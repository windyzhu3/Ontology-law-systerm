import { describe, expect, it } from "vitest";
import { previewLeadCsv } from "./leadImport";

const mapping = { sourceRecordKey: "来源记录", capturedName: "姓名", phone: "电话", legalNeedSummary: "需求" };

describe("bounded lead CSV preview", () => {
  it("preserves record identity and quoted multiline text without interpreting formulas", () => {
    const result = previewLeadCsv('\uFEFF来源记录,姓名,电话,需求\r\na001,"王,女士",+8613800138000,"第一行\r\n第二行"\r\na002,=SUM(A1),,确认需求\r\n', mapping);
    expect(result).toEqual([
      { rowNumber: 2, values: { sourceRecordKey: "a001", capturedName: "王,女士", phone: "+8613800138000", legalNeedSummary: "第一行\n第二行" }, errors: [{ field: "legalNeedSummary", message: "请移除换行或控制字符后再确认。" }] },
      { rowNumber: 3, values: { sourceRecordKey: "a002", capturedName: "=SUM(A1)", legalNeedSummary: "确认需求" }, errors: [] },
    ]);
  });
  it("marks every duplicate source key rather than silently choosing a row", () => {
    const result = previewLeadCsv("来源记录,姓名,电话,需求\na,甲,,需求甲\na,乙,,需求乙", mapping);
    expect(result).toHaveLength(2);
    expect(result.every(row => row.errors.some(error => error.field === "sourceRecordKey"))).toBe(true);
  });
  it("keeps row errors separate so valid rows remain reviewable", () => {
    const result = previewLeadCsv("来源记录,姓名,电话,需求\n,甲,,\nb,乙,,有效需求", mapping);
    expect(result[0].errors.map(error => error.field)).toEqual(["sourceRecordKey", "legalNeedSummary"]);
    expect(result[1].errors).toEqual([]);
  });
  it.each([
    '来源记录,姓名,电话,需求\na,"未闭合,,需求',
    '来源记录,姓名,电话,需求\na,"姓名"外文,,需求',
    '来源记录,姓名,电话,需求\na,姓"名,,需求',
    '来源记录,姓名,电话,需求\na,甲,,需求,多余',
    '来源记录,来源记录,电话,需求\na,甲,,需求',
  ])("rejects ambiguous CSV structure: %s", text => {
    expect(() => previewLeadCsv(text, mapping)).toThrow();
  });
  it("rejects missing or multiply assigned required columns", () => {
    expect(() => previewLeadCsv("姓名,需求\n甲,说明", mapping)).toThrow();
    expect(() => previewLeadCsv("来源记录,姓名,电话,需求\na,甲,,说明", { ...mapping, legalNeedSummary: "来源记录" })).toThrow();
  });
  it("bounds batches before submission and rejects oversized input", () => {
    expect(() => previewLeadCsv("来源记录,姓名,电话,需求\n" + Array.from({ length: 201 }, (_, i) => `${i},甲,,说明`).join("\n"), mapping)).toThrow();
    expect(() => previewLeadCsv("x".repeat(1_048_577), mapping)).toThrow();
  });
  it("treats blank contacts as incomplete intake, not an invalid row", () => {
    const result = previewLeadCsv("来源记录,姓名,电话,需求\na,甲,,说明", mapping);
    expect(result[0].errors).toEqual([]);
    expect(result[0].values).not.toHaveProperty("phone");
  });
  it("flags overlong legal need without truncating user data", () => {
    const result = previewLeadCsv("来源记录,姓名,电话,需求\na,甲,," + "法".repeat(2001), mapping);
    expect(result[0].errors.map(error => error.field)).toContain("legalNeedSummary");
    expect(result[0].values.legalNeedSummary).toHaveLength(2001);
  });
  it("does not rewrite a source record key or merge distinct source identities", () => {
    const result = previewLeadCsv("来源记录,姓名,电话,需求\n a ,甲,,需求甲\na,乙,,需求乙", mapping);
    expect(result.map(row => row.values.sourceRecordKey)).toEqual([" a ", "a"]);
    expect(result.map(row => row.errors)).toEqual([[], []]);
  });
  it("flags contact and control-character formats rejected by the capture endpoint", () => {
    const rows = previewLeadCsv('来源记录,姓名,电话,邮件,需求\na,甲,13800138000,not-email,"第一行\n第二行"', { ...mapping, email: "邮件" });
    expect(rows[0].errors.map(error => error.field)).toEqual(["phone", "email", "legalNeedSummary"]);
  });
});
