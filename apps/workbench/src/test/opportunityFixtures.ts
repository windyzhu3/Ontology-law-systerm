import { envelope, tags, taskId, digest } from "./fixtures";
export const opportunityValues = () => ({ progressTypeCode: "PHONE_CONNECTED" as const, progressSummary: "客户确认范围", occurredAt: new Date(Date.now()-10000).toISOString(), nextCheckAt: new Date(Date.now()+86400000).toISOString() });
export function opportunityEnvelope(values?: ReturnType<typeof opportunityValues>) {
  const base=envelope();
  return { ...base, currentCard: { ...base.currentCard!, taskType: "PROGRESS_OPPORTUNITY" as const,
    subject: { ...base.currentCard!.subject, subjectType: "OPPORTUNITY" as const, title: "海宁公司" },
    businessPurpose: {code:"PROGRESS_OPPORTUNITY",label:"推进客户委托"},
    primaryCommand:{code:"RECORD_OPPORTUNITY_PROGRESS",label:"确认本次进展",enabled:true},
    expectedCompletionFact:"OPPORTUNITY_PROGRESS",commandForm:{actionCode:"RECORD_OPPORTUNITY_PROGRESS",schemaVersion:1,values:values??{},fields:[]},
    actionDraft: values ? {draftId:taskId,draftRevision:2,actionCode:"RECORD_OPPORTUNITY_PROGRESS",schemaVersion:1,values,digest,updatedAt:new Date().toISOString(),editable:true}:null,
    preconditions:{...tags,draftETag:values?tags.draftETag:null},
  }};
}
