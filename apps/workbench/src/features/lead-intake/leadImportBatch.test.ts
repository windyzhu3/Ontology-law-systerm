import { beforeEach, expect, it } from "vitest";
import { testSession, receipt } from "../../test/fixtures";
import { RecoveryStore, markerKey } from "../session/recoveryMarker";
import { createLeadIntakeApi, type LeadCaptureWrite } from "./leadIntakeApi";
import { LeadImportBatch } from "./leadImportBatch";

const keys = [1, 2, 3].map(n => `019c7000-0000-7000-8000-00000000000${n}`);
const writes = (): LeadCaptureWrite[] => keys.map((key, i) => ({ key, body: {
  sourceChannelCode: "MANUAL", sourceAccountCode: "sales_intake", sourceRecordKey: `source-${i}`,
  capturedAt: "2026-09-14T08:00:00.000000Z", serviceCategoryCode: "GENERAL_INTAKE", jurisdictionCode: "CN", urgencyCode: "NORMAL", legalNeedSummary: "确认委托需求",
} }));
const entries = () => writes().map((write, i) => ({ rowNumber: i + 2, write }));
const success = (key: string, status = 201) => new Response(JSON.stringify({ ...receipt(key), resultFact: { factType: "LEAD", factRef: "opaque-lead", revision: 0 } }), {
  status, headers: { "Content-Type": "application/json", "Cache-Control": "no-store", Location: `/api/v1/commands/${key}/receipt` },
});
beforeEach(() => sessionStorage.clear());
const signal = () => new AbortController().signal;

it("serializes valid rows, skips invalid rows, and never repeats successes", async () => {
  const store = new RecoveryStore(sessionStorage), sent: string[] = [];
  const api = createLeadIntakeApi(store, async request => { const key = request.headers.get("Idempotency-Key")!; sent.push(key); return success(key); }, "https://law.test");
  const batch = new LeadImportBatch(testSession().actorScopeKey, [...entries(), { rowNumber: 5, errors: ["缺少需求描述"] }], api, store);
  await batch.submit(testSession(), signal());
  await batch.submit(testSession(), signal());
  expect(sent).toEqual(keys);
  expect(batch.snapshot().rows.map(row => row.status)).toEqual(["captured", "captured", "captured", "invalid"]);
  expect(batch.snapshot().busy).toBe(false);
  expect(batch.snapshot().rows[0].subjectFactRef).toBe("opaque-lead");
  expect(batch.snapshot().rows[0].receipt).toMatchObject({commandId:keys[0],receiptId:receipt(keys[0]).receiptId,completedAt:receipt(keys[0]).completedAt,outcome:"SUCCEEDED",resultFact:{factRef:"opaque-lead"}});
});

it("stops on an unknown result, checks only its GET receipt, and resumes remaining rows explicitly", async () => {
  const store = new RecoveryStore(sessionStorage), sent: string[] = [];
  const api = createLeadIntakeApi(store, async request => {
    sent.push(`${request.method} ${request.headers.get("Idempotency-Key") ?? "receipt"}`);
    if (request.method === "GET") return success(keys[1], 200);
    const key = request.headers.get("Idempotency-Key")!;
    if (key === keys[1]) throw new Error("network secret body");
    return success(key);
  }, "https://law.test");
  const batch = new LeadImportBatch(testSession().actorScopeKey, entries(), api, store);
  await batch.submit(testSession(), signal());
  expect(batch.snapshot().rows.map(row => row.status)).toEqual(["captured", "unresolved", "pending"]);
  await batch.submit(testSession(), signal());
  expect(sent).toHaveLength(2);
  await batch.recover(testSession(), signal());
  expect(sent).toHaveLength(3);
  expect(batch.snapshot().rows.map(row => row.status)).toEqual(["captured", "captured", "pending"]);
  expect(batch.snapshot().rows[1].subjectFactRef).toBe("opaque-lead");
  expect(batch.snapshot().rows[1].receipt?.commandId).toBe(keys[1]);
  await batch.submit(testSession(), signal());
  expect(sent).toEqual([`POST ${keys[0]}`, `POST ${keys[1]}`, "GET receipt", `POST ${keys[2]}`]);
  expect(sessionStorage.getItem(markerKey)).toBeNull();
  expect(JSON.stringify(batch.snapshot())).not.toContain("secret body");
});

it("keeps a missing receipt unresolved, even when another UI cleared the marker", async () => {
  const store = new RecoveryStore(sessionStorage), sent: Request[] = [];
  const api = createLeadIntakeApi(store, async request => { sent.push(request); if (request.method === "GET") return new Response(null, { status: 404 }); throw new Error("network"); }, "https://law.test");
  const batch = new LeadImportBatch(testSession().actorScopeKey, entries(), api, store);
  await batch.submit(testSession(), signal());
  await batch.recover(testSession(), signal());
  store.abandon(true);
  await batch.recover(testSession(), signal());
  await batch.submit(testSession(), signal());
  expect(sent).toHaveLength(2);
  expect(batch.snapshot().rows[0].status).toBe("unresolved");
});

it("does not submit or disclose a batch under another actor scope", async () => {
  const store = new RecoveryStore(sessionStorage); let sends = 0;
  const api = createLeadIntakeApi(store, async () => { sends++; throw new Error(); }, "https://law.test");
  const batch = new LeadImportBatch(testSession().actorScopeKey, entries(), api, store);
  await expect(batch.submit(testSession(2), signal())).rejects.toThrow("会话");
  await expect(batch.recover(testSession(2), signal())).rejects.toThrow("会话");
  expect(sends).toBe(0);
});

it("blocks a competing submit and snapshots the original body against caller mutation", async () => {
  const store = new RecoveryStore(sessionStorage), input = entries(), bodies: unknown[] = [];
  let release!: () => void;
  const pause = new Promise<void>(resolve => { release = resolve; });
  const api = createLeadIntakeApi(store, async request => { bodies.push(await request.json()); await pause; return success(request.headers.get("Idempotency-Key")!); }, "https://law.test");
  const batch = new LeadImportBatch(testSession().actorScopeKey, input, api, store);
  input[0].write.body.legalNeedSummary = "changed";
  const first = batch.submit(testSession(), signal());
  await batch.submit(testSession(), signal());
  release(); await first;
  expect(bodies).toHaveLength(3);
  expect(bodies[0]).toMatchObject({ legalNeedSummary: "确认委托需求" });
});

it("does not send any batch row while another command is unresolved", async () => {
  const store = new RecoveryStore(sessionStorage); let sends = 0;
  store.reserveWrite(keys[2], "SAVE_ACTION_DRAFT", testSession().actorScopeKey, {});
  const api = createLeadIntakeApi(store, async () => { sends++; throw new Error(); }, "https://law.test");
  const batch = new LeadImportBatch(testSession().actorScopeKey, entries(), api, store);
  await batch.submit(testSession(), signal());
  expect(sends).toBe(0);
  expect(batch.snapshot().rows.every(row => row.status === "pending")).toBe(true);
  expect(batch.snapshot().notice).toContain("未决操作");
});

it("rejects duplicate command keys and duplicate source identities before any capture", () => {
  const store = new RecoveryStore(sessionStorage), api = createLeadIntakeApi(store);
  const input = entries(); input[1].write.key = input[0].write.key;
  expect(() => new LeadImportBatch(testSession().actorScopeKey, input, api, store)).toThrow();
  const duplicates = entries(); duplicates[1].write.body.sourceRecordKey = duplicates[0].write.body.sourceRecordKey;
  expect(() => new LeadImportBatch(testSession().actorScopeKey, duplicates, api, store)).toThrow();
});

it("records a recovered rejection as rejected, never as a captured lead", async () => {
  const store = new RecoveryStore(sessionStorage);
  const api = createLeadIntakeApi(store, async request => {
    if (request.method === "POST") throw new Error("network");
    return new Response(JSON.stringify({ commandId: keys[0], receiptId: keys[2], completedAt: "2026-09-14T08:00:00Z", outcome: "REJECTED", rejectionCode: "NOT_AUTHORIZED" }), { status: 200, headers: { "Content-Type": "application/json", "Cache-Control": "no-store" } });
  }, "https://law.test");
  const batch = new LeadImportBatch(testSession().actorScopeKey, entries(), api, store);
  await batch.submit(testSession(), signal()); await batch.recover(testSession(), signal());
  expect(batch.snapshot().rows[0].status).toBe("rejected");
  expect(batch.snapshot().rows[0].receipt).toMatchObject({commandId:keys[0],receiptId:keys[2],outcome:"REJECTED"});
  expect(batch.snapshot().rows[1].status).toBe("pending");
});

it("preserves an in-flight row after session invalidation and sends no subsequent row", async () => {
  const store = new RecoveryStore(sessionStorage); let current = true, sends = 0;
  const api = createLeadIntakeApi(store, async () => { sends++; current = false; return success(keys[0]); }, "https://law.test");
  const batch = new LeadImportBatch(testSession().actorScopeKey, entries(), api, store);
  await batch.submit({ ...testSession(), isCurrent: () => current }, signal());
  expect(sends).toBe(1);
  expect(batch.snapshot().rows.map(row => row.status)).toEqual(["unresolved", "pending", "pending"]);
  expect(store.read()?.commandId).toBe(keys[0]);
});

it("retains the unknown marker if the request is aborted after transmission", async () => {
  const store = new RecoveryStore(sessionStorage), controller = new AbortController(); let sends = 0;
  const api = createLeadIntakeApi(store, async () => { sends++; controller.abort(); return success(keys[0]); }, "https://law.test");
  const batch = new LeadImportBatch(testSession().actorScopeKey, entries(), api, store);
  await batch.submit(testSession(), controller.signal);
  expect(sends).toBe(1);
  expect(batch.snapshot().rows[0].status).toBe("unresolved");
  expect(store.read()?.commandId).toBe(keys[0]);
  expect(sessionStorage.getItem(markerKey)).not.toContain("确认委托需求");
});

it("does not turn a known successful receipt into an unknown outcome when a view listener fails", async () => {
  const store = new RecoveryStore(sessionStorage); let sends = 0;
  const api = createLeadIntakeApi(store, async request => { sends++; return success(request.headers.get("Idempotency-Key")!); }, "https://law.test");
  const batch = new LeadImportBatch(testSession().actorScopeKey, entries(), api, store);
  batch.subscribe(() => { throw new Error("view"); });
  await batch.submit(testSession(), signal());
  expect(sends).toBe(3);
  expect(batch.snapshot().rows.every(row => row.status === "captured")).toBe(true);
});

it("does not mistake a competing command reserved during token refresh for this row's unknown result", async () => {
  const store = new RecoveryStore(sessionStorage); let sends = 0;
  const api = createLeadIntakeApi(store, async () => { sends++; throw new Error(); }, "https://law.test");
  const batch = new LeadImportBatch(testSession().actorScopeKey, entries(), api, store);
  await batch.submit({ ...testSession(), getValidAccessToken: async () => {
    store.reserveWrite(keys[2], "SAVE_ACTION_DRAFT", testSession().actorScopeKey, {});
    return "token";
  } }, signal());
  expect(sends).toBe(0);
  expect(batch.snapshot().rows[0].status).toBe("pending");
  expect(batch.snapshot().notice).toContain("未决操作");
});
