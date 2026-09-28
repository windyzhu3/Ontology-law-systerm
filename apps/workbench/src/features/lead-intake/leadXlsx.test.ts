/// <reference types="node" />
import { describe, expect, it } from "vitest";
import { zipSync, strToU8 } from "fflate";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { previewLeadXlsx, readLeadXlsx } from "./leadXlsx";
import { readLeadImportFile } from "./leadImportFile";

const ns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
const relations = "http://schemas.openxmlformats.org/package/2006/relationships";
const mapping = { sourceRecordKey: "来源记录", capturedName: "姓名", legalNeedSummary: "需求" };
const textCell = (ref: string, text: string) => `<c r="${ref}" t="inlineStr"><is><t>${text}</t></is></c>`;
const header = `<row r="1">${textCell("A1", "来源记录")}${textCell("B1", "姓名")}${textCell("C1", "需求")}</row>`;
function workbook(rows: string, overrides: Record<string, string> = {}) {
  const files = {
    "[Content_Types].xml": `<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/></Types>`,
    "xl/workbook.xml": `<workbook xmlns="${ns}" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="线索" sheetId="1" r:id="rId1"/></sheets></workbook>`,
    "xl/_rels/workbook.xml.rels": `<Relationships xmlns="${relations}"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/intake.xml"/></Relationships>`,
    "xl/worksheets/intake.xml": `<worksheet xmlns="${ns}"><sheetData>${header}${rows}</sheetData></worksheet>`,
    ...overrides,
  };
  return zipSync(Object.fromEntries(Object.entries(files).map(([name, value]) => [name, strToU8(value)])));
}
const validRow = (n = 2, key = "a001") => `<row r="${n}">${textCell(`A${n}`, key)}${textCell(`B${n}`, "王女士")}${textCell(`C${n}`, "确认需求")}</row>`;

describe("bounded XLSX intake preview", () => {
  it("reads an independently generated openpyxl workbook through the file adapter", async () => {
    const bytes = readFileSync(resolve("src/test/lead-intake-fixtures/lead-intake-openpyxl.xlsx"));
    const file = await readLeadImportFile({ name: "线索.XLSX", size: bytes.length, arrayBuffer: async () => Uint8Array.from(bytes).buffer });
    expect(file.headers).toEqual(["来源记录", "姓名", "电话", "需求"]);
    const rows = file.preview({ ...mapping, phone: "电话" });
    expect(rows.map(row => [row.rowNumber, row.values.sourceRecordKey, row.errors])).toEqual([[2, "0001", []], [8, "0002", []]]);
    expect(rows[0].values.phone).toBe("+8613800138000");
  });
  it("reads a workbook relationship instead of guessing sheet1 and preserves source row numbers", () => {
    const bytes = workbook(validRow(2) + validRow(7, "a002"));
    expect(readLeadXlsx(bytes).headers).toEqual(["来源记录", "姓名", "需求"]);
    expect(previewLeadXlsx(bytes, mapping)).toEqual([
      { rowNumber: 2, values: { sourceRecordKey: "a001", capturedName: "王女士", legalNeedSummary: "确认需求" }, errors: [] },
      { rowNumber: 7, values: { sourceRecordKey: "a002", capturedName: "王女士", legalNeedSummary: "确认需求" }, errors: [] },
    ]);
  });
  it("reads shared and rich inline strings without trimming source identity", () => {
    const bytes = workbook(`<row r="2"><c r="A2" t="s"><v>0</v></c><c r="B2" t="inlineStr"><is><r><t>王</t></r><r><t>女士</t></r></is></c>${textCell("C2", "确认需求")}</row>`, {
      "xl/sharedStrings.xml": `<sst xmlns="${ns}"><si><t xml:space="preserve"> a001 </t></si></sst>`,
    });
    expect(previewLeadXlsx(bytes, mapping)[0]).toMatchObject({ values: { sourceRecordKey: " a001 ", capturedName: "王女士" }, errors: [] });
  });
  it("decodes Excel Xstring escapes once, preserving escaped literal source keys", () => {
    const bytes = workbook(`<row r="2">${textCell("A2", "_x005F_x0041_")}${textCell("C2", "甲_x000D_乙")}</row><row r="3"><c r="A3" t="s"><v>0</v></c><c r="C3" t="str"><v>丙_x000D_丁</v></c></row>`, {
      "xl/sharedStrings.xml": `<sst xmlns="${ns}"><si><t>_x005F_x0042_</t></si></sst>`,
    });
    const rows = previewLeadXlsx(bytes, mapping);
    expect(rows.map(row => row.values.sourceRecordKey)).toEqual(["_x0041_", "_x0042_"]);
    expect(rows.map(row => row.values.legalNeedSummary)).toEqual(["甲\r乙", "丙\r丁"]);
    expect(rows.every(row => row.errors.some(error => error.field === "legalNeedSummary"))).toBe(true);
  });
  it("reuses duplicate and missing-field validation", () => {
    const rows = previewLeadXlsx(workbook(validRow(2) + `<row r="3">${textCell("A3", "a001")}</row>`), mapping);
    expect(rows.every(row => row.errors.some(e => e.field === "sourceRecordKey"))).toBe(true);
    expect(rows[1].errors.some(e => e.field === "legalNeedSummary")).toBe(true);
  });
  it("does not silently use a formula's cached result", () => {
    expect(() => previewLeadXlsx(workbook(`<row r="2"><c r="A2"><f>1+1</f><v>2</v></c>${textCell("C2", "需求")}</row>`), mapping)).toThrow("公式");
  });
  it("flags numeric source identifiers so Excel rounding or leading-zero formatting cannot change identity silently", () => {
    const row = previewLeadXlsx(workbook(`<row r="2"><c r="A2"><v>123</v></c>${textCell("C2", "需求")}</row>`), mapping)[0];
    expect(row.errors).toContainEqual({ field: "sourceRecordKey", message: "来源记录号必须为文本，请核对前导零及完整编号。" });
  });
  it("rejects merged or hidden rows rather than importing an ambiguous view", () => {
    expect(() => readLeadXlsx(workbook("", { "xl/worksheets/intake.xml": `<worksheet xmlns="${ns}"><sheetData>${header}${validRow()}</sheetData><mergeCells><mergeCell ref="A2:B2"/></mergeCells></worksheet>` }))).toThrow("合并");
    expect(() => readLeadXlsx(workbook(validRow().replace('r="2"', 'r="2" hidden="1"')))).toThrow("隐藏");
  });
  it("rejects multiple worksheets and external worksheet relationships without fetching anything", () => {
    expect(() => readLeadXlsx(workbook(validRow(), { "xl/workbook.xml": `<workbook xmlns="${ns}"><sheets><sheet name="甲"/><sheet name="乙"/></sheets></workbook>` }))).toThrow("一个工作表");
    expect(() => readLeadXlsx(workbook(validRow(), { "xl/_rels/workbook.xml.rels": `<Relationships xmlns="${relations}"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" TargetMode="External" Target="https://example.test/sheet.xml"/></Relationships>` }))).toThrow();
  });
  it("rejects malformed archives, missing parts, macros, and entity declarations", () => {
    expect(() => readLeadXlsx(new Uint8Array([1, 2, 3]))).toThrow();
    expect(() => readLeadXlsx(zipSync({ "unrelated.txt": strToU8("nothing") }))).toThrow();
    expect(() => readLeadXlsx(workbook(validRow(), { "xl/vbaProject.bin": "macro" }))).toThrow();
    expect(() => readLeadXlsx(workbook(validRow(), { "xl/sharedStrings.xml": '<!DOCTYPE sst [<!ENTITY x "value">]><sst/>' }))).toThrow();
  });
  it("rejects duplicate cells, wrong cell rows and out-of-range shared strings", () => {
    for (const cells of [textCell("A2", "a") + textCell("A2", "b"), textCell("A3", "a"), '<c r="A2" t="s"><v>999</v></c>']) {
      expect(() => readLeadXlsx(workbook(`<row r="2">${cells}</row>`))).toThrow();
    }
  });
  it("bounds file, expansion, column and nonempty-row sizes before producing a preview", () => {
    expect(() => readLeadXlsx(new Uint8Array(1_048_577))).toThrow("1 MB");
    expect(() => readLeadXlsx(workbook(Array.from({ length: 201 }, (_, i) => validRow(i + 2, `k${i}`)).join("")))).toThrow("200");
    expect(() => readLeadXlsx(workbook(`<row r="2">${textCell("XFD2", "a")}</row>`))).toThrow();
    expect(() => readLeadXlsx(workbook("", { "xl/sharedStrings.xml": "x".repeat(2_097_153) }))).toThrow();
  });
  it("rejects a truncated ZIP directory instead of accepting locally readable fragments", () => {
    const bytes = workbook(validRow());
    expect(() => readLeadXlsx(bytes.subarray(0, bytes.length - 22))).toThrow();
  });
  it("rejects a mismatched checksum even when the XML still parses", () => {
    const bytes = workbook(validRow());
    const view = new DataView(bytes.buffer);
    const directory = view.getUint32(bytes.length - 6, true);
    view.setUint32(directory + 16, view.getUint32(directory + 16, true) ^ 1, true);
    expect(() => readLeadXlsx(bytes)).toThrow();
  });
  it("bounds actual inflated output even when local and directory sizes both lie", () => {
    const bytes = workbook(validRow(), { "xl/sharedStrings.xml": "x".repeat(2_097_153) });
    const view = new DataView(bytes.buffer);
    let offset = view.getUint32(bytes.length - 6, true);
    while (view.getUint32(offset, true) === 0x02014b50) {
      const length = view.getUint16(offset + 28, true);
      const name = new TextDecoder().decode(bytes.subarray(offset + 46, offset + 46 + length));
      if (name === "xl/sharedStrings.xml") {
        view.setUint32(offset + 24, 1, true);
        view.setUint32(view.getUint32(offset + 42, true) + 22, 1, true);
        break;
      }
      offset += 46 + length + view.getUint16(offset + 30, true) + view.getUint16(offset + 32, true);
    }
    expect(() => readLeadXlsx(bytes)).toThrow();
  });
});
