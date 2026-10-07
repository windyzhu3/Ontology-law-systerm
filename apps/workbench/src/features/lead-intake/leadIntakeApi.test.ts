import { beforeEach, expect, it } from "vitest";
import { testSession, receipt, taskId, envelope } from "../../test/fixtures";
import { RecoveryStore, markerKey } from "../session/recoveryMarker";
import { createLeadIntakeApi, type LeadCaptureWrite } from "./leadIntakeApi";

const original: LeadCaptureWrite = { key: taskId, body: {
  sourceChannelCode: "MANUAL", sourceAccountCode: "sales_intake", sourceRecordKey: "source-001",
  capturedAt: "2026-09-13T08:00:00.000000Z", serviceCategoryCode: "GENERAL_INTAKE", jurisdictionCode: "CN", urgencyCode: "NORMAL", legalNeedSummary: "确认委托需求",
} };
const success = () => ({ ...receipt(taskId), resultFact: { factType: "LEAD", factRef: "opaque-lead", revision: 0 } });
const response = (data: unknown, status = 201) => new Response(JSON.stringify(data), { status, headers: {
  "Content-Type": "application/json", "Cache-Control": "no-store", Location: `/api/v1/commands/${taskId}/receipt`,
} });
const intakeSource={sourceAccountCode:"helong",displayName:"何龙",sourceChannelCode:"MANUAL",serviceCategoryCode:"GENERAL_INTAKE",jurisdictionCode:"CN",urgencyCode:"NORMAL"};
it("returns the bound source mode and accepts an empty bound catalog",async()=>{
  for(const sources of [[intakeSource],[]]) {
    const api=createLeadIntakeApi(new RecoveryStore(sessionStorage),async()=>response({sources,sourceSelection:"BOUND_TO_PRINCIPAL"},200),"https://law.test");
    expect(await api.sources(testSession(),new AbortController().signal)).toEqual({sources,sourceSelection:"BOUND_TO_PRINCIPAL"});
  }
});
it("preserves legacy selectable catalogs but rejects multiple bound sources and unknown modes",async()=>{
  const sources=[intakeSource,{...intakeSource,sourceAccountCode:"wanhefeng",displayName:"万和峰"}];
  const legacy=createLeadIntakeApi(new RecoveryStore(sessionStorage),async()=>response({sources},200),"https://law.test");
  expect(await legacy.sources(testSession(),new AbortController().signal)).toEqual({sources,sourceSelection:"SELECTABLE"});
  for(const body of [{sources,sourceSelection:"BOUND_TO_PRINCIPAL"},{sources,sourceSelection:"AUTO"},{sources,sourceSelection:null}]) {
    const api=createLeadIntakeApi(new RecoveryStore(sessionStorage),async()=>response(body,200),"https://law.test");
    await expect(api.sources(testSession(),new AbortController().signal)).rejects.toThrow();
  }
});
beforeEach(() => sessionStorage.clear());

it("submits the original capture through authenticated transport and clears only its proven receipt", async () => {
  const requests: Request[] = [];
  const api = createLeadIntakeApi(new RecoveryStore(sessionStorage), async request => { requests.push(request); return response(success()); }, "https://law.test");
  const result = await api.capture(testSession(), original, new AbortController().signal);
  expect(result.data).toEqual(success());
  expect(requests).toHaveLength(1);
  expect(requests[0].url).toBe("https://law.test/api/v1/leads");
  expect(requests[0].headers.get("Idempotency-Key")).toBe(taskId);
  expect(requests[0].headers.get("Authorization")).toBe("Bearer test-only");
  expect(await requests[0].json()).toEqual(original.body);
  expect(sessionStorage.getItem(markerKey)).toBeNull();
});
it("retains the unresolved command after a network failure and blocks another row", async () => {
  let sends = 0;
  const store = new RecoveryStore(sessionStorage);
  const api = createLeadIntakeApi(store, async () => { sends++; throw new Error("network"); }, "https://law.test");
  await expect(api.capture(testSession(), original, new AbortController().signal)).rejects.toThrow();
  expect(store.read()?.commandId).toBe(taskId);
  await expect(api.capture(testSession(), { ...original, key: "019c7000-0000-7000-8000-000000000009" }, new AbortController().signal)).rejects.toThrow();
  expect(sends).toBe(1);
  expect(sessionStorage.getItem(markerKey)).not.toContain("确认委托需求");
});
it("recovers an exact capture receipt without sending another capture", async () => {
  const store = new RecoveryStore(sessionStorage);
  store.reserveWrite(taskId, "CAPTURE_LEAD", testSession().actorScopeKey, original);
  const requests: Request[] = [];
  const api = createLeadIntakeApi(store, async request => { requests.push(request); return response(success(), 200); }, "https://law.test");
  const result = await api.receipt(testSession(), taskId, new AbortController().signal);
  expect(result.data).toEqual(success());
  expect(requests.map(request => [request.method, new URL(request.url).pathname])).toEqual([["GET", `/api/v1/commands/${taskId}/receipt`]]);
  expect(store.read()).toBeNull();
});
it("does not clear recovery on an unrelated or malformed success", async () => {
  const store = new RecoveryStore(sessionStorage);
  const api = createLeadIntakeApi(store, async () => response(receipt(taskId)), "https://law.test");
  await expect(api.capture(testSession(), original, new AbortController().signal)).rejects.toThrow();
  expect(store.read()?.commandId).toBe(taskId);
});
it("rejects a changed session before transmitting the lead", async () => {
  let sends = 0;
  const api = createLeadIntakeApi(new RecoveryStore(sessionStorage), async () => { sends++; return response(success()); }, "https://law.test");
  await expect(api.capture({ ...testSession(), isCurrent: () => false }, original, new AbortController().signal)).rejects.toThrow();
  expect(sends).toBe(0);
});
it("leaves a missing receipt unresolved instead of treating it as permission to retry", async () => {
  const store = new RecoveryStore(sessionStorage);
  store.reserveWrite(taskId, "CAPTURE_LEAD", testSession().actorScopeKey, original);
  const api = createLeadIntakeApi(store, async () => new Response(null, { status: 404 }), "https://law.test");
  await expect(api.receipt(testSession(), taskId, new AbortController().signal)).rejects.toThrow();
  expect(store.read()?.commandId).toBe(taskId);
});
it("does not recover a capture in another actor scope", async () => {
  const store = new RecoveryStore(sessionStorage);
  store.reserveWrite(taskId, "CAPTURE_LEAD", testSession().actorScopeKey, original);
  let sends = 0;
  const api = createLeadIntakeApi(store, async () => { sends++; return response(success(), 200); }, "https://law.test");
  await expect(api.receipt(testSession(2), taskId, new AbortController().signal)).rejects.toThrow();
  expect(sends).toBe(0);
  expect(store.read()?.actorScopeKey).toBe(testSession().actorScopeKey);
});
it("does not accept a rejected payload disguised as a created lead", async () => {
  const store = new RecoveryStore(sessionStorage);
  const api = createLeadIntakeApi(store, async () => response({ commandId: taskId, receiptId: "019c7000-0000-7000-8000-000000000004", completedAt: "2026-09-08T02:10:00Z", outcome: "REJECTED", rejectionCode: "NOT_AUTHORIZED" }), "https://law.test");
  await expect(api.capture(testSession(), original, new AbortController().signal)).rejects.toThrow();
  expect(store.read()?.commandId).toBe(taskId);
});

it("matches follow-up tasks only by stable reference and requires the task projection", async () => {
  const ref = "a".repeat(43), body = envelope(5, true);
  const row = { ...body.nextSummaries[0], subjectTitle: "同名客户", subjectFactRef: ref };
  let data: unknown = { ...body, myTasks: [row, { ...body.nextSummaries[1], subjectTitle: "同名客户", subjectFactRef: "b".repeat(43) }] };
  const api = createLeadIntakeApi(new RecoveryStore(sessionStorage), async () => response(data, 200));
  expect(await api.followup(testSession(), ref, new AbortController().signal)).toEqual([row]);
  expect(await api.followup(testSession(), "c".repeat(43), new AbortController().signal)).toEqual([]);
  data = { ...body, myTasks: [{ ...row, subjectFactRef: undefined }] };
  await expect(api.followup(testSession(), ref, new AbortController().signal)).rejects.toThrow();
  data = body;
  await expect(api.followup(testSession(), ref, new AbortController().signal)).rejects.toThrow();
});
