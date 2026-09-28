import { readLeadCsv, previewLeadTable, type LeadImportMapping, type LeadImportRow } from "./leadImport";
import { readLeadXlsx, previewLeadXlsxTable } from "./leadXlsx";

type ImportFile = Pick<File, "name" | "size" | "arrayBuffer">;
export type LeadImportFile = Readonly<{
  headers: readonly string[];
  preview(mapping: LeadImportMapping): LeadImportRow[];
}>;

/** Reads once, in memory. Callers abort on file/session changes; no network or business side effect. */
export async function readLeadImportFile(file: ImportFile, signal?: AbortSignal): Promise<LeadImportFile> {
  const active = () => { if (signal?.aborted) throw new Error("已取消文件读取，请重新选择。"); };
  const bounded = (size: number) => { if (!Number.isSafeInteger(size) || size < 1 || size > 1_048_576) throw new Error("请选择非空文件，每次导入不能超过 1 MB。"); };
  active(); bounded(file.size);
  const extension = file.name.toLowerCase().split(".").pop();
  if (extension !== "csv" && extension !== "xlsx") throw new Error("请选择 CSV 或 XLSX 文件。");
  let buffer: ArrayBuffer;
  try { buffer = await file.arrayBuffer(); } catch { active(); throw new Error("无法读取文件，请重新选择。"); }
  active(); bounded(buffer.byteLength);
  const bytes = new Uint8Array(buffer);
  if (extension === "xlsx") {
    const table = readLeadXlsx(bytes);
    active();
    return Object.freeze({ headers: Object.freeze([...table.headers]), preview: (mapping: LeadImportMapping) => {
      active(); return previewLeadXlsxTable(table, mapping);
    } });
  }
  let text: string;
  try { text = new TextDecoder("utf-8", { fatal: true }).decode(bytes); }
  catch { throw new Error("请将 CSV 保存为 UTF-8 编码后重新导入。"); }
  const table = readLeadCsv(text);
  return Object.freeze({ headers: Object.freeze([...table.headers]), preview: (mapping: LeadImportMapping) => {
    active(); return previewLeadTable(table.headers, table.records, mapping);
  } });
}
