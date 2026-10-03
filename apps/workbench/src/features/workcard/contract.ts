import type { components } from "../../generated/api/schema";
import { opportunityCandidate, validOpportunityDraft, validOpportunityValues } from "./opportunityProgress";
export type Schema = components["schemas"];
export type PublicReceipt = Schema["CommandReceipt"];
export const transferTaskActions={PREPARE_TRANSFER:'SUBMIT_TRANSFER',SUPPLEMENT_TRANSFER:'RESUBMIT_TRANSFER',REVIEW_TRANSFER:'RECORD_TRANSFER_CONFLICT_REVIEW',ACCEPT_TRANSFER:'RECORD_TRANSFER_INTAKE',CLASSIFY_MATTER:'CLASSIFY_MATTER'} as const;
export const isTransferTask=(taskType:string):taskType is keyof typeof transferTaskActions=>Object.hasOwn(transferTaskActions,taskType);
export const isTransferCard=(card:Card):card is Schema['R2TransferCurrentCardV1']=>isTransferTask(card.taskType);
const transferCompletion:Record<string,string>={SUBMIT_TRANSFER:'TRANSFER_SUBMISSION',RESUBMIT_TRANSFER:'TRANSFER_SUBMISSION',RECORD_TRANSFER_CONFLICT_REVIEW:'TRANSFER_CONFLICT_REVIEW',RECORD_TRANSFER_INTAKE:'TRANSFER_INTAKE',CLASSIFY_MATTER:'MATTER_CLASSIFICATION'};
export const contractTaskActions={CHECK_CONTRACT_RECEIPT:'RECORD_CONTRACT_RECEIPT_REVIEW',SUPPLEMENT_CONTRACT_RECEIPT:'SUPPLEMENT_CONTRACT_RECEIPT',CHECK_CONTRACT_EXECUTION:'VERIFY_CONTRACT_EXECUTION_CONDITIONS',REVIEW_CONTRACT_TERMINATION:'RECORD_CONTRACT_TERMINATION_REVIEW',ARRANGE_CONTRACT_SIGNATURE:'CONFIRM_CONTRACT_SIGNATURE_ARRANGEMENT',COLLECT_CONTRACT_SIGNATURE:'SUBMIT_CONTRACT_SIGNATURE',VERIFY_CONTRACT_SIGNATURE:'RECORD_CONTRACT_SIGNATURE_VERIFICATION',ARCHIVE_CONTRACT_SIGNATURE:'ARCHIVE_CONTRACT_SIGNATURE',REQUEST_CONTRACT_PREPARATION:'REQUEST_CONTRACT_PREPARATION',DECIDE_CONTRACT_PREPARATION:'RECORD_CONTRACT_PREPARATION_DECISION',PREPARE_CONTRACT:'FORM_CONTRACT',SUBMIT_CONTRACT_REVIEW:'REQUEST_CONTRACT_REVIEW',REVIEW_CONTRACT:'RECORD_CONTRACT_REVIEW',SUBMIT_CONTRACT_APPROVAL:'REQUEST_CONTRACT_APPROVAL',APPROVE_CONTRACT:'RECORD_CONTRACT_DECISION',SUPPLEMENT_CONTRACT_REVIEW:'REQUEST_CONTRACT_REVIEW'} as const;
export const isContractTask=(taskType:string):taskType is keyof typeof contractTaskActions=>Object.hasOwn(contractTaskActions,taskType);
type ContractPurposeCard=Schema["R2ContractCurrentCardV1"];
export type Card = Schema["R2CurrentCardV1"];
export const isContractCard=(card:Card):card is ContractPurposeCard=>isContractTask(card.taskType);
export type StandardCard = Exclude<Card,Schema["R2QuoteCurrentCardV1"]|ContractPurposeCard|Schema["R2TransferCurrentCardV1"]>;
export const isQuoteCard=(card:Card):card is Schema["R2QuoteCurrentCardV1"]=>isQuoteTask(card.taskType);
export type Envelope = Schema["CurrentWorkCardEnvelope"];
export type Values = Record<string, unknown>;
export type Action = Schema["ActionCode"] | "RECORD_SOURCE_REQUEST_CONTINUATION";
export const quoteTaskActions = {PREPARE_QUOTE:'FORM_QUOTE',SUBMIT_QUOTE_APPROVAL:'REQUEST_QUOTE_APPROVAL',APPROVE_QUOTE:'RECORD_QUOTE_DECISION',DELIVER_QUOTE:'RECORD_QUOTE_DELIVERY',RECORD_QUOTE_REPLY:'RECORD_QUOTE_RESPONSE',RESOLVE_QUOTE_AUTHORITY:'REQUEST_QUOTE_APPROVAL'} as const;
export const isQuoteTask = (taskType:string):taskType is keyof typeof quoteTaskActions => Object.hasOwn(quoteTaskActions,taskType);
const quoteCompletion:Record<string,string>={FORM_QUOTE:'QUOTE_REVISION',REQUEST_QUOTE_APPROVAL:'QUOTE_APPROVAL_REQUEST',RECORD_QUOTE_DECISION:'QUOTE_APPROVAL_DECISION',RECORD_QUOTE_DELIVERY:'QUOTE_ISSUE',RECORD_QUOTE_RESPONSE:'QUOTE_RESPONSE'};
export const actions = {
  RESOLVE_SOURCE_REQUEST: "RECORD_SOURCE_REQUEST_CONTINUATION",
  RESOLVE_LEAD_DUPLICATE: "RESOLVE_DUPLICATE_LEAD",
  COMPLETE_LEAD_INGRESS: "COMPLETE_LEAD_INGRESS",
  ASSIGN_LEAD: "ASSIGN_LEAD",
  RESOLVE_LEAD_ROUTING_GAP: "RECORD_ROUTING_DISPOSITION",
  ACK_SOURCE_INTAKE_STOP_REQUEST: "ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST",
  CONTACT_LEAD: "RECORD_CONTACT_RESULT",
  REVIEW_LEAD_VALIDITY: "REVIEW_LEAD_VALIDITY",
} as const;
const editable: Record<Action, string[]> = {
  RECORD_SOURCE_REQUEST_CONTINUATION: ["decisionCode","ownerAppointmentId","reviewAt","rationaleSummary"],
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
  RECORD_SOURCE_REQUEST_CONTINUATION: [],
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
  RECORD_SOURCE_REQUEST_CONTINUATION: ["ASSIGN_SELECTED","SCHEDULE_REVIEW","END_LEAD"],
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
  if(action === "RECORD_SOURCE_REQUEST_CONTINUATION") return safeText(v.rationaleSummary) && choices[action].includes(String(v.decisionCode)) &&
    (v.decisionCode === "ASSIGN_SELECTED" ? uuid(v.ownerAppointmentId) && v.reviewAt === undefined : v.ownerAppointmentId === undefined &&
      (v.decisionCode === "SCHEDULE_REVIEW" ? instant(v.reviewAt) : v.reviewAt === undefined));
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
          : (f.name === "evidenceSubmissionId" || f.name === "reviewAt")
            ? "TEXT"
            : f.name === "decisionCode" ||
                f.name === "ownerAppointmentId" ||
                f.name in choices
              ? "SELECT"
              : "TEXTAREA";
    if (f.control !== control) return false;
    if (control !== "SELECT") return f.options.length === 0;
    return (
      (f.options.length > 0 || action === "RECORD_SOURCE_REQUEST_CONTINUATION" && f.name === "ownerAppointmentId") &&
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
  action: Action | "RECORD_OPPORTUNITY_PROGRESS",
): v is Schema["ActionDraftProjection"] | Schema["OpportunityProgressDraftProjectionV1"] | Schema["RecordSourceRequestContinuationDraftProjection"] {
  if(action === "RECORD_OPPORTUNITY_PROGRESS") return validOpportunityDraft(v);
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
      "myTasks",
      "selectionNotice",
      "recommendedTaskId",
    ], ["todaySummary", "currentCard", "nextSummaries", "waitingCount", "chatComposer"]) ||
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
        keys(n, ["taskId", "businessPurpose", "priority", "timeHint", "subjectTitle", "subjectFactRef"], ["taskId", "businessPurpose", "priority", "timeHint"]) &&
        (n.subjectTitle === undefined || safeText(n.subjectTitle, 200)) && (n.subjectFactRef === undefined || (typeof n.subjectFactRef === "string" && /^[A-Za-z0-9_-]{43}$/.test(n.subjectFactRef))) &&
        uuid(n.taskId) &&
        labeled(n.businessPurpose) &&
        ["URGENT", "NORMAL"].includes(String(n.priority)) &&
        safeText(n.timeHint, 200),
    )
  )
    return fail();
  if (value.selectionNotice !== undefined && !safeText(value.selectionNotice)) return fail();
  if (value.myTasks !== undefined && (!Array.isArray(value.myTasks) || !value.myTasks.every(n =>
    isObject(n) && keys(n, ["taskId", "businessPurpose", "priority", "timeHint", "subjectTitle", "subjectFactRef"], ["taskId", "businessPurpose", "priority", "timeHint"]) && (n.subjectTitle === undefined || safeText(n.subjectTitle, 200)) && (n.subjectFactRef === undefined || (typeof n.subjectFactRef === "string" && /^[A-Za-z0-9_-]{43}$/.test(n.subjectFactRef))) && uuid(n.taskId) && labeled(n.businessPurpose) && ["URGENT", "NORMAL"].includes(String(n.priority)) && safeText(n.timeHint, 200)))) return fail();
  if (value.recommendedTaskId !== undefined && value.recommendedTaskId !== null && !uuid(value.recommendedTaskId)) return fail();
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
      (!(c.taskType in actions) && c.taskType !== "PROGRESS_OPPORTUNITY" && !isQuoteTask(c.taskType) && !isContractTask(c.taskType) && !isTransferTask(c.taskType))
    )
      return fail();
    const quoteTask=isQuoteTask(c.taskType)||isContractTask(c.taskType)||isTransferTask(c.taskType);
    const action = isTransferTask(c.taskType)?transferTaskActions[c.taskType]:isContractTask(c.taskType)?contractTaskActions[c.taskType]:isQuoteTask(c.taskType) ? quoteTaskActions[c.taskType] : c.taskType === "PROGRESS_OPPORTUNITY" ? "RECORD_OPPORTUNITY_PROGRESS" : actions[c.taskType as keyof typeof actions];
    const completion =
      isTransferTask(c.taskType)?transferCompletion[action]:isContractTask(c.taskType)?({RECORD_CONTRACT_RECEIPT_REVIEW:'CONTRACT_PAYMENT_REVIEW',SUPPLEMENT_CONTRACT_RECEIPT:'CONTRACT_PAYMENT_REVIEW',VERIFY_CONTRACT_EXECUTION_CONDITIONS:'CONTRACT_EXECUTION_VERIFICATION',RECORD_CONTRACT_TERMINATION_REVIEW:'CONTRACT_NEGOTIATION_DISPOSITION',CONFIRM_CONTRACT_SIGNATURE_ARRANGEMENT:'CONTRACT_SIGNATURE_ARRANGEMENT',SUBMIT_CONTRACT_SIGNATURE:'CONTRACT_SIGNATURE_SUBMISSION',RECORD_CONTRACT_SIGNATURE_VERIFICATION:'CONTRACT_SIGNATURE_VERIFICATION',ARCHIVE_CONTRACT_SIGNATURE:'CONTRACT_SIGNATURE_ARCHIVE',REQUEST_CONTRACT_PREPARATION:'CONTRACT_PREPARATION_REQUEST',RECORD_CONTRACT_PREPARATION_DECISION:'CONTRACT_PREPARATION_DECISION',FORM_CONTRACT:'CONTRACT_REVISION',REQUEST_CONTRACT_REVIEW:'CONTRACT_REVIEW_REQUEST',RECORD_CONTRACT_REVIEW:'CONTRACT_REVIEW_DECISION',REQUEST_CONTRACT_APPROVAL:'CONTRACT_APPROVAL_REQUEST',RECORD_CONTRACT_DECISION:'CONTRACT_APPROVAL_DECISION'} as Record<string,string>)[action]:quoteTask ? quoteCompletion[action] : c.taskType === "PROGRESS_OPPORTUNITY" ? "OPPORTUNITY_PROGRESS" : c.taskType === "CONTACT_LEAD"
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
      c.subject.subjectType !== (quoteTask || c.taskType === "PROGRESS_OPPORTUNITY" ? "OPPORTUNITY" : "LEAD") ||
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
      !(quoteTask ? isObject(c.commandForm.values) && Object.keys(c.commandForm.values).length===0 && Array.isArray(c.commandForm.fields) && c.commandForm.fields.length===0
        : action === "RECORD_OPPORTUNITY_PROGRESS"
        ? isObject(c.commandForm.values) && (Object.keys(c.commandForm.values).length===0 || validOpportunityValues(c.commandForm.values)) && Array.isArray(c.commandForm.fields) && c.commandForm.fields.length===0
        : validValues(action as Action, c.commandForm.values, false) && validFields(action as Action, c.commandForm.fields)) ||
      !validPreconditions(c.preconditions)
    )
      return fail();
    if (c.actionDraft !== null && (quoteTask || !validDraft(c.actionDraft, action as Action)))
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
): Schema["SaveActionDraftV1"] | Schema["SaveOpportunityProgressDraftV1"] | Schema["SaveSourceRequestDraftV1"] {
  if(isQuoteCard(card)||isContractCard(card)||isTransferCard(card)) throw new Error("请通过报价工作卡办理当前事项。");
  if(card.taskType === "PROGRESS_OPPORTUNITY") return opportunityCandidate(input);
  const action = card.commandForm.actionCode;
  const values = { ...input };
  if(action === "RECORD_SOURCE_REQUEST_CONTINUATION") {
    if(values.decisionCode !== "ASSIGN_SELECTED") delete values.ownerAppointmentId;
    if(values.decisionCode !== "SCHEDULE_REVIEW") delete values.reviewAt;
    else if(!instant(values.reviewAt) || Date.parse(String(values.reviewAt)) <= Date.now()) throw new Error("请选择未来的复查时间。");
  }
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
      f.control === "SELECT" && !(action === "RECORD_SOURCE_REQUEST_CONTINUATION" && f.name === "ownerAppointmentId" && values.decisionCode !== "ASSIGN_SELECTED") &&
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
  } as Schema["SaveActionDraftV1"] | Schema["SaveSourceRequestDraftV1"];
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
export function validReceipt(v: unknown, key: string): v is PublicReceipt {
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
        "VALIDATION_FAILED",
        "NOT_AUTHORIZED",
        "NOT_FOUND",
        "APPOINTMENT_INACTIVE",
        "TASK_NOT_OPEN",
        "TASK_ALREADY_COMPLETED",
        "DRAFT_DIGEST_MISMATCH",
        "INGRESS_COMPLETION_ALREADY_RECORDED",
        "STALE_TASK",
        "STALE_DRAFT",
        "STALE_SUBJECT",
        "STALE_EVIDENCE",
        "CUSTOMER_CONFIRMATION_REQUIRED",
        "CONTRACT_HANDLING_PAUSED",
        "CONTRACT_PREPARATION_SOURCE_REQUIRED",
        "CONTRACT_RESPONSIBILITY_REQUIRED",
        "CONTRACT_REVIEW_SCOPE_REQUIRED",
        "CONTRACT_REVIEW_NOT_CLEAR",
        "CONTRACT_REVIEW_SCOPE_COMPLETE",
        "CONTRACT_REVIEW_FINDING_REQUIRED",
        "CONTRACT_REVIEW_WAIVER_UNAVAILABLE",
        "CONTRACT_CONFLICT_DECISION_REQUIRED",
        "COMMERCIAL_AUTHORIZATION_REQUIRED",
        "CONTRACT_APPROVAL_POLICY_CHANGED",
        "CONTRACT_APPROVAL_POLICY_REQUIRED",
        "CONTRACT_REVIEW_REQUIRED",
        "CONTRACT_SOURCE_AMBIGUOUS",
        "CONTRACT_VERSION_BASIS_CHANGED",

        "SUPERVISOR_UNRESOLVED",
        "SOURCE_INTAKE_OWNER_UNRESOLVED",
        "IDENTITY_BINDING_CONFLICT",
        "IDENTITY_STATE_CONFLICT",
        "IDENTITY_SELF_LOCKOUT",
        "IDENTITY_LAST_ADMIN",
        "IDENTITY_ORGANIZATION_DEPENDENCY",
        "IDENTITY_RESPONSIBILITY_DEPENDENCY",
        "OPPORTUNITY_CLOSED",
        "OPPORTUNITY_HAS_DOWNSTREAM_FACTS",
        "STALE_CUSTOMER_BASIS", "STALE_REVIEW", "STALE_TRANSFER_PARTY", "STALE_TRANSFER_REVIEW_BASIS", "RECIPIENT_UNAVAILABLE", "TRANSFER_REVIEW_OUTCOME_UNAVAILABLE",
        "STALE_IDENTITY",
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
      "OPPORTUNITY_OWNER_EXCEPTION",
      "OPPORTUNITY_CLOSURE",
      "OPPORTUNITY_MATERIAL_UPLOAD",
      "OPPORTUNITY_MATERIAL_VERSION",
      "TRANSFER_SUBMISSION", "TRANSFER_CONFLICT_REVIEW", "TRANSFER_INTAKE", "MATTER_CLASSIFICATION", "CONTRACT_PAYMENT_REQUEST", "CONTRACT_PAYMENT_REVIEW", "CONTRACT_PAYMENT_WORKFLOW", "CONTRACT_EXECUTION_VERIFICATION", "CONTRACT_EXECUTION_WORKFLOW", "CONTRACT_NEGOTIATION_DISPOSITION", "CONTRACT_SIGNATURE_DRAFT", "CONTRACT_SIGNATURE_ARRANGEMENT", "CONTRACT_SIGNATURE_SUBMISSION", "CONTRACT_SIGNATURE_VERIFICATION", "CONTRACT_SIGNATURE_ARCHIVE", "CONTRACT_SIGNATURE_REVISION_RETURN", "CONTRACT_PREPARATION_REQUEST", "CONTRACT_PREPARATION_DECISION", "CONTRACT", "CONTRACT_DRAFT", "CONTRACT_REVISION", "CONTRACT_REVIEW_REQUEST", "CONTRACT_REVIEW_DECISION", "CONTRACT_APPROVAL_REQUEST", "CONTRACT_APPROVAL_DECISION", "FOLLOWUP_ATTEMPT", "QUOTE_TERMINATION", "QUOTE_PREPARATION_INTENT", "QUOTE_DRAFT", "QUOTE_REVISION", "QUOTE_APPROVAL_REQUEST", "QUOTE_APPROVAL_DECISION", "QUOTE_ISSUE", "QUOTE_RESPONSE",
      "OPPORTUNITY_CUSTOMER_DRAFT",
      "OPPORTUNITY_CUSTOMER_CONFIRMATION",
      "OPPORTUNITY_PROGRESS",
      "APPOINTMENT_ROLE",
      "IDENTITY_PRINCIPAL",
      "ORGANIZATION_UNIT",
      "APPOINTMENT",
      "AUTHORITY_GRANT",
    ].includes(String(f.factType)) &&
    safeText(f.factRef, 512) &&
    (["FOLLOWUP_ATTEMPT", "DECISION_RECORD", "LEAD_CONTACT_RESULT", "OPPORTUNITY_PROGRESS", "QUOTE_REVISION", "QUOTE_RESPONSE", "CONTRACT_REVISION"].includes(String(f.factType))
      ? hash(f.digest) && f.revision === undefined
      : revision(f.revision) && (!(String(f.factType).startsWith('CONTRACT_SIGNATURE_')||f.factType==='CONTRACT_NEGOTIATION_DISPOSITION')||f.revision===0) && f.digest === undefined)
  );
}





