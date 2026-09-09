import type { components } from "../generated/api/schema";
import {
  publicCommandFacts,
  type RecoveryMarker,
} from "../features/session/recoveryMarker";
import { allowedCommandError } from "../features/session/recoveryOutcome";
import { validReceipt } from "../features/workcard/contract";

type S = components["schemas"];

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

export class TransportError extends Error {
  constructor(
    readonly status: number,
    readonly code?: string,
    readonly provenOutcome = false,
    readonly retryPolicy?: string,
    readonly currentETag?: string,
  ) {
    super(safeProblemMessage(status, code));
  }
}

export function safeProblemMessage(status: number, code?: string) {
  if (status === 401) return "登录状态已失效，请重新登录后继续。";
  if (status === 403 || status === 404)
    return "当前内容不可用，请刷新或联系管理员。";
  if (code === "STALE_IDENTITY" || code === "IDENTITY_PRECONDITION_REQUIRED")
    return "身份内容或版本已变化，请刷新后重新核对。";
  if (status === 412 || status === 428 || code === "DRAFT_DIGEST_MISMATCH")
    return "内容或版本已变化，请刷新后重新核对并保存候选。";
  if (status === 422) return "暂时无法确定负责人，请联系管理员处理后刷新。";
  if (status === 409) return "当前责任或请求已发生变化，请刷新并核对处理结果。";
  if (status === 400) return "请求内容未通过校验，请核对必填内容。";
  if (status === 429) return "请求较多，请稍后重试。";
  return "服务暂时不可用，请稍后重试。";
}

type ClientResponse<T> = {
  response: Response;
  data?: T;
  error?: unknown;
};

const problemField = (value: unknown, field: string): string | undefined =>
  typeof value === "object" &&
  value !== null &&
  field in value &&
  typeof (value as Record<string, unknown>)[field] === "string"
    ? String((value as Record<string, unknown>)[field])
    : undefined;

export function createSessionTransport(allowOnBehalf: boolean) {
  const assertCurrent = (session: WorkbenchSession, signal: AbortSignal) => {
    if (!session.isCurrent() || signal.aborted)
      throw new Error("会话已变化，请重新核对。");
  };

  const auth = async (session: WorkbenchSession, signal: AbortSignal) => {
    assertCurrent(session, signal);
    if (!allowOnBehalf && session.selectedOnBehalfAppointmentId !== null)
      throw new Error("身份管理不接受代办身份，请切换为本人任职后重试。");
    const token = await session.getValidAccessToken();
    assertCurrent(session, signal);
    return {
      Authorization: `Bearer ${token}`,
      "X-Appointment-Id": session.selectedAppointmentId,
      ...(allowOnBehalf && session.selectedOnBehalfAppointmentId
        ? {
            "X-On-Behalf-Appointment-Id":
              session.selectedOnBehalfAppointmentId,
          }
        : {}),
    };
  };

  const checked = <T>(
    session: WorkbenchSession,
    signal: AbortSignal,
    result: ClientResponse<T>,
    allowNotModified = false,
  ) => {
    assertCurrent(session, signal);
    if ([401, 403].includes(result.response.status))
      session.invalidate(result.response.status);
    if (
      !result.response.ok &&
      !(allowNotModified && result.response.status === 304)
    ) {
      throw new TransportError(
        result.response.status,
        problemField(result.error, "code"),
        false,
        problemField(result.error, "retryPolicy"),
      );
    }
    return {
      data: result.data,
      status: result.response.status,
      etag: result.response.headers.get("ETag"),
    };
  };

  return { assertCurrent, auth, checked };
}

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
