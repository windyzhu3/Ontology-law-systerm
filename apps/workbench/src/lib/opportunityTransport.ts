import type { Client } from "openapi-fetch";
import type { components, paths } from "../generated/api/schema";
import type { RecoveryStore } from "../features/session/recoveryMarker";
import { provenWriteOutcome } from "../features/session/recoveryOutcome";
import { isObject, etag, validPreconditions } from "../features/workcard/contract";
import { validOpportunityDraft, validOpportunityValues, validOpportunityConfirmation, sameOpportunityValues } from "../features/workcard/opportunityProgress";
import { createSessionTransport, matchesReceipt, TransportError, type WorkbenchSession } from "./sessionTransport";

type S = components["schemas"];
export type OpportunityWrite = { key: string; taskId: string; headers: Record<string, string> } & (
  { kind: "draft"; body: S["SaveOpportunityProgressDraftV1"] } |
  { kind: "command"; body: S["RecordOpportunityProgressV1"] }
);

/** Uses the SPA's shared pending-write store so lead, identity and opportunity writes cannot overlap. */
export function createOpportunityWriter(client: Client<paths>, recovery: RecoveryStore) {
  const { auth, assertCurrent, checked } = createSessionTransport(true);
  return async (session: WorkbenchSession, original: OpportunityWrite, signal: AbortSignal) => {
    if (session.selectedOnBehalfAppointmentId !== null) throw Error("请切换为本人任职后办理商机进展。");
    const headerKeys = Object.keys(original.headers);
    const precondition = original.kind === "command"
      ? headerKeys.length === 1 && etag(original.headers["If-Match"], "task")
      : headerKeys.length === 1 && (original.headers["If-None-Match"] === "*" || etag(original.headers["If-Match"], "draft"));
    const bodyValid = original.kind === "command" ? validOpportunityConfirmation(original.body)
      : Object.keys(original.body).sort().join() === "actionCode,schemaVersion,values" && original.body.actionCode === "RECORD_OPPORTUNITY_PROGRESS" && original.body.schemaVersion === 1 && validOpportunityValues(original.body.values);
    if (!precondition || !bodyValid) throw Error("原请求内容或前置条件不可用。");
    const headers = { ...(await auth(session, signal)), ...original.headers, "Idempotency-Key": original.key };
    const marker = recovery.reserveWrite(original.key, original.kind === "draft" ? "SAVE_ACTION_DRAFT" : "RECORD_OPPORTUNITY_PROGRESS", session.actorScopeKey, original);
    const params = { path: { taskId: original.taskId }, header: { "Idempotency-Key": original.key } };
    const r = original.kind === "draft"
      ? await client.PUT("/api/v1/tasks/{taskId}/opportunity-progress-draft", { params, headers, body: original.body, signal })
      : await client.POST("/api/v1/tasks/{taskId}/commands/record-opportunity-progress", { params: { ...params, header: { ...params.header, "If-Match": original.headers["If-Match"] } }, headers, body: original.body, signal });
    assertCurrent(session, signal);
    if (!r.response.ok && provenWriteOutcome(r.error, r.response.status, marker)) {
      recovery.clear(marker);
      throw new TransportError(r.response.status, (r.error as { code: string }).code, true);
    }
    const result = checked(session, signal, r as { response: Response; data?: S["OpportunityProgressDraftWriteResultV1"] | S["OpportunityProgressCommandReceiptV1"]; error?: unknown });
    const data: unknown = result.data;
    const terminal = original.kind === "draft" && isObject(data) ? data.receipt : data;
    let valid = matchesReceipt(terminal, marker) && terminal.outcome !== "REJECTED";
    if (original.kind === "draft") valid = valid && [200, 201].includes(r.response.status) && isObject(data)
      && Object.keys(data).sort().join() === "draft,preconditions,receipt" && validOpportunityDraft(data.draft)
      && validPreconditions(data.preconditions) && etag(result.etag, "draft") && data.preconditions.draftETag === result.etag
      && sameOpportunityValues(data.draft.values, original.body.values) && matchesReceipt(terminal, marker)
      && terminal.outcome !== "REJECTED" && "revision" in terminal.resultFact && terminal.resultFact.revision === data.draft.draftRevision;
    else valid = valid && r.response.status === 200;
    if (!valid) throw Error("尚未确认处理结果，请查询原回执或使用原请求重试。");
    recovery.clear(marker);
    return result;
  };
}
