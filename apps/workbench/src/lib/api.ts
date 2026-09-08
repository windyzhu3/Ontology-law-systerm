import createClient from "openapi-fetch";

import type { paths, components } from "../generated/api/schema";

export const apiClient = createClient<paths>();
export interface WorkbenchSession {
  readonly sessionKey: string;
  readonly accessToken: string;
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
) {
  const client = createClient<paths>({
    baseUrl,
    ...(fetcher ? { fetch: fetcher } : {}),
  });
  const auth = (s: WorkbenchSession) => ({
    Authorization: `Bearer ${s.accessToken}`,
  });
  return {
    async current(
      session: WorkbenchSession,
      tag: string | null,
      signal: AbortSignal,
    ) {
      return unwrap(
        await client.GET("/api/v1/workcards/current", {
          headers: {
            ...auth(session),
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
      const headers = {
        ...auth(session),
        ...original.headers,
        "Idempotency-Key": original.key,
      };
      const params = {
        path: { taskId: original.taskId },
        header: { "Idempotency-Key": original.key },
      };
      if (original.kind === "draft")
        return unwrap(
          await client.PUT("/api/v1/tasks/{taskId}/draft", {
            params,
            headers,
            body: original.body,
            signal,
          }),
        );
      return unwrap(
        await client.POST(commandPaths[original.action], {
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
        }),
      );
    },
    async receipt(session: WorkbenchSession, key: string, signal: AbortSignal) {
      return unwrap(
        await client.GET("/api/v1/commands/{commandId}/receipt", {
          params: { path: { commandId: key } },
          headers: auth(session),
          cache: "no-store",
          signal,
        }),
      );
    },
  };
}
export type WorkbenchApi = ReturnType<typeof createWorkbenchApi>;
