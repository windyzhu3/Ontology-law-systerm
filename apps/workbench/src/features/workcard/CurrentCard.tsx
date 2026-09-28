import {AiCandidatePanel} from '../ai/AiCandidatePanel';
import type {AiCandidateTransport} from '../../lib/aiCandidateTransport';
import type {WorkbenchSession} from '../../lib/sessionTransport';
import { useEffect, useImperativeHandle, useRef, useState, type Ref } from "react";
import { User } from "@phosphor-icons/react/User";
import { Clock } from "@phosphor-icons/react/Clock";
import { Briefcase } from "@phosphor-icons/react/Briefcase";
import { ShieldCheck } from "@phosphor-icons/react/ShieldCheck";
import { FloppyDisk } from "@phosphor-icons/react/FloppyDisk";
import { ActionDraftForm } from "./ActionDraftForm";
import { sameValues, isQuoteCard, isContractCard, isTransferCard, type Card as AnyCard, type StandardCard as Card, type Schema, type Values } from "./contract";

const resultCopy: Record<Card["taskType"], string> = {
  RESOLVE_SOURCE_REQUEST: "本操作只处理该线索，不改变来源渠道的全局状态。",
  PROGRESS_OPPORTUNITY: "记录本次真实进展，下次跟进按约定时间提醒。",
  RESOLVE_LEAD_DUPLICATE: "记录本次归属判断，保留已有客户事实。",
  COMPLETE_LEAD_INGRESS: "补全联系方式后，继续安排负责人和首次联系。",
  ASSIGN_LEAD: "分配确认后，由所选销售继续联系客户。",
  RESOLVE_LEAD_ROUTING_GAP: "记录本次调配安排；停止受理仍需来源负责人确认。",
  ACK_SOURCE_INTAKE_STOP_REQUEST: "确认收到请求，来源受理状态保持原样。",
  CONTACT_LEAD: "记录实际联系结果，并按结果安排后续事项。",
  REVIEW_LEAD_VALIDITY: "记录本次复核决定，保留历史联系事实。",
};
const questions: Record<Card["taskType"], string> = {
  RESOLVE_SOURCE_REQUEST: "确认现有线索的去向",
  PROGRESS_OPPORTUNITY: "本次实质进展是什么？",
  RESOLVE_LEAD_DUPLICATE: "这条线索应如何归属？",
  COMPLETE_LEAD_INGRESS: "请补全客户联系方式",
  ASSIGN_LEAD: "由哪位销售继续联系？",
  RESOLVE_LEAD_ROUTING_GAP: "本次如何安排后续处理？",
  ACK_SOURCE_INTAKE_STOP_REQUEST: "请确认本次受理请求",
  CONTACT_LEAD: "这次联系的结果是什么？",
  REVIEW_LEAD_VALIDITY: "本次复核的结论是什么？",
};
export interface CurrentCardHandle { saveDraft: () => Promise<boolean> }
interface Props {
  session?: WorkbenchSession;
  aiApi?: AiCandidateTransport;
  draftRef?: Ref<CurrentCardHandle>;
  onBusinessContinue?: (view:'choose'|'customer'|'attempt') => void;
  onDirtyChange?: (dirty: boolean) => void;
  card: AnyCard;
  composer: Schema["ChatComposer"];
  busy: boolean;
  blocked: boolean;
  save: (v: Values) => Promise<boolean | void>;
  submit: (v: Values) => Promise<void>;
}
export function CurrentCard(props:Props){if(isQuoteCard(props.card)||isContractCard(props.card)||isTransferCard(props.card))return null;return <StandardCurrentCard {...props} card={props.card}/>;}
function StandardCurrentCard({session,aiApi,card, onBusinessContinue, draftRef, onDirtyChange, composer, busy, blocked, save, submit}: Omit<Props,"card"> & {card:Card}) {
  const [values, setValues] = useState<Values>({...card.actionDraft?.values ?? card.commandForm.values});
  useEffect(() => {
    onDirtyChange?.(!sameValues(values, card.actionDraft?.values ?? card.commandForm.values));
  }, [values, card, onDirtyChange]);
  const context = {taskId:card.taskId, taskRevision:card.taskRevision, preconditions:card.preconditions, form:card.commandForm, draft:card.actionDraft};
  const previousContext = useRef(context);
  const [notice, setNotice] = useState<string | null>(null);
  useEffect(() => {
    if (sameValues(previousContext.current, context)) return;
    const previous = previousContext.current;
    previousContext.current = context;
    setValues({...card.actionDraft?.values ?? card.commandForm.values});
    const savedHere = sameValues(values, card.actionDraft?.values) && sameValues(previous.preconditions.taskETag, card.preconditions.taskETag);
    setNotice(savedHere ? "草稿已保存，尚未提交。" : "办理依据已更新，已重新载入当前内容，请核对后继续。");
  }, [card]);
  const editable = !blocked && card.versionStatus === "CURRENT" && card.primaryCommand.enabled && card.actionDraft?.editable !== false;
  const saveDraft = async () => {
    if (busy || !editable || !composer.enabled) return false;
    return await save(values) === true;
  };
  useImperativeHandle(draftRef, () => ({saveDraft}));
  const change = (name: string, value: string) => {
    setNotice(null);
    setValues(current => {
      const next = {...current, [name]: value};
      if (card.taskType === "PROGRESS_OPPORTUNITY" && !next.occurredAt) next.occurredAt = new Date().toISOString();
      if(card.taskType === "RESOLVE_SOURCE_REQUEST" && name === "decisionCode") {if(value !== "ASSIGN_SELECTED") delete next.ownerAppointmentId;if(value !== "SCHEDULE_REVIEW") delete next.reviewAt;}
      if (name === "resultCode" && value !== "CONNECTED_VALID") delete next.legalNeed;
      return next;
    });
  };
  return <article className="current-card" aria-labelledby="current-purpose">
    <div className="card-context">
      <p className="eyebrow">当前责任</p>
      <h1 id="current-purpose">{card.taskType === "PROGRESS_OPPORTUNITY" ? "记录商机实质进展" : card.businessPurpose.label}</h1>
      <p className="subject-title">{card.subject.title}</p>
      <p className="meta-line"><User aria-hidden="true" size={21}/><span>{card.owner.displayName} · {card.owner.organizationLabel}</span></p>
      <p className="meta-line"><Clock aria-hidden="true" size={21}/><span>{card.sla.timeHint}</span>{card.sla.status !== "ON_TRACK" && <span className="status-tag">{card.sla.status === "OVERDUE" ? "已逾期" : "即将到期"}</span>}</p>
      <section className="card-known" aria-label="办理依据">
        <h2>办理依据</h2>
        <ul><li><Briefcase aria-hidden="true" size={20}/><span>{card.subject.subjectType === "OPPORTUNITY" ? "商机跟进" : "线索办理"} · {card.businessPurpose.label}</span></li>
          {card.subject.subtitle && <li><span>{card.subject.subtitle}</span></li>}
        </ul>
      </section>
      {card.versionStatus !== "CURRENT" && <p className="version-note" role="alert">责任依据与关联资料不一致，暂不能提交。请联系管理员核对责任依据，修复后刷新继续。</p>}
    </div>
    <div className="card-action">
      <h2>{questions[card.taskType]}</h2>
      <form onSubmit={event => {event.preventDefault(); if (!busy && editable) void submit(values);}} noValidate>
        {session&&card.taskType==='PROGRESS_OPPORTUNITY'&&<AiCandidatePanel session={session} target={{taskId:card.taskId}} task="SUMMARY" api={aiApi} sourceVersion={JSON.stringify([card.taskId,card.taskRevision,card.preconditions])} draftVersion={JSON.stringify(values)} disabled={busy||!editable} onApply={(field,value)=>{if(field==='progressSummary'){setNotice(null);setValues(previous=>({...previous,progressSummary:value}));}}}/>}
        <fieldset disabled={busy || !editable}><legend className="sr-only">{card.businessPurpose.label}表单</legend><ActionDraftForm card={card} values={values} onChange={change} disabled={busy || !editable}/></fieldset>
        {notice && !busy && <p className="candidate-state" role="status">{notice}</p>}
        {blocked && <p className="candidate-state">请先核对上方状态，再继续办理。</p>}
        <div className="card-action-area">
          <button id="primary-confirm" type="submit" className="primary-action" disabled={busy || !editable}>{busy ? "正在处理…" : card.primaryCommand.label}</button>
          <div className="card-secondary"><button type="button" className="draft-action" onClick={() => void saveDraft()} disabled={busy || !editable || !composer.enabled}><FloppyDisk size={20} aria-hidden="true"/>保存草稿</button></div>
        </div>
      </form>
      {card.taskType==='PROGRESS_OPPORTUNITY'&&onBusinessContinue&&<details className="history"><summary>其他业务处理</summary><p>准备进入下一环节时，无需先填写本次进展或下次时间。</p><div className="card-secondary"><button className="link-button" disabled={busy||!editable} onClick={()=>onBusinessContinue('choose')}>推进或结束本次商机</button><button className="link-button" disabled={busy||!editable} onClick={()=>onBusinessContinue('customer')}>维护客户与需求</button><button className="link-button" disabled={busy||blocked||card.versionStatus!=='CURRENT'} onClick={()=>onBusinessContinue('attempt')}>记录联系尝试，尚无有效进展</button></div></details>}
      <p className="result-note"><ShieldCheck aria-hidden="true" size={21}/><span>{resultCopy[card.taskType]}</span></p>
    </div>
  </article>;
}


