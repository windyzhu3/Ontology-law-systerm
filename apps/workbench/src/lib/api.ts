import createClient from "openapi-fetch";

import type { paths, components } from "../generated/api/schema";
import { RecoveryStore } from "../features/session/recoveryMarker";
import {
  isObject,
  validDraft,
  validPreconditions,
  sameValues,
  etag,
} from "../features/workcard/contract";
import { provenWriteOutcome } from "../features/session/recoveryOutcome";
import {
  createSessionTransport,
  matchesReceipt,
  TransportError,
  type WorkbenchSession,
} from "./sessionTransport";

export {
  matchesReceipt,
  safeProblemMessage,
  TransportError,
  type WorkbenchSession,
} from "./sessionTransport";

export const apiClient = createClient<paths>();
type S = components["schemas"];
export const commandPaths = {
  RESOLVE_DUPLICATE_LEAD:
    "/api/v1/tasks/{taskId}/commands/resolve-duplicate-lead",
  COMPLETE_LEAD_INGRESS:
    "/api/v1/tasks/{taskId}/commands/complete-lead-ingress",
  ASSIGN_LEAD: "/api/v1/tasks/{taskId}/commands/assign-lead",
  RECORD_ROUTING_DISPOSITION:
    "/api/v1/tasks/{taskId}/commands/record-routing-disposition",
  ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST:
    "/api/v1/tasks/{taskId}/commands/acknowledge-source-intake-stop-request",
  RECORD_CONTACT_RESULT:
    "/api/v1/tasks/{taskId}/commands/record-contact-result",
  REVIEW_LEAD_VALIDITY: "/api/v1/tasks/{taskId}/commands/review-lead-validity",
} as const satisfies Record<S["ActionCode"], keyof paths>;
type CommandBody =
  | S["ResolveDuplicateLeadV1"]
  | S["CompleteLeadIngressV1"]
  | S["AssignLeadV1"]
  | S["RecordRoutingDispositionV1"]
  | S["AcknowledgeSourceIntakeStopRequestV1"]
  | S["RecordContactResultV1"]
  | S["ReviewLeadValidityV1"];
export type OriginalWrite = {
  key: string;
  taskId: string;
  headers: Record<string, string>;
} & (
  | { kind: "draft"; body: S["SaveActionDraftV1"] }
  | { kind: "command"; action: S["ActionCode"]; body: CommandBody }
);
/** Public-only adapter. Credentials live in the injected session, never browser storage. */
export function createWorkbenchApi(
  fetcher?: (request: Request) => Promise<Response>,
  baseUrl = window.location.origin,
  recovery = new RecoveryStore(window.sessionStorage),
) {
  const client = createClient<paths>({
    baseUrl,
    ...(fetcher ? { fetch: fetcher } : {}),
  });
  const { assertCurrent, auth, checked } = createSessionTransport(true);
  return {
    recovery,
    async current(
      session: WorkbenchSession,
      tag: string | null,
      signal: AbortSignal,
    ) {
      return checked(
        session,
        signal,
        await client.GET("/api/v1/workcards/current", {
          headers: {
            ...(await auth(session, signal)),
            ...(tag ? { "If-None-Match": tag } : {}),
          },
          signal,
          cache: "no-store",
        }),
        true,
      );
    },
    async write(
      session: WorkbenchSession,
      original: OriginalWrite,
      signal: AbortSignal,
    ) {
      if (
        Object.keys(original.headers).some(
          (k) => !["If-Match", "If-None-Match"].includes(k),
        )
      )
        throw new Error("原请求前置条件不可用。");
      const headers = {
        ...(await auth(session, signal)),
        ...original.headers,
        "Idempotency-Key": original.key,
      };
      const marker = recovery.reserveWrite(
        original.key,
        original.kind === "draft" ? "SAVE_ACTION_DRAFT" : original.action,
        session.actorScopeKey,
        original,
      );
      const params = {
        path: { taskId: original.taskId },
        header: { "Idempotency-Key": original.key },
      };
      const r =
        original.kind === "draft"
          ? await client.PUT("/api/v1/tasks/{taskId}/draft", {
              params,
              headers,
              body: original.body,
              signal,
            })
          : await client.POST(commandPaths[original.action], {
              params: {
                ...params,
                header: {
                  ...params.header,
                  "If-Match": original.headers["If-Match"],
                },
              },
              headers,
              body: original.body,
              signal,
            });
      assertCurrent(session, signal);
      if (
        !r.response.ok &&
        provenWriteOutcome(r.error, r.response.status, marker)
      ) {
        recovery.clear(marker);
        throw new TransportError(
          r.response.status,
          (r.error as { code: string }).code,
          true,
        );
      }
      const data: unknown = r.data;
      const terminal =
        original.kind === "draft" && isObject(data) ? data.receipt : data;
      if (r.response.ok && matchesReceipt(terminal, marker)) {
        if (
          original.kind !== "draft" ||
          (isObject(data) &&
            Object.keys(data).sort().join() === "draft,preconditions,receipt" &&
            validDraft(data.draft, original.body.actionCode) &&
            validPreconditions(data.preconditions) &&
            etag(r.response.headers.get("ETag"), "draft") &&
            data.preconditions.draftETag === r.response.headers.get("ETag") &&
            sameValues(data.draft.values, original.body.values) &&
            terminal.outcome !== "REJECTED" &&
            "revision" in terminal.resultFact &&
            terminal.resultFact.revision === data.draft.draftRevision)
        )
          recovery.clear(marker);
      }
      return checked(
        session,
        signal,
        r as {
          response: Response;
          data?: S["ActionDraftWriteResult"] | S["CommandReceipt"];
          error?: unknown;
        },
      );
    },
    async receipt(session: WorkbenchSession, key: string, signal: AbortSignal) {
      const marker = recovery.read();
      if (
        !marker ||
        marker.commandId !== key ||
        marker.actorScopeKey !== session.actorScopeKey
      )
        throw new Error("结果尚未确认，不能自动重发。");
      const r = await client.GET("/api/v1/commands/{commandId}/receipt", {
        params: { path: { commandId: key } },
        headers: await auth(session, signal),
        cache: "no-store",
        signal,
      });
      assertCurrent(session, signal);
      if (r.response.status === 200 && matchesReceipt(r.data, marker))
        recovery.clear(marker);
      return checked(session, signal, r);
    },
  };
}
export type WorkbenchApi = ReturnType<typeof createWorkbenchApi>;
