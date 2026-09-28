import type { components } from "../../generated/api/schema";
export type IntakeSource = components["schemas"]["LeadIntakeSourceV1"];
export type CaptureFields = Partial<Record<"contactName" | "customerName" | "phone" | "email", string>> & { legalNeedSummary: string };
export function captureBody(source: IntakeSource, fields: CaptureFields, sourceRecordKey: string, now = new Date()): components["schemas"]["CaptureLeadV1"] {
  const values: Partial<CaptureFields> = {};
  for (const key of ["customerName", "contactName", "legalNeedSummary"] as const) {
    const raw = fields[key] ?? "";
    if (/[\u0000-\u001f\u007f-\u009f]/.test(raw) || [...raw.trim()].length > (key === "legalNeedSummary" ? 2000 : 200)) throw new Error("姓名和需求请使用单段文字，并核对长度。");
    if (raw.trim()) values[key] = raw.trim();
  }
  if (!values.legalNeedSummary) throw new Error("请填写需求描述。");
  let phone = fields.phone?.trim().replace(/[ -]/g, "") ?? "";
  if (source.jurisdictionCode === "CN" && /^1[3-9][0-9]{9}$/.test(phone)) phone = `+86${phone}`;
  if (phone && !/^\+[1-9][0-9]{0,14}$/.test(phone)) throw new Error("请核对手机号，非中国大陆号码需带国家或地区代码。");
  const email = fields.email?.trim();
  if (email && (email.length > 320 || !/^[^\s@]+@[^\s@]+$/.test(email))) throw new Error("请核对邮箱地址。");
  return { sourceAccountCode: source.sourceAccountCode, sourceChannelCode: source.sourceChannelCode, serviceCategoryCode: source.serviceCategoryCode, jurisdictionCode: source.jurisdictionCode, urgencyCode: source.urgencyCode,
    sourceRecordKey, capturedAt: now.toISOString().replace(/Z$/, "000Z"), ...values, legalNeedSummary: values.legalNeedSummary, ...(phone ? { phone } : {}), ...(email ? { email } : {}) };
}
