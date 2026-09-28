import { expect, it, vi } from "vitest";
import { readLeadImportFile } from "./leadImportFile";
const mapping = { sourceRecordKey: "来源记录", capturedName: "姓名", legalNeedSummary: "需求" };
const data = new TextEncoder().encode("来源记录,姓名,需求\na001,王女士,确认需求");
const file = (name = "线索.csv", bytes = data) => ({ name, size: bytes.byteLength, arrayBuffer: async () => bytes.slice().buffer });
it("exposes CSV headers and validates mapping without uploading the source file", async () => {
  const input = await readLeadImportFile(file());
  expect(input.headers).toEqual(["来源记录", "姓名", "需求"]);
  expect(input.preview(mapping)[0]).toMatchObject({ rowNumber: 2, values: { sourceRecordKey: "a001" }, errors: [] });
  expect(() => input.preview({ ...mapping, sourceRecordKey: "姓名" })).toThrow();
});
it("rejects oversize and unsupported files before reading their contents", async () => {
  const read = vi.fn(async () => data.buffer);
  await expect(readLeadImportFile({ ...file(), size: 1_048_577, arrayBuffer: read })).rejects.toThrow("1 MB");
  await expect(readLeadImportFile({ ...file("线索.xlsm"), arrayBuffer: read })).rejects.toThrow("CSV 或 XLSX");
  expect(read).not.toHaveBeenCalled();
});
it("discards a file read when the selection is cancelled", async () => {
  const controller = new AbortController();
  await expect(readLeadImportFile({ ...file(), arrayBuffer: async () => { controller.abort(); return data.buffer; } }, controller.signal)).rejects.toThrow("取消");
});
it("rejects non-UTF-8 CSV and renamed non-XLSX input with a readable error", async () => {
  await expect(readLeadImportFile(file("线索.csv", new Uint8Array([255, 254, 0])))).rejects.toThrow("UTF-8");
  await expect(readLeadImportFile(file("线索.xlsx"))).rejects.toThrow("工作簿");
});
it("rechecks actual byte size after reading rather than trusting file metadata", async () => {
  await expect(readLeadImportFile({ ...file(), arrayBuffer: async () => new ArrayBuffer(1_048_577) })).rejects.toThrow("1 MB");
});
