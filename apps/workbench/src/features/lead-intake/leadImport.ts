export type LeadImportField = "sourceRecordKey" | "capturedName" | "customerName" | "contactName" | "phone" | "email" | "legalNeedSummary";
export type LeadImportMapping = Partial<Record<LeadImportField, string>>;
export type LeadImportRow = {
  rowNumber: number;
  values: Partial<Record<LeadImportField, string>>;
  errors: Array<{ field: LeadImportField; message: string }>;
};
const limits: Record<LeadImportField, number> = { sourceRecordKey: 256, capturedName: 200, customerName: 200, contactName: 200, phone: 16, email: 320, legalNeedSummary: 2000 };
const required: LeadImportField[] = ["sourceRecordKey", "legalNeedSummary"];
const invalid = () => new Error("文件格式无法识别，请核对表头、引号和列数后重试。");

/** Preview only: never evaluates cells or creates business facts. Record keys come from the source, not row order. */
export function previewLeadCsv(text: string, mapping: LeadImportMapping): LeadImportRow[] {
  const table = readLeadCsv(text);
  return previewLeadTable(table.headers, table.records, mapping);
}

export function readLeadCsv(text: string) {
  if (new TextEncoder().encode(text).length > 1_048_576) throw new Error("每次导入文件不能超过 1 MB。");
  const records = parseCsv(text.replace(/^\uFEFF/, "").replace(/\r\n?/g, "\n"));
  const headers = records.shift()?.map(value => value.trim()) ?? [];
  if (!headers.length || headers.some(value => !value) || new Set(headers).size !== headers.length) throw invalid();
  const rows = records.map((cells, index) => ({ rowNumber: index + 2, cells })).filter(row => row.cells.some(value => value.trim()));
  if (rows.length > 200) throw new Error("每次最多导入 200 条线索，请拆分文件。");
  if (!rows.length) throw new Error("文件没有可导入的线索。");
  if (rows.some(row => row.cells.length !== headers.length)) throw invalid();
  return { headers, records: rows };
}

/** Shared capture-field validation for CSV and XLSX; physical sheet row numbers remain intact. */
export function previewLeadTable(rawHeaders: readonly string[], records: readonly { rowNumber: number; cells: readonly string[] }[], mapping: LeadImportMapping): LeadImportRow[] {
  const headers = rawHeaders.map(value => value.trim());
  if (!headers?.length || headers.some(value => !value) || new Set(headers).size !== headers.length) throw invalid();
  const entries = Object.entries(mapping) as [LeadImportField, string][];
  if (required.some(field => !mapping[field]) || entries.some(([field, header]) => !Object.hasOwn(limits, field) || !headers.includes(header))
    || new Set(entries.map(([, header]) => header)).size !== entries.length) throw invalid();
  const columns = entries.map(([field, header]) => [field, headers.indexOf(header)] as const);
  const rows: LeadImportRow[] = [];
  records.forEach(({ cells: record, rowNumber }) => {
    if (record.every(value => !value.trim())) return;
    if (record.length !== headers.length) throw invalid();
    const values: LeadImportRow["values"] = {};
    for (const [field, column] of columns) {
      const value = field === "sourceRecordKey" ? record[column] : record[column].trim();
      if (value.trim()) values[field] = value;
    }
    const errors: LeadImportRow["errors"] = [];
    for (const field of required) if (!values[field]) errors.push({ field, message: "请补齐此项。" });
    for (const [field] of columns) if (values[field] && Array.from(values[field]!).length > limits[field]) errors.push({ field, message: "内容超过允许长度，请核对。" });
    if (values.phone && !/^\+[1-9][0-9]{0,14}$/.test(values.phone)) errors.push({ field: "phone", message: "请填写含国家区号的电话号码，例如 +8613800138000。" });
    if (values.email && !/^[^\s@]+@[^\s@]+$/.test(values.email)) errors.push({ field: "email", message: "请核对邮箱地址格式。" });
    for (const field of ["capturedName", "customerName", "contactName", "legalNeedSummary"] as const) {
      if (values[field] && /[\u0000-\u001F\u007F-\u009F]/.test(values[field])) errors.push({ field, message: "请移除换行或控制字符后再确认。" });
    }
    rows.push({ rowNumber, values, errors });
    if (rows.length > 200) throw new Error("每次最多导入 200 条线索，请拆分文件。");
  });
  const counts = new Map<string, number>();
  for (const row of rows) if (row.values.sourceRecordKey) counts.set(row.values.sourceRecordKey, (counts.get(row.values.sourceRecordKey) ?? 0) + 1);
  for (const row of rows) if (row.values.sourceRecordKey && counts.get(row.values.sourceRecordKey)! > 1) row.errors.push({ field: "sourceRecordKey", message: "来源记录重复，请核对后保留准确记录。" });
  if (!rows.length) throw new Error("文件没有可导入的线索。");
  return rows;
}

function parseCsv(text: string): string[][] {
  const records: string[][] = [];
  let record: string[] = [], value = "", quoted = false, closed = false;
  for (let i = 0; i < text.length; i++) {
    const character = text[i];
    if (quoted) {
      if (character === '"') {
        if (text[i + 1] === '"') { value += '"'; i++; }
        else { quoted = false; closed = true; }
      } else value += character;
    } else if (character === "," || character === "\n") {
      record.push(value); value = ""; closed = false;
      if (character === "\n") { records.push(record); record = []; }
    } else if (closed) throw invalid();
    else if (character === '"') {
      if (value) throw invalid();
      quoted = true;
    } else value += character;
  }
  if (quoted) throw invalid();
  if (value || record.length || closed) { record.push(value); records.push(record); }
  return records;
}
