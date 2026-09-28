import { Unzip, UnzipInflate } from "fflate";
import { previewLeadTable, type LeadImportMapping, type LeadImportRow } from "./leadImport";

const main = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
const office = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
const packages = "http://schemas.openxmlformats.org/package/2006/relationships";
const maxPartBytes = 2_097_152;
const invalid = () => new Error("无法读取此工作簿，请使用单工作表 XLSX 线索模板后重试。");
type SheetRow = { rowNumber: number; cells: string[]; numericColumns: number[] };
export type LeadXlsxTable = { headers: string[]; records: SheetRow[] };

function xml(bytes: Uint8Array): Document {
  const text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  if (/<!DOCTYPE|<!ENTITY/i.test(text)) throw invalid();
  const document = new DOMParser().parseFromString(text, "application/xml");
  if (document.getElementsByTagName("parsererror").length) throw invalid();
  return document;
}
const elements = (node: Document | Element, name: string, ns = main) => Array.from(node.getElementsByTagNameNS(ns, name));
const children = (node: Element, name: string) => Array.from(node.children).filter(child => child.namespaceURI === main && child.localName === name);

function zipDirectory(bytes: Uint8Array): Map<string, { size: number; crc: number }> {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let end = bytes.length - 22;
  for (; end >= Math.max(0, bytes.length - 65_557); end--) {
    if (view.getUint32(end, true) === 0x06054b50 && end + 22 + view.getUint16(end + 20, true) === bytes.length) break;
  }
  if (end < Math.max(0, bytes.length - 65_557)) throw invalid();
  const count = view.getUint16(end + 10, true), start = view.getUint32(end + 16, true), length = view.getUint32(end + 12, true);
  if (view.getUint16(end + 4, true) || view.getUint16(end + 6, true) || count !== view.getUint16(end + 8, true)
    || count < 1 || count > 128 || start + length !== end) throw invalid();
  const entries = new Map<string, { size: number; crc: number }>();
  let offset = start;
  const ranges: [number, number][] = [];
  for (let index = 0; index < count; index++) {
    if (offset + 46 > end || view.getUint32(offset, true) !== 0x02014b50) throw invalid();
    const flags = view.getUint16(offset + 8, true), method = view.getUint16(offset + 10, true);
    const compressed = view.getUint32(offset + 20, true), size = view.getUint32(offset + 24, true);
    const nameLength = view.getUint16(offset + 28, true), extra = view.getUint16(offset + 30, true), comment = view.getUint16(offset + 32, true);
    const local = view.getUint32(offset + 42, true), next = offset + 46 + nameLength + extra + comment;
    if (next > end || flags & 1 || ![0, 8].includes(method) || view.getUint16(offset + 34, true) || local + 30 > start) throw invalid();
    const name = new TextDecoder("utf-8", { fatal: true }).decode(bytes.subarray(offset + 46, offset + 46 + nameLength));
    if (entries.has(name) || view.getUint32(local, true) !== 0x04034b50 || view.getUint16(local + 6, true) !== flags || view.getUint16(local + 8, true) !== method) throw invalid();
    const localNameLength = view.getUint16(local + 26, true), dataStart = local + 30 + localNameLength + view.getUint16(local + 28, true);
    if (dataStart + compressed > start || name !== new TextDecoder("utf-8", { fatal: true }).decode(bytes.subarray(local + 30, local + 30 + localNameLength))) throw invalid();
    if (!(flags & 8) && (view.getUint32(local + 18, true) !== compressed || view.getUint32(local + 22, true) !== size)) throw invalid();
    ranges.push([local, dataStart + compressed]);
    entries.set(name, { size, crc: view.getUint32(offset + 16, true) });
    offset = next;
  }
  ranges.sort((a, b) => a[0] - b[0]);
  if (offset !== end || ranges.some((range, index) => index > 0 && range[0] < ranges[index - 1][1])) throw invalid();
  return entries;
}
const crcTable = Uint32Array.from({ length: 256 }, (_, index) => {
  let value = index;
  for (let bit = 0; bit < 8; bit++) value = value & 1 ? 0xedb88320 ^ (value >>> 1) : value >>> 1;
  return value >>> 0;
});

/** Inflate incrementally and cap actual output, not untrusted ZIP size claims. Nothing is written to disk. */
function parts(bytes: Uint8Array): Map<string, Uint8Array> {
  if (bytes.byteLength > 1_048_576) throw new Error("每次导入文件不能超过 1 MB。");
  if (bytes.length < 22 || bytes[0] !== 0x50 || bytes[1] !== 0x4b) throw invalid();
  const directory = zipDirectory(bytes);
  const result = new Map<string, Uint8Array>(), seen = new Set<string>();
  let expanded = 0, started = 0, completed = 0;
  const unzip = new Unzip(file => {
    if (seen.size >= 128 || seen.has(file.name) || /(^\/|\\|(?:^|\/)\.\.(?:\/|$))/.test(file.name)) throw invalid();
    seen.add(file.name);
    const expected = directory.get(file.name);
    if (!expected) throw invalid();
    if (/vbaProject|externalLinks|embeddings/i.test(file.name)) throw invalid();
    if (!/^\[Content_Types\]\.xml$|^xl\/(?:workbook\.xml|_rels\/workbook\.xml\.rels|sharedStrings\.xml|worksheets\/[A-Za-z0-9_-]+\.xml)$/.test(file.name)) return;
    if (expected.size > maxPartBytes || (file.originalSize !== undefined && file.originalSize !== expected.size)) throw invalid();
    let length = 0, crc = 0xffffffff;
    const chunks: Uint8Array[] = [];
    started++;
    file.ondata = (error, data, final) => {
      if (error) throw invalid();
      length += data.length; expanded += data.length;
      if (length > maxPartBytes || expanded > 8_388_608) { file.terminate(); throw invalid(); }
      for (const byte of data) crc = crcTable[(crc ^ byte) & 255] ^ (crc >>> 8);
      chunks.push(data);
      if (final) {
        if (length !== expected.size || ((crc ^ 0xffffffff) >>> 0) !== expected.crc) throw invalid();
        const value = new Uint8Array(length); let offset = 0;
        for (const chunk of chunks) { value.set(chunk, offset); offset += chunk.length; }
        result.set(file.name, value); completed++;
      }
    };
    file.start();
  });
  unzip.register(UnzipInflate);
  // Bounded input chunks also bound each synchronous inflater output before the cap is checked.
  for (let offset = 0; offset < bytes.length; offset += 1024) unzip.push(bytes.subarray(offset, offset + 1024), offset + 1024 >= bytes.length);
  if (started !== completed || seen.size !== directory.size) throw invalid();
  return result;
}

export function readLeadXlsx(bytes: Uint8Array): LeadXlsxTable {
  const archive = parts(bytes);
  const part = (name: string) => { const value = archive.get(name); if (!value) throw invalid(); return xml(value); };
  const contentTypes = part("[Content_Types].xml");
  if (!Array.from(contentTypes.getElementsByTagNameNS("http://schemas.openxmlformats.org/package/2006/content-types", "Override"))
    .some(value => value.getAttribute("PartName") === "/xl/workbook.xml" && value.getAttribute("ContentType") === "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml")) throw invalid();
  const workbook = part("xl/workbook.xml"), sheets = elements(workbook, "sheet");
  if (sheets.length !== 1) throw new Error("请仅保留一个工作表后导入，避免遗漏或选错线索。");
  if (sheets[0].hasAttribute("state") && sheets[0].getAttribute("state") !== "visible") throw new Error("请取消工作表隐藏后核对导入。");
  const id = sheets[0].getAttributeNS(office, "id");
  const relationships = elements(part("xl/_rels/workbook.xml.rels"), "Relationship", packages);
  const matching = relationships.filter(value => value.getAttribute("Id") === id);
  if (!id || matching.length !== 1) throw invalid();
  const relation = matching[0], target = relation.getAttribute("Target");
  if (relation.getAttribute("TargetMode") === "External" || relation.getAttribute("Type") !== `${office}/worksheet` || !target) throw invalid();
  const path = target.startsWith("/xl/") ? target.slice(1) : `xl/${target}`;
  if (!/^xl\/worksheets\/[A-Za-z0-9_-]+\.xml$/.test(path)) throw invalid();
  const worksheet = part(path);
  if (elements(worksheet, "mergeCell").length) throw new Error("请取消合并单元格并逐行补齐内容后导入。");
  if (elements(worksheet, "f").length) throw new Error("工作表含公式，请转换为文本值并核对后导入。");
  if (elements(worksheet, "col").some(col => ["1", "true"].includes(col.getAttribute("hidden") ?? ""))) throw new Error("请取消隐藏列后核对导入。");
  const shared = archive.has("xl/sharedStrings.xml") ? elements(part("xl/sharedStrings.xml"), "si").map(readText) : [];
  if (shared.length > 20_000) throw invalid();
  const data = elements(worksheet, "sheetData");
  if (data.length !== 1) throw invalid();
  const parsed: SheetRow[] = [];
  let previous = 0;
  for (const row of children(data[0], "row")) {
    const rowNumber = Number(row.getAttribute("r"));
    if (!Number.isSafeInteger(rowNumber) || rowNumber <= previous || rowNumber > 1_048_576) throw invalid();
    previous = rowNumber;
    if (["1", "true"].includes(row.getAttribute("hidden") ?? "")) throw new Error("请取消隐藏行后核对导入。");
    const cells: string[] = [], numericColumns: number[] = [];
    let previousColumn = -1;
    for (const cell of children(row, "c")) {
      const ref = /^([A-Z]{1,3})([1-9][0-9]*)$/.exec(cell.getAttribute("r") ?? "");
      if (!ref || Number(ref[2]) !== rowNumber) throw invalid();
      const column = Array.from(ref[1]).reduce((n, letter) => n * 26 + letter.charCodeAt(0) - 64, 0) - 1;
      if (column <= previousColumn || column >= 64) throw invalid();
      previousColumn = column;
      const type = cell.getAttribute("t") ?? "n", values = children(cell, "v");
      if (values.length > 1) throw invalid();
      const value = values[0]?.textContent ?? "";
      let text: string;
      if (type === "inlineStr") {
        const inline = children(cell, "is");
        if (inline.length > 1 || values.length) throw invalid();
        text = inline.length ? readText(inline[0]) : "";
      } else if (type === "s") {
        if (!/^(0|[1-9][0-9]*)$/.test(value) || !Object.hasOwn(shared, Number(value))) throw invalid();
        text = shared[Number(value)];
      } else if (type === "str") text = decodeXstring(value);
      else if (type === "n") {
        if (value && !/^-?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/.test(value)) throw invalid();
        text = value; if (value) numericColumns.push(column);
      } else throw new Error("含有无法作为线索字段的单元格，请转换为文本后核对。");
      while (cells.length <= column) cells.push("");
      cells[column] = text;
    }
    if (cells.every(value => !value.trim())) continue;
    parsed.push({ rowNumber, cells, numericColumns });
    if (parsed.length > 201) throw new Error("每次最多导入 200 条线索，请拆分文件。");
  }
  const first = parsed.shift();
  if (!first || first.rowNumber !== 1) throw new Error("请在第一行填写唯一的字段表头。");
  const headers = first.cells.map(value => value.trim());
  if (headers.some(value => !value) || new Set(headers).size !== headers.length) throw invalid();
  for (const record of parsed) {
    if (record.cells.length > headers.length) throw invalid();
    while (record.cells.length < headers.length) record.cells.push("");
  }
  return { headers, records: parsed };
}

// ST_Xstring: decode once per XML text value so _x005F_x0041_ stays the literal _x0041_.
// https://learn.microsoft.com/en-us/openspecs/office_standards/ms-oe376/bd0aa042-434a-4ca7-b25f-4e1fd25a954d
function decodeXstring(value: string): string {
  return value.replace(/_x([0-9A-Fa-f]{4})_/g, (_, code: string) => String.fromCharCode(Number.parseInt(code, 16)));
}
function readText(node: Element): string {
  // Phonetic annotations are not part of the cell's visible identifier.
  return Array.from(node.children).map(child => child.namespaceURI !== main ? "" : child.localName === "t"
    ? decodeXstring(child.textContent ?? "") : child.localName === "r" ? children(child, "t").map(t => decodeXstring(t.textContent ?? "")).join("") : "").join("");
}

export function previewLeadXlsx(bytes: Uint8Array, mapping: LeadImportMapping): LeadImportRow[] {
  return previewLeadXlsxTable(readLeadXlsx(bytes), mapping);
}

export function previewLeadXlsxTable(table: LeadXlsxTable, mapping: LeadImportMapping): LeadImportRow[] {
  const rows = previewLeadTable(table.headers, table.records, mapping);
  const sourceColumn = table.headers.indexOf(mapping.sourceRecordKey ?? "");
  for (let index = 0; index < rows.length; index++) if (table.records[index].numericColumns.includes(sourceColumn)) {
    rows[index].errors.push({ field: "sourceRecordKey", message: "来源记录号必须为文本，请核对前导零及完整编号。" });
  }
  return rows;
}
