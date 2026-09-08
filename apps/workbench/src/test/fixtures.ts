import type { components } from "../generated/api/schema";

type S = components["schemas"];
export const taskId = "019c7000-0000-7000-8000-000000000001";
export const selectorId = "019c7000-0000-7000-8000-000000000002";
export const draftId = "019c7000-0000-7000-8000-000000000003";
export const digest = "a".repeat(43);
export const tags = {
  taskETag: `"task.${digest}"`,
  subjectETag: `"subject.${digest}"`,
  draftETag: `"draft.${digest}"`,
};
const field = (
  name: S["FormField"]["name"],
  label: string,
  control: S["FormField"]["control"],
  required = true,
  options: [string, string][] = [],
): S["FormField"] => ({
  name,
  label,
  control,
  required,
  readOnly: false,
  options: options.map(([value, label]) => ({ value, label, disabled: false })),
});
export const variants = [
  {
    taskType: "RESOLVE_LEAD_DUPLICATE",
    action: "RESOLVE_DUPLICATE_LEAD",
    purpose: "解决疑似重复线索",
    label: "确认线索归属",
    fact: "DECISION_RECORDED",
    values: {
      candidateLeadId: selectorId,
      candidateLeadRevision: 0,
      partyId: selectorId,
      partyRevision: 1,
      decisionCode: "KEEP_SEPARATE",
      rationaleSummary: "经核对为独立需求",
    },
    fields: [
      field("decisionCode", "线索归属", "SELECT", true, [
        ["LINK_EXISTING_PARTY", "关联已有客户"],
        ["KEEP_SEPARATE", "作为独立线索继续"],
      ]),
      field("rationaleSummary", "判断理由", "TEXTAREA"),
    ],
  },
  {
    taskType: "COMPLETE_LEAD_INGRESS",
    action: "COMPLETE_LEAD_INGRESS",
    purpose: "补全联系方式",
    label: "保存并继续分配",
    fact: "LEAD_INGRESS_COMPLETED",
    values: {
      phone: "+8613812345678",
      sourceCode: "CUSTOMER_PROVIDED",
      sourceSummary: "客户提供并确认",
    },
    fields: [
      field("phone", "联系电话", "TEL", false),
      field("email", "电子邮箱", "EMAIL", false),
      field("sourceCode", "信息来源", "SELECT", true, [
        ["CUSTOMER_PROVIDED", "客户提供"],
        ["OWNER_CONFIRMED", "负责人确认"],
      ]),
      field("sourceSummary", "来源说明", "TEXTAREA"),
    ],
  },
  {
    taskType: "ASSIGN_LEAD",
    action: "ASSIGN_LEAD",
    purpose: "人工指定负责人",
    label: "分配给所选销售",
    fact: "LEAD_ASSIGNED",
    values: { ownerAppointmentId: selectorId },
    fields: [
      field("ownerAppointmentId", "指定销售", "SELECT", true, [
        [selectorId, "陈晓 · 销售一组"],
      ]),
    ],
  },
  {
    taskType: "RESOLVE_LEAD_ROUTING_GAP",
    action: "RECORD_ROUTING_DISPOSITION",
    purpose: "零候选调配",
    label: "记录本次调配处置",
    fact: "DECISION_RECORDED",
    values: {
      decisionCode: "SCHEDULE_ROUTING_REVIEW",
      rationaleSummary: "等待容量恢复后复查",
    },
    fields: [
      field("decisionCode", "本次调配处置", "SELECT", true, [
        ["SCHEDULE_ROUTING_REVIEW", "安排调配复查"],
        ["RETRY_ASSIGNMENT_NOW", "重新尝试分配"],
        ["REQUEST_SOURCE_INTAKE_STOP", "请求停止来源受理"],
      ]),
      field("rationaleSummary", "处置理由", "TEXTAREA"),
    ],
  },
  {
    taskType: "ACK_SOURCE_INTAKE_STOP_REQUEST",
    action: "ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST",
    purpose: "确认来源受理停止请求",
    label: "确认收到停止受理请求",
    fact: "DECISION_RECORDED",
    values: {
      causalDecisionId: selectorId,
      causalDecisionHash: digest,
      rationaleSummary: "已收到请求并确认",
    },
    fields: [field("rationaleSummary", "确认说明", "TEXTAREA")],
  },
  {
    taskType: "CONTACT_LEAD",
    action: "RECORD_CONTACT_RESULT",
    purpose: "记录本次联系结果",
    label: "保存联系结果",
    fact: "CONTACT_RESULT_RECORDED",
    values: {
      leadAssignmentId: selectorId,
      leadAssignmentRevision: 0,
      contactChannelCode: "PHONE",
      resultCode: "NOT_CONNECTED",
      resultSummary: "本次拨打未接通",
    },
    fields: [
      field("contactChannelCode", "联系渠道", "SELECT", true, [
        ["PHONE", "电话"],
        ["EMAIL", "邮件"],
      ]),
      field("resultCode", "联系结果", "SELECT", true, [
        ["CONNECTED_VALID", "有效接通"],
        ["NOT_CONNECTED", "未接通"],
        ["SUSPECT_INVALID", "疑似无效"],
      ]),
      field("resultSummary", "联系说明", "TEXTAREA", false),
      field("evidenceSubmissionId", "联系证据", "TEXT", false),
    ],
  },
  {
    taskType: "REVIEW_LEAD_VALIDITY",
    action: "REVIEW_LEAD_VALIDITY",
    purpose: "复核疑似无效线索",
    label: "记录复核决定",
    fact: "DECISION_RECORDED",
    values: {
      triggeringContactResultId: selectorId,
      triggeringContactResultHash: digest,
      decisionCode: "CONFIRM_INVALID",
      rationaleSummary: "经复核需求无效",
    },
    fields: [
      field("decisionCode", "复核决定", "SELECT", true, [
        ["CONFIRM_INVALID", "确认无效并归档"],
        ["CLOSE_UNREACHED", "结束未接通跟进"],
        ["REOPEN_CONTACT", "重新开启联系"],
      ]),
      field("rationaleSummary", "复核理由", "TEXTAREA"),
    ],
  },
] as const;

export function envelope(
  index = 5,
  withDraft = false,
): S["CurrentWorkCardEnvelope"] {
  const selected = variants[index];
  const v = {
    ...selected,
    fields: [...structuredClone(selected.fields)],
    fact:
      selected.taskType === "CONTACT_LEAD"
        ? "LEAD_CONTACT_RESULT"
        : selected.taskType === "ASSIGN_LEAD"
          ? "LEAD_ASSIGNMENT"
          : selected.taskType === "COMPLETE_LEAD_INGRESS"
            ? "LEAD"
            : "DECISION_RECORD",
  };
  return {
    todaySummary: "当前有一项责任需要处理；另有两项后续责任，一项正在等待。",
    currentCard: {
      taskId,
      taskRevision: 0,
      taskType: v.taskType,
      subject: {
        subjectType: "LEAD",
        subjectRef: "safe-subject-reference",
        subjectRevision: 0,
        title: "王某 · 劳动仲裁咨询",
        subtitle: "请核对本次候选内容",
      },
      owner: { displayName: "陈晓", organizationLabel: "销售一组" },
      businessPurpose: { code: v.taskType, label: v.purpose },
      primaryCommand: { code: v.action, label: v.label, enabled: true },
      expectedCompletionFact: v.fact,
      sla: {
        code: "CONTACT_SLA",
        dueAt: "2026-09-08T02:45:00Z",
        status: "DUE_SOON",
        timeHint: "今天 10:45 前",
      },
      versionStatus: "CURRENT",
      commandForm: {
        actionCode: v.action,
        schemaVersion: 1,
        values: { ...v.values },
        fields: structuredClone(v.fields),
      },
      actionDraft: withDraft
        ? {
            draftId,
            draftRevision: 2,
            actionCode: v.action,
            schemaVersion: 1,
            values: { ...v.values },
            digest,
            updatedAt: "2026-09-08T02:00:00Z",
            editable: true,
          }
        : null,
      preconditions: { ...tags, draftETag: withDraft ? tags.draftETag : null },
    } as S["CurrentCard"],
    nextSummaries: [
      {
        taskId: selectorId,
        businessPurpose: { code: "CONTACT_LEAD", label: "联系下一位客户" },
        priority: "NORMAL",
        timeHint: "今天 15:00",
      },
      {
        taskId: draftId,
        businessPurpose: {
          code: "REVIEW_LEAD_VALIDITY",
          label: "复核联系结果",
        },
        priority: "URGENT",
        timeHint: "明天 09:30",
      },
    ],
    waitingCount: 1,
    chatComposer: {
      mode: "ACTION_DRAFT",
      enabled: true,
      targetTaskId: taskId,
      placeholder: "输入要保存的候选内容",
    },
  };
}
export const jsonResponse = (
  body: unknown,
  status = 200,
  etag = `"wb.${digest}"`,
) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ETag: etag },
  });
export function receipt(
  commandId: string,
  factType = "LEAD_CONTACT_RESULT",
): S["CommandReceipt"] {
  return {
    commandId,
    receiptId: "019c7000-0000-7000-8000-000000000004",
    outcome: "SUCCEEDED",
    completedAt: "2026-09-08T02:10:00Z",
    resultFact: {
      factType,
      factRef: "safe-result-reference",
      ...(factType === "ACTION_DRAFT" ? { revision: 2 } : { digest }),
    },
  } as S["CommandReceipt"];
}
export function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}
