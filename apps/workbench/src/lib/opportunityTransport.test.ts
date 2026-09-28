import { beforeEach, expect, it } from "vitest";
import { createWorkbenchApi, type OpportunityWrite, type WorkbenchSession } from "./api";
import { markerKey } from "../features/session/recoveryMarker";
import { digest, jsonResponse, receipt, tags, taskId, deferred, problemResponse } from "../test/fixtures";

const session: WorkbenchSession = { identityEpoch: 1, actorScopeKey: `ask1.${"a".repeat(43)}`, selectedAppointmentId: taskId, selectedOnBehalfAppointmentId: null, getValidAccessToken: async () => "token", isCurrent: () => true, invalidate: () => {} };
const values = { progressTypeCode: "PHONE_CONNECTED" as const, progressSummary: "客户确认范围", occurredAt: "2026-09-14T07:00:00Z", nextCheckAt: "2026-09-15T07:00:00Z" };
const draft = { draftId: taskId, draftRevision: 2, actionCode: "RECORD_OPPORTUNITY_PROGRESS" as const, schemaVersion: 1 as const, values, digest, updatedAt: "2026-09-14T08:00:00Z", editable: true };
const original = (): OpportunityWrite => ({ kind: "command", key: taskId, taskId, headers: { "If-Match": tags.taskETag }, body: { ...values, draftId: taskId, expectedDraftRevision: 2, draftDigest: digest } });
const signal = () => new AbortController().signal;
beforeEach(() => sessionStorage.clear());

it("accepts an existing draft update with Java-normalized instants", async () => {
  const api = createWorkbenchApi(async () => jsonResponse({ receipt: receipt(taskId, "ACTION_DRAFT"), draft, preconditions: tags }, 200, tags.draftETag));
  await api.opportunityWrite(session, { kind: "draft", key: taskId, taskId, headers: { "If-Match": tags.draftETag }, body: { actionCode: "RECORD_OPPORTUNITY_PROGRESS", schemaVersion: 1, values: { ...values, occurredAt: values.occurredAt.replace("Z", ".000Z"), nextCheckAt: values.nextCheckAt.replace("Z", ".000000Z") } } }, signal());
  expect(api.recovery.read()).toBeNull();
});

it("posts to the typed opportunity route and clears only a matching progress receipt", async () => {
  const api = createWorkbenchApi(async request => {
    expect(new URL(request.url).pathname).toBe(`/api/v1/tasks/${taskId}/commands/record-opportunity-progress`);
    expect(request.headers.get("If-Match")).toBe(tags.taskETag);
    expect(request.headers.get("Idempotency-Key")).toBe(taskId);
    expect(await request.json()).toEqual(original().body);
    return jsonResponse(receipt(taskId, "OPPORTUNITY_PROGRESS"));
  });
  await api.opportunityWrite(session, original(), signal());
  expect(api.recovery.read()).toBeNull();
});
it("saves using the dedicated draft route and checks the exact draft projection", async () => {
  const api = createWorkbenchApi(async request => {
    expect(request.method).toBe("PUT");
    expect(new URL(request.url).pathname).toBe(`/api/v1/tasks/${taskId}/opportunity-progress-draft`);
    return jsonResponse({ receipt: receipt(taskId, "ACTION_DRAFT"), draft, preconditions: tags }, 201, tags.draftETag!);
  });
  await api.opportunityWrite(session, { kind: "draft", key: taskId, taskId, headers: { "If-None-Match": "*" }, body: { actionCode: "RECORD_OPPORTUNITY_PROGRESS", schemaVersion: 1, values } }, signal());
  expect(api.recovery.read()).toBeNull();
});
it("keeps only a four-field marker after a lost response and recovers through the shared GET", async () => {
  let lose = true;
  const api = createWorkbenchApi(async request => {
    if (lose) throw Error("lost");
    expect(request.method).toBe("GET");
    return jsonResponse(receipt(taskId, "OPPORTUNITY_PROGRESS"));
  });
  await expect(api.opportunityWrite(session, original(), signal())).rejects.toThrow();
  const raw = sessionStorage.getItem(markerKey)!;
  expect(Object.keys(JSON.parse(raw)).sort()).toEqual(["actorScopeKey", "commandId", "commandType", "recordedAt"]);
  expect(raw).not.toContain(values.progressSummary);
  expect(api.recovery.read()?.commandType).toBe("RECORD_OPPORTUNITY_PROGRESS");
  lose = false;
  await api.receipt(session, taskId, signal());
  expect(api.recovery.read()).toBeNull();
});
it("retains an ambiguous result, permits exact retry, and blocks another write", async () => {
  let count = 0;
  const api = createWorkbenchApi(async () => { count++; return jsonResponse(receipt(taskId, "LEAD_CONTACT_RESULT")); });
  const request = original();
  await expect(api.opportunityWrite(session, request, signal())).rejects.toThrow();
  await expect(api.opportunityWrite(session, request, signal())).rejects.toThrow();
  await expect(api.opportunityWrite(session, original(), signal())).rejects.toThrow(/原请求/);
  expect(count).toBe(2);
  expect(api.recovery.read()).not.toBeNull();
});
it("blocks impersonation and invalid preconditions before sending or reserving", async () => {
  let count = 0;
  const api = createWorkbenchApi(async () => { count++; return jsonResponse({}); });
  await expect(api.opportunityWrite({ ...session, selectedOnBehalfAppointmentId: taskId }, original(), signal())).rejects.toThrow();
  await expect(api.opportunityWrite(session, { ...original(), headers: { "If-Match": tags.subjectETag } }, signal())).rejects.toThrow();
  expect(count).toBe(0);
  expect(api.recovery.read()).toBeNull();
});
it.each(["values", "revision", "etag", "extra"])("retains recovery when draft response has inconsistent %s", async field => {
  const projection = { ...draft, values: { ...values } };
  if (field === "values") projection.values.progressSummary = "unconfirmed";
  if (field === "revision") projection.draftRevision++;
  const api = createWorkbenchApi(async () => jsonResponse({ receipt: receipt(taskId, "ACTION_DRAFT"), draft: projection, preconditions: tags, ...(field === "extra" ? { internal: "private" } : {}) }, 201, field === "etag" ? tags.taskETag : tags.draftETag));
  await expect(api.opportunityWrite(session, { kind: "draft", key: taskId, taskId, headers: { "If-None-Match": "*" }, body: { actionCode: "RECORD_OPPORTUNITY_PROGRESS", schemaVersion: 1, values } }, signal())).rejects.toThrow(/回执/);
  expect(api.recovery.read()).not.toBeNull();
});
it("does not clear the original marker when the session changes during a response", async () => {
  const pending = deferred<Response>();
  let current = true;
  const api = createWorkbenchApi(async () => pending.promise);
  const promise = api.opportunityWrite({ ...session, isCurrent: () => current }, original(), signal());
  await new Promise(resolve => setTimeout(resolve, 0));
  current = false;
  pending.resolve(jsonResponse(receipt(taskId, "OPPORTUNITY_PROGRESS")));
  await expect(promise).rejects.toThrow(/会话/);
  expect(api.recovery.read()).not.toBeNull();
});
it("releases the marker for a proven draft-digest conflict", async () => {
  const api = createWorkbenchApi(async () => problemResponse("DRAFT_DIGEST_MISMATCH", 409));
  await expect(api.opportunityWrite(session, original(), signal())).rejects.toMatchObject({ status: 409, provenOutcome: true });
  expect(api.recovery.read()).toBeNull();
});
it("prevents an R1 write while an opportunity response is unresolved", async () => {
  let count = 0;
  const api = createWorkbenchApi(async () => { count++; throw Error("lost"); });
  await expect(api.opportunityWrite(session, original(), signal())).rejects.toThrow();
  await expect(api.write(session, { kind: "draft", key: taskId, taskId, headers: { "If-None-Match": "*" }, body: { actionCode: "RECORD_CONTACT_RESULT", schemaVersion: 1, values: { leadAssignmentId: taskId, leadAssignmentRevision: 0, contactChannelCode: "PHONE", resultCode: "NOT_CONNECTED" } } }, signal())).rejects.toThrow(/原请求/);
  expect(count).toBe(1);
});
