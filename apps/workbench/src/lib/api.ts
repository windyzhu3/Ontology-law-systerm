import createClient from "openapi-fetch";

import type { paths, components } from "../generated/api/schema";
import {
  RecoveryStore,
  type RecoveryMarker,
  publicCommandFacts,
} from "../features/session/recoveryMarker";
import {
  isObject,
  validReceipt,
  validDraft,
  validPreconditions,
  sameValues,
  etag,
} from "../features/workcard/contract";
import {
  provenWriteOutcome,
  allowedCommandError,
} from "../features/session/recoveryOutcome";

export const apiClient = createClient<paths>();
export interface WorkbenchSession {
  readonly identityEpoch: number;
  readonly actorScopeKey: string;
  readonly selectedAppointmentId: string;
  readonly selectedOnBehalfAppointmentId: string | null;
  getValidAccessToken(): Promise<string>;
  isCurrent(): boolean;
  invalidate(status: number): void;
  readonly displayName?: string;
}
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
export class TransportError extends Error {
  constructor(
    readonly status: number,
    readonly code?: string,
    readonly provenOutcome = false,
  ) {
    super(safeProblemMessage(status, code));
  }
}
export function safeProblemMessage(status: number, code?: string) {
  if (status === 401) return "登录状态已失效，请重新登录后继续。";
  if (status === 403 || status === 404)
    return "当前内容不可用，请刷新或联系管理员。";
  if (status === 412 || status === 428 || code === "DRAFT_DIGEST_MISMATCH")
    return "内容或版本已变化，请刷新后重新核对并保存候选。";
  if (status === 422) return "暂时无法确定负责人，请联系管理员处理后刷新。";
  if (status === 409) return "当前责任或请求已发生变化，请刷新并核对处理结果。";
  if (status === 400) return "请求内容未通过校验，请核对必填内容。";
  if (status === 429) return "请求较多，请稍后重试。";
  return "服务暂时不可用，请稍后重试。";
}
function unwrap<T>(r: { response: Response; data?: T; error?: unknown }) {
  if (!r.response.ok && r.response.status !== 304) {
    const e = r.error;
    throw new TransportError(
      r.response.status,
      typeof e === "object" &&
      e !== null &&
      "code" in e &&
      typeof e.code === "string"
        ? e.code
        : undefined,
    );
  }
  return {
    data: r.data,
    status: r.response.status,
    etag: r.response.headers.get("ETag"),
  };
}
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
  const assertCurrent = (s: WorkbenchSession, signal: AbortSignal) => {
    if (!s.isCurrent() || signal.aborted)
      throw new Error("会话已变化，请重新核对。");
  };
  const auth = async (s: WorkbenchSession, signal: AbortSignal) => {
    assertCurrent(s, signal);
    const token = await s.getValidAccessToken();
    assertCurrent(s, signal);
    return {
      Authorization: `Bearer ${token}`,
      "X-Appointment-Id": s.selectedAppointmentId,
      ...(s.selectedOnBehalfAppointmentId
        ? { "X-On-Behalf-Appointment-Id": s.selectedOnBehalfAppointmentId }
        : {}),
    };
  };
  const checked = <T>(
    s: WorkbenchSession,
    signal: AbortSignal,
    r: { response: Response; data?: T; error?: unknown },
  ) => {
    assertCurrent(s, signal);
    if ([401, 403].includes(r.response.status)) s.invalidate(r.response.status);
    return unwrap(r);
  };
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
export function matchesReceipt(
  value: unknown,
  marker: RecoveryMarker,
): value is S["CommandReceipt"] {
  if (
    !Object.hasOwn(publicCommandFacts, marker.commandType) ||
    !validReceipt(value, marker.commandId)
  )
    return false;
  const fact =
    publicCommandFacts[marker.commandType as keyof typeof publicCommandFacts];
  if (value.outcome !== "REJECTED") return value.resultFact.factType === fact;
  return allowedCommandError(marker.commandType, value.rejectionCode);
}
