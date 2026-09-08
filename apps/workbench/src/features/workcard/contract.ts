import type { components } from "../../generated/api/schema";
export type Schema = components["schemas"];
export type Card = Schema["CurrentCard"];
export type Envelope = Schema["CurrentWorkCardEnvelope"];
export type Values = Record<string, unknown>;
export type Action = Schema["ActionCode"];
export const actions = {
  RESOLVE_LEAD_DUPLICATE: "RESOLVE_DUPLICATE_LEAD",
  COMPLETE_LEAD_INGRESS: "COMPLETE_LEAD_INGRESS",
  ASSIGN_LEAD: "ASSIGN_LEAD",
  RESOLVE_LEAD_ROUTING_GAP: "RECORD_ROUTING_DISPOSITION",
  ACK_SOURCE_INTAKE_STOP_REQUEST: "ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST",
  CONTACT_LEAD: "RECORD_CONTACT_RESULT",
  REVIEW_LEAD_VALIDITY: "REVIEW_LEAD_VALIDITY",
} as const;
const editable: Record<Action, string[]> = {
  RESOLVE_DUPLICATE_LEAD: ["decisionCode", "rationaleSummary"],
  COMPLETE_LEAD_INGRESS: ["phone", "email", "sourceCode", "sourceSummary"],
  ASSIGN_LEAD: ["ownerAppointmentId"],
  RECORD_ROUTING_DISPOSITION: ["decisionCode", "rationaleSummary"],
  ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST: ["rationaleSummary"],
  RECORD_CONTACT_RESULT: [
    "contactChannelCode",
    "resultCode",
    "resultSummary",
    "legalNeed",
  ],
  REVIEW_LEAD_VALIDITY: ["decisionCode", "rationaleSummary"],
};
const selectors: Record<Action, string[]> = {
  RESOLVE_DUPLICATE_LEAD: [
    "candidateLeadId",
    "candidateLeadRevision",
    "partyId",
    "partyRevision",
  ],
  COMPLETE_LEAD_INGRESS: [],
  ASSIGN_LEAD: [],
  RECORD_ROUTING_DISPOSITION: [],
  ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST: [
    "causalDecisionId",
    "causalDecisionHash",
  ],
  RECORD_CONTACT_RESULT: [
    "leadAssignmentId",
    "leadAssignmentRevision",
    "evidenceSubmissionId",
  ],
  REVIEW_LEAD_VALIDITY: [
    "triggeringContactResultId",
    "triggeringContactResultHash",
  ],
};
const choices: Record<string, readonly string[]> = {
  RESOLVE_DUPLICATE_LEAD: ["LINK_EXISTING_PARTY", "KEEP_SEPARATE"],
  RECORD_ROUTING_DISPOSITION: [
    "SCHEDULE_ROUTING_REVIEW",
    "RETRY_ASSIGNMENT_NOW",
    "REQUEST_SOURCE_INTAKE_STOP",
  ],
  REVIEW_LEAD_VALIDITY: [
    "CONFIRM_INVALID",
    "CLOSE_UNREACHED",
    "REOPEN_CONTACT",
  ],
  resultCode: ["CONNECTED_VALID", "NOT_CONNECTED", "SUSPECT_INVALID"],
  contactChannelCode: ["PHONE", "EMAIL"],
  sourceCode: ["OWNER_CONFIRMED", "CUSTOMER_PROVIDED"],
};
export const isObject = (v: unknown): v is Values =>
  typeof v === "object" && v !== null && !Array.isArray(v);
const keys = (v: Values, allowed: string[], required = allowed) =>
  Object.keys(v).every((k) => allowed.includes(k)) &&
  required.every((k) => k in v);
export const safeText = (v: unknown, max = 500, empty = false): v is string =>
  typeof v === "string" &&
  [...v.trim()].length >= (empty ? 0 : 1) &&
  [...v].length <= max &&
  !/[\u0000-\u001f\u007f-\u009f]/u.test(v);
const uuid = (v: unknown) =>
  typeof v === "string" &&
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(
    v,
  );
const revision = (v: unknown) => Number.isSafeInteger(v) && Number(v) >= 0;
const hash = (v: unknown) =>
  typeof v === "string" && /^[A-Za-z0-9_-]{43}$/.test(v);
export const etag = (v: unknown, kind: string): v is string =>
  typeof v === "string" &&
  new RegExp(`^"${kind}\\.[A-Za-z0-9_-]{43}"$`).test(v);
const instant = (v: unknown) =>
  typeof v === "string" && /T.+Z$/.test(v) && Number.isFinite(Date.parse(v));
function validValues(action: Action, v: unknown, complete: boolean): boolean {
  if (!isObject(v) || !keys(v, [...editable[action], ...selectors[action]], []))
    return false;
  for (const key of selectors[action]) {
    if (
      key === "evidenceSubmissionId" &&
      (v[key] === undefined || (!complete && v[key] === ""))
    )
      continue;
    if (
      !(key.endsWith("Revision")
        ? revision(v[key])
        : key.endsWith("Hash")
          ? hash(v[key])
          : uuid(v[key]))
    )
      return false;
  }
  for (const [key, value] of Object.entries(v)) {
    if (!editable[action].includes(key)) continue;
    if (key === "ownerAppointmentId") {
      if (!(uuid(value) || (!complete && value === ""))) return false;
    } else if (key === "decisionCode" || key in choices) {
      const allowed = choices[key === "decisionCode" ? action : key];
      if (!allowed?.includes(String(value)) && !(!complete && value === ""))
        return false;
    } else if (
      !safeText(
        value,
        key === "legalNeed"
          ? 2000
          : key === "email"
            ? 320
            : key === "phone"
              ? 16
              : 500,
        !complete,
      )
    )
      return false;
  }
  if (!complete) return true;
  if (action === "ASSIGN_LEAD") return uuid(v.ownerAppointmentId);
  if (action === "COMPLETE_LEAD_INGRESS")
    return (
      !!(v.phone || v.email) &&
      (!v.phone || /^\+[1-9][0-9]{0,14}$/.test(String(v.phone))) &&
      (!v.email || /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(String(v.email))) &&
      choices.sourceCode.includes(String(v.sourceCode)) &&
      safeText(v.sourceSummary)
    );
  if (action === "RECORD_CONTACT_RESULT")
    return (
      choices.contactChannelCode.includes(String(v.contactChannelCode)) &&
      choices.resultCode.includes(String(v.resultCode)) &&
      (v.resultCode === "CONNECTED_VALID"
        ? safeText(v.legalNeed, 2000)
        : v.legalNeed === undefined)
    );
  return (
    safeText(v.rationaleSummary) &&
    (action === "ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST" ||
      choices[action].includes(String(v.decisionCode)))
  );
}
function validFields(action: Action, value: unknown) {
  if (!Array.isArray(value) || value.length < 1 || value.length > 16)
    return false;
  const names = new Set<string>();
  const valid = value.every((f) => {
    if (
      !isObject(f) ||
      !keys(f, [
        "name",
        "label",
        "control",
        "required",
        "readOnly",
        "options",
      ]) ||
      typeof f.name !== "string" ||
      names.has(f.name) ||
      ![
        ...editable[action],
        ...(action === "RECORD_CONTACT_RESULT" ? ["evidenceSubmissionId"] : []),
      ].includes(f.name)
    )
      return false;
    names.add(f.name);
    if (
      !safeText(f.label, 200) ||
      typeof f.readOnly !== "boolean" ||
      typeof f.required !== "boolean" ||
      !Array.isArray(f.options)
    )
      return false;
    const control =
      f.name === "phone"
        ? "TEL"
        : f.name === "email"
          ? "EMAIL"
          : f.name === "evidenceSubmissionId"
            ? "TEXT"
            : f.name === "decisionCode" ||
                f.name === "ownerAppointmentId" ||
                f.name in choices
              ? "SELECT"
              : "TEXTAREA";
    if (f.control !== control) return false;
    if (control !== "SELECT") return f.options.length === 0;
    return (
      f.options.length > 0 &&
      f.options.every(
        (o) =>
          isObject(o) &&
          keys(o, ["value", "label", "disabled"]) &&
          safeText(o.label, 200) &&
          typeof o.disabled === "boolean" &&
          (f.name === "ownerAppointmentId"
            ? uuid(o.value)
            : choices[
                f.name === "decisionCode" ? action : (f.name as string)
              ]?.includes(String(o.value))),
      )
    );
  });
  return (
    valid &&
    editable[action]
      .filter((name) => name !== "legalNeed")
      .every((name) => names.has(name))
  );
}
export function validPreconditions(
  v: unknown,
): v is Schema["PreconditionTokens"] {
  return (
    isObject(v) &&
    keys(v, ["taskETag", "subjectETag", "draftETag"]) &&
    etag(v.taskETag, "task") &&
    etag(v.subjectETag, "subject") &&
    (v.draftETag === null || etag(v.draftETag, "draft"))
  );
}
export function validDraft(
  v: unknown,
  action: Action,
): v is Schema["ActionDraftProjection"] {
  return (
    isObject(v) &&
    keys(v, [
      "draftId",
      "draftRevision",
      "actionCode",
      "schemaVersion",
      "values",
      "digest",
      "updatedAt",
      "editable",
    ]) &&
    uuid(v.draftId) &&
    revision(v.draftRevision) &&
    v.actionCode === action &&
    v.schemaVersion === 1 &&
    hash(v.digest) &&
    instant(v.updatedAt) &&
    typeof v.editable === "boolean" &&
    validValues(action, v.values, true)
  );
}
const labeled = (v: unknown) =>
  isObject(v) &&
  keys(v, ["code", "label"]) &&
  typeof v.code === "string" &&
  /^[A-Z][A-Z0-9_]{0,63}$/.test(v.code) &&
  safeText(v.label, 200);
export function parseEnvelope(value: unknown): Envelope {
  const fail = () => {
    throw new Error("暂时无法显示工作卡，请刷新后重试。");
  };
  if (
    !isObject(value) ||
    !keys(value, [
      "todaySummary",
      "currentCard",
      "nextSummaries",
      "waitingCount",
      "chatComposer",
    ]) ||
    !safeText(value.todaySummary) ||
    !revision(value.waitingCount) ||
    !Array.isArray(value.nextSummaries) ||
    value.nextSummaries.length > 2
  )
    return fail();
  if (
    !value.nextSummaries.every(
      (n) =>
        isObject(n) &&
        keys(n, ["taskId", "businessPurpose", "priority", "timeHint"]) &&
        uuid(n.taskId) &&
        labeled(n.businessPurpose) &&
        ["URGENT", "NORMAL"].includes(String(n.priority)) &&
        safeText(n.timeHint, 200),
    )
  )
    return fail();
  const c = value.currentCard;
  if (c !== null) {
    if (
      !isObject(c) ||
      !keys(c, [
        "taskId",
        "taskType",
        "taskRevision",
        "subject",
        "owner",
        "businessPurpose",
        "primaryCommand",
        "expectedCompletionFact",
        "sla",
        "versionStatus",
        "commandForm",
        "actionDraft",
        "preconditions",
      ]) ||
      !uuid(c.taskId) ||
      !revision(c.taskRevision) ||
      typeof c.taskType !== "string" ||
      !(c.taskType in actions)
    )
      return fail();
    const action = actions[c.taskType as keyof typeof actions];
    const completion =
      c.taskType === "CONTACT_LEAD"
        ? "LEAD_CONTACT_RESULT"
        : c.taskType === "ASSIGN_LEAD"
          ? "LEAD_ASSIGNMENT"
          : c.taskType === "COMPLETE_LEAD_INGRESS"
            ? "LEAD"
            : "DECISION_RECORD";
    if (c.expectedCompletionFact !== completion) return fail();
    if (
      !labeled(c.businessPurpose) ||
      (c.businessPurpose as Values).code !== c.taskType ||
      !isObject(c.primaryCommand) ||
      !keys(c.primaryCommand, ["code", "label", "enabled"]) ||
      c.primaryCommand.code !== action ||
      !safeText(c.primaryCommand.label, 200) ||
      typeof c.primaryCommand.enabled !== "boolean"
    )
      return fail();
    if (
      !isObject(c.subject) ||
      !keys(
        c.subject,
        ["subjectType", "subjectRef", "subjectRevision", "title", "subtitle"],
        ["subjectType", "subjectRef", "subjectRevision", "title"],
      ) ||
      c.subject.subjectType !== "LEAD" ||
      !safeText(c.subject.subjectRef, 512) ||
      !revision(c.subject.subjectRevision) ||
      !safeText(c.subject.title, 200) ||
      (c.subject.subtitle !== undefined && !safeText(c.subject.subtitle, 300))
    )
      return fail();
    if (
      !isObject(c.owner) ||
      !keys(c.owner, ["displayName", "organizationLabel"]) ||
      !safeText(c.owner.displayName, 200) ||
      !safeText(c.owner.organizationLabel, 200)
    )
      return fail();
    if (
      !isObject(c.sla) ||
      !keys(c.sla, ["code", "dueAt", "status", "timeHint"]) ||
      !safeText(c.sla.code, 64) ||
      !instant(c.sla.dueAt) ||
      !["ON_TRACK", "DUE_SOON", "OVERDUE"].includes(String(c.sla.status)) ||
      !safeText(c.sla.timeHint, 200) ||
      !["CURRENT", "REFRESH_RECOMMENDED"].includes(String(c.versionStatus)) ||
      !safeText(c.expectedCompletionFact, 64)
    )
      return fail();
    if (
      !isObject(c.commandForm) ||
      !keys(c.commandForm, [
        "actionCode",
        "schemaVersion",
        "values",
        "fields",
      ]) ||
      c.commandForm.actionCode !== action ||
      c.commandForm.schemaVersion !== 1 ||
      !validValues(action, c.commandForm.values, false) ||
      !validFields(action, c.commandForm.fields) ||
      !validPreconditions(c.preconditions)
    )
      return fail();
    if (c.actionDraft !== null && !validDraft(c.actionDraft, action))
      return fail();
    if ((c.actionDraft === null) !== (c.preconditions.draftETag === null))
      return fail();
  }
  const chat = value.chatComposer;
  if (
    !isObject(chat) ||
    !keys(chat, ["mode", "targetTaskId", "placeholder", "enabled"]) ||
    chat.mode !== "ACTION_DRAFT" ||
    !safeText(chat.placeholder, 200) ||
    typeof chat.enabled !== "boolean" ||
    (c === null
      ? chat.targetTaskId !== null || chat.enabled
      : chat.targetTaskId !== (c as Values).taskId)
  )
    return fail();
  return value as Envelope;
}
export function candidate(
  card: Card,
  input: Values,
): Schema["SaveActionDraftV1"] {
  const action = card.commandForm.actionCode;
  const values = { ...input };
  // Exact selectors are retained from the authorized card, never accepted from text input.
  for (const name of selectors[action]) {
    const server = (card.commandForm.values as Values)[name];
    const previous = (card.actionDraft?.values as Values | undefined)?.[name];
    if (
      card.actionDraft &&
      previous !== server &&
      !(previous === undefined && server === "")
    )
      throw new Error("关联内容已变化，请刷新后核对候选。");
    if (server === undefined || server === "") delete values[name];
    else values[name] = server;
  }
  for (const name of editable[action])
    if (values[name] === "") delete values[name];
  if (
    action === "RECORD_CONTACT_RESULT" &&
    values.resultCode !== "CONNECTED_VALID"
  )
    delete values.legalNeed;
  if (!validValues(action, values, true))
    throw new Error(
      action === "RECORD_CONTACT_RESULT" &&
      values.resultCode === "CONNECTED_VALID"
        ? "请填写法律需求，并核对必填内容。"
        : "请补齐必填内容，并核对联系方式和选项。",
    );
  for (const f of card.commandForm.fields) {
    if (
      f.control === "SELECT" &&
      !f.options.some((o) => o.value === values[f.name] && !o.disabled)
    )
      throw new Error("请选择当前可用的选项。");
    if (
      f.readOnly &&
      values[f.name] !== (card.commandForm.values as Values)[f.name]
    )
      throw new Error("部分内容已不可编辑，请刷新工作卡。");
  }
  return {
    actionCode: action,
    schemaVersion: 1,
    values,
  } as Schema["SaveActionDraftV1"];
}
export function sameValues(a: unknown, b: unknown): boolean {
  if (Array.isArray(a) && Array.isArray(b))
    return (
      a.length === b.length &&
      a.every((value, index) => sameValues(value, b[index]))
    );
  if (isObject(a) && isObject(b))
    return (
      Object.keys(a).length === Object.keys(b).length &&
      Object.keys(a).every((k) => sameValues(a[k], b[k]))
    );
  return a === b;
}
export function validReceipt(
  v: unknown,
  key: string,
): v is Schema["CommandReceipt"] {
  if (
    !isObject(v) ||
    !uuid(v.commandId) ||
    v.commandId !== key ||
    !uuid(v.receiptId) ||
    !instant(v.completedAt)
  )
    return false;
  if (v.outcome === "REJECTED")
    return (
      keys(v, [
        "commandId",
        "receiptId",
        "completedAt",
        "outcome",
        "rejectionCode",
      ]) &&
      [
        "NOT_AUTHORIZED",
        "APPOINTMENT_INACTIVE",
        "TASK_NOT_OPEN",
        "TASK_ALREADY_COMPLETED",
        "DRAFT_DIGEST_MISMATCH",
        "INGRESS_COMPLETION_ALREADY_RECORDED",
        "STALE_TASK",
        "STALE_DRAFT",
        "STALE_SUBJECT",
        "SUPERVISOR_UNRESOLVED",
        "SOURCE_INTAKE_OWNER_UNRESOLVED",
      ].includes(String(v.rejectionCode))
    );
  if (
    !["SUCCEEDED", "NO_CHANGE"].includes(String(v.outcome)) ||
    !keys(v, [
      "commandId",
      "receiptId",
      "completedAt",
      "outcome",
      "resultFact",
    ]) ||
    !isObject(v.resultFact)
  )
    return false;
  const f = v.resultFact;
  return (
    keys(
      f,
      ["factType", "factRef", "revision", "digest"],
      ["factType", "factRef"],
    ) &&
    [
      "LEAD",
      "ACTION_DRAFT",
      "TASK_OCCURRENCE",
      "DECISION_RECORD",
      "LEAD_ASSIGNMENT",
      "LEAD_CONTACT_RESULT",
    ].includes(String(f.factType)) &&
    safeText(f.factRef, 512) &&
    (["DECISION_RECORD", "LEAD_CONTACT_RESULT"].includes(String(f.factType))
      ? hash(f.digest) && f.revision === undefined
      : revision(f.revision) && f.digest === undefined)
  );
}
