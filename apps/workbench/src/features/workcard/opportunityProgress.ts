import type { components } from "../../generated/api/schema";
import { isObject, sameValues } from "./contract";

type S = components["schemas"];
export type OpportunityValues = S["OpportunityProgressValuesV1"];
export const progressChoices = [
  ["PHONE_CONNECTED", "已接通电话"], ["CLIENT_VISIT", "客户来访"],
  ["MEETING", "面谈"], ["SITE_VISIT", "外访"], ["WECHAT_CONNECTED", "有效微信对话"],
] as const;
const fields = ["progressTypeCode", "progressSummary", "occurredAt", "nextCheckAt"];
const exactKeys = (v: Record<string, unknown>, names: string[]) =>
  Object.keys(v).length === names.length && names.every(name => Object.hasOwn(v, name));
const revision = (v: unknown) => Number.isSafeInteger(v) && Number(v) >= 0;
const hash = (v: unknown) => typeof v === "string" && /^[A-Za-z0-9_-]{43}$/.test(v);
const uuid = (v: unknown) => typeof v === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(v);
function instant(v: unknown): v is string {
  if (typeof v !== "string" || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z$/.test(v)) return false;
  const date = new Date(v);
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 19) === v.slice(0, 19);
}
function canonicalInstant(v: string): string {
  const fraction = (v.split(".")[1]?.slice(0, -1) ?? "").replace(/0+$/, "");
  return v.slice(0, 19) + (fraction ? "." + fraction.padEnd(fraction.length <= 3 ? 3 : 6, "0") : "") + "Z";
}
function micros(v: string): bigint {
  const fraction = (v.split(".")[1]?.slice(0, -1) ?? "").padEnd(6, "0");
  return BigInt(Date.parse(v.slice(0, 19) + "Z")) * 1000n + BigInt(fraction);
}
export function sameOpportunityValues(a: unknown, b: unknown): boolean {
  if (!validOpportunityValues(a) || !validOpportunityValues(b)) return false;
  const normalize = (v: OpportunityValues) => ({ ...v, occurredAt: canonicalInstant(v.occurredAt), nextCheckAt: canonicalInstant(v.nextCheckAt) });
  return sameValues(normalize(a), normalize(b));
}
function summary(v: unknown): string {
  if (typeof v !== "string" || !v.isWellFormed() || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/u.test(v)) throw Error("请核对进展摘要。");
  const normalized = v.replace(/\r\n?/g, "\n").normalize("NFC").replace(/^[\p{Z}\u0009-\u000d\u001c-\u001f]+|[\p{Z}\u0009-\u000d\u001c-\u001f]+$/gu, "");
  if ([...normalized].length < 1 || [...normalized].length > 2000) throw Error("请填写不超过 2000 字的进展摘要。");
  return normalized;
}
/** Static validation is also used on historical receipts; it deliberately does not use the current clock. */
export function validOpportunityValues(v: unknown): v is OpportunityValues {
  try {
    return isObject(v) && exactKeys(v, fields) && progressChoices.some(([code]) => code === v.progressTypeCode)
      && summary(v.progressSummary) === v.progressSummary && instant(v.occurredAt) && instant(v.nextCheckAt);
  } catch { return false; }
}
export function opportunityCandidate(v: unknown, now: Date = new Date()): S["SaveOpportunityProgressDraftV1"] {
  if (!isObject(v) || !exactKeys(v, fields)) throw Error("请核对本次有效进展内容。");
  const values = { ...v, progressSummary: summary(v.progressSummary) };
  if (!validOpportunityValues(values)) throw Error("请选择有效进展并填写下一次跟进时间。");
  if (!Number.isFinite(now.getTime()) || micros(values.occurredAt) > BigInt(now.getTime()) * 1000n || micros(values.nextCheckAt) <= BigInt(now.getTime()) * 1000n)
    throw Error("本次进展不能发生在未来，下一次跟进时间须晚于现在。");
  return { actionCode: "RECORD_OPPORTUNITY_PROGRESS", schemaVersion: 1, values: { ...values, occurredAt: canonicalInstant(values.occurredAt), nextCheckAt: canonicalInstant(values.nextCheckAt) } };
}
export function validOpportunityDraft(v: unknown): v is S["OpportunityProgressDraftProjectionV1"] {
  return isObject(v) && exactKeys(v, ["draftId", "draftRevision", "actionCode", "schemaVersion", "values", "digest", "updatedAt", "editable"])
    && uuid(v.draftId) && revision(v.draftRevision) && v.actionCode === "RECORD_OPPORTUNITY_PROGRESS" && v.schemaVersion === 1
    && validOpportunityValues(v.values) && hash(v.digest) && instant(v.updatedAt) && typeof v.editable === "boolean";
}
export function validOpportunityConfirmation(v: unknown): v is S["RecordOpportunityProgressV1"] {
  if (!isObject(v) || !exactKeys(v, [...fields, "draftId", "expectedDraftRevision", "draftDigest"])) return false;
  const { draftId, expectedDraftRevision, draftDigest, ...values } = v;
  return uuid(draftId) && revision(expectedDraftRevision) && hash(draftDigest) && validOpportunityValues(values);
}
export function opportunityConfirmation(draft: unknown, input: unknown, now: Date = new Date()): S["RecordOpportunityProgressV1"] {
  if (!validOpportunityDraft(draft) || !draft.editable) throw Error("草稿已变化，请刷新后重新保存。");
  const { values } = opportunityCandidate(input, now);
  if (!sameOpportunityValues(draft.values, values)) throw Error("内容已修改，请先保存草稿后再确认。");
  return { ...draft.values, draftId: draft.draftId, expectedDraftRevision: draft.draftRevision, draftDigest: draft.digest };
}
