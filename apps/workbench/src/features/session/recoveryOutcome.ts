import type { RecoveryMarker } from "./recoveryMarker";
import { uuidPattern, publicCommandFacts } from "./recoveryMarker";
import { isObject } from "../workcard/contract";
const policy: Record<string, readonly [number, string, string | null]> = {
  VALIDATION_FAILED: [400, "SAME_KEY_AFTER_FIX", null],
  IDEMPOTENCY_KEY_REQUIRED: [400, "SAME_KEY_AFTER_FIX", null],
  IDEMPOTENCY_KEY_INVALID: [400, "SAME_KEY_AFTER_FIX", null],
  DRAFT_PRECONDITION_REQUIRED: [428, "SAME_KEY_AFTER_FIX", "DRAFT"],
  TASK_PRECONDITION_REQUIRED: [428, "SAME_KEY_AFTER_FIX", "TASK"],
  IDENTITY_PRECONDITION_REQUIRED: [428, "SAME_KEY_AFTER_FIX", "IDENTITY"],
  STALE_TASK: [412, "NEW_KEY_AFTER_REFRESH", "TASK"],
  STALE_DRAFT: [412, "NEW_KEY_AFTER_REFRESH", "DRAFT"],
  STALE_SUBJECT: [412, "NEW_KEY_AFTER_REFRESH", "SUBJECT"],
  STALE_IDENTITY: [412, "NEW_KEY_AFTER_REFRESH", "IDENTITY"],
  DRAFT_DIGEST_MISMATCH: [409, "NEW_KEY_AFTER_REFRESH", "DRAFT"],
  TASK_NOT_OPEN: [409, "NO", "TASK"],
  TASK_ALREADY_COMPLETED: [409, "NO", "TASK"],
  INGRESS_COMPLETION_ALREADY_RECORDED: [409, "NO", "SUBJECT"],
  SUPERVISOR_UNRESOLVED: [422, "NEW_KEY_AFTER_ADMIN_FIX", null],
  SOURCE_INTAKE_OWNER_UNRESOLVED: [422, "NEW_KEY_AFTER_ADMIN_FIX", null],
  IDENTITY_BINDING_CONFLICT: [409, "NO", null],
  IDENTITY_STATE_CONFLICT: [409, "NEW_KEY_AFTER_REFRESH", null],
  IDENTITY_SELF_LOCKOUT: [409, "NO", null],
  IDENTITY_LAST_ADMIN: [409, "NO", null],
  IDENTITY_ORGANIZATION_DEPENDENCY: [409, "NEW_KEY_AFTER_ADMIN_FIX", null],
  IDENTITY_RESPONSIBILITY_DEPENDENCY: [409, "NEW_KEY_AFTER_ADMIN_FIX", null],
};
export function allowedCommandError(
  commandType: string,
  code: string,
): boolean {
  if (!Object.hasOwn(publicCommandFacts, commandType)) return false;
  if (
    [
      "VALIDATION_FAILED",
      "IDEMPOTENCY_KEY_REQUIRED",
      "IDEMPOTENCY_KEY_INVALID",
      "UNAUTHENTICATED",
      "NOT_AUTHORIZED",
      "APPOINTMENT_INACTIVE",
      "COMMAND_PAYLOAD_CONFLICT",
      "RATE_LIMITED",
      "INTERNAL_ERROR",
      "SERVICE_UNAVAILABLE",
    ].includes(code)
  )
    return true;
  const identity = [
    "IDENTITY_PRINCIPAL",
    "ORGANIZATION_UNIT",
    "APPOINTMENT",
    "AUTHORITY_GRANT",
  ].includes(
    publicCommandFacts[commandType as keyof typeof publicCommandFacts],
  );
  if (identity)
    return (
      code === "NOT_FOUND" ||
      [
        "IDENTITY_BINDING_CONFLICT",
        "IDENTITY_STATE_CONFLICT",
        "IDENTITY_SELF_LOCKOUT",
        "IDENTITY_LAST_ADMIN",
        "IDENTITY_ORGANIZATION_DEPENDENCY",
        "IDENTITY_RESPONSIBILITY_DEPENDENCY",
      ].includes(code) ||
      (!commandType.startsWith("CREATE_") &&
        ["STALE_IDENTITY", "IDENTITY_PRECONDITION_REQUIRED"].includes(code))
    );
  if (code === "SUPERVISOR_UNRESOLVED")
    return [
      "CAPTURE_LEAD",
      "RESOLVE_DUPLICATE_LEAD",
      "COMPLETE_LEAD_INGRESS",
      "RECORD_CONTACT_RESULT",
    ].includes(commandType);
  if (commandType === "CAPTURE_LEAD") return false;
  if (
    [
      "NOT_FOUND",
      "TASK_NOT_OPEN",
      "TASK_ALREADY_COMPLETED",
      "DRAFT_DIGEST_MISMATCH",
      "STALE_TASK",
      "STALE_DRAFT",
    ].includes(code)
  )
    return true;
  if (commandType === "SAVE_ACTION_DRAFT")
    return code === "DRAFT_PRECONDITION_REQUIRED";
  return (
    ["STALE_SUBJECT", "TASK_PRECONDITION_REQUIRED"].includes(code) ||
    (code === "SOURCE_INTAKE_OWNER_UNRESOLVED" &&
      commandType === "RECORD_ROUTING_DISPOSITION") ||
    (code === "INGRESS_COMPLETION_ALREADY_RECORDED" &&
      commandType === "COMPLETE_LEAD_INGRESS")
  );
}
export function provenWriteOutcome(
  value: unknown,
  status: number,
  marker: RecoveryMarker,
): boolean {
  if (
    !isObject(value) ||
    typeof value.code !== "string" ||
    !Object.hasOwn(policy, value.code) ||
    !allowedCommandError(marker.commandType, value.code)
  )
    return false;
  const [expected, retry, kind] = policy[value.code];
  if (
    status !== expected ||
    value.status !== status ||
    value.retryPolicy !== retry
  )
    return false;
  const fields = [
    "type",
    "title",
    "status",
    "code",
    "detail",
    "instance",
    "retryPolicy",
    "fieldErrors",
    "currentETag",
    "receiptRef",
  ];
  if (Object.keys(value).some((k) => !fields.includes(k))) return false;
  const safe = (v: unknown, max: number) =>
    typeof v === "string" &&
    v.length > 0 &&
    v.length <= max &&
    !/[\u0000-\u001f\u007f]/.test(v);
  if (
    !safe(value.title, 200) ||
    !safe(value.detail, 500) ||
    !safe(value.type, 2048) ||
    !/^[a-z][a-z\d+.-]*:/i.test(String(value.type)) ||
    !safe(value.instance, 2048) ||
    !/^\/(?!\/)[^?#]*$/.test(String(value.instance))
  )
    return false;
  const identity = [
    "IDENTITY_PRINCIPAL",
    "ORGANIZATION_UNIT",
    "APPOINTMENT",
    "AUTHORITY_GRANT",
  ].includes(
    publicCommandFacts[marker.commandType as keyof typeof publicCommandFacts],
  );
  const identityError =
    value.code.startsWith("IDENTITY_") || value.code === "STALE_IDENTITY";
  if (
    identityError !== identity &&
    ![
      "VALIDATION_FAILED",
      "IDEMPOTENCY_KEY_REQUIRED",
      "IDEMPOTENCY_KEY_INVALID",
    ].includes(value.code)
  )
    return false;
  if (value.code === "VALIDATION_FAILED") {
    if (
      !Array.isArray(value.fieldErrors) ||
      !value.fieldErrors.length ||
      value.fieldErrors.length > 64 ||
      !value.fieldErrors.every(
        (f) =>
          isObject(f) &&
          Object.keys(f).sort().join() === "code,detail,pointer" &&
          typeof f.pointer === "string" &&
          /^(?:\/(?:[^~]|~[01])*)*$/.test(f.pointer) &&
          [
            "REQUIRED",
            "INVALID_FORMAT",
            "OUT_OF_RANGE",
            "NOT_ALLOWED",
            "CONDITION_FAILED",
          ].includes(String(f.code)) &&
          safe(f.detail, 200),
      )
    )
      return false;
  } else if (value.fieldErrors !== undefined) return false;
  if (kind) {
    const e = value.currentETag;
    if (
      e !== undefined &&
      (!isObject(e) ||
        Object.keys(e).sort().join() !== "resourceKind,value" ||
        e.resourceKind !== kind ||
        !new RegExp('^"' + kind.toLowerCase() + '\\.[A-Za-z0-9_-]{43}"$').test(
          String(e.value),
        ))
    )
      return false;
    if (status !== 428 && e === undefined) return false;
  } else if (value.currentETag !== undefined) return false;
  if (value.receiptRef !== undefined) {
    const r = value.receiptRef;
    if (
      !isObject(r) ||
      Object.keys(r).sort().join() !== "commandId,href" ||
      r.commandId !== marker.commandId ||
      !uuidPattern.test(String(r.commandId)) ||
      r.href !== `/api/v1/commands/${marker.commandId}/receipt`
    )
      return false;
  }
  return true;
}
