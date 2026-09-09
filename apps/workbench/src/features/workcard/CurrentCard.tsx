import { useEffect, useRef, useState } from "react";
import { User } from "@phosphor-icons/react/User";
import { Clock } from "@phosphor-icons/react/Clock";
import { ShieldCheck } from "@phosphor-icons/react/ShieldCheck";
import { FloppyDisk } from "@phosphor-icons/react/FloppyDisk";
import { ActionDraftForm } from "./ActionDraftForm";
import {
  candidate,
  sameValues,
  type Card,
  type Schema,
  type Values,
} from "./contract";
const resultCopy: Record<Card["taskType"], string> = {
  RESOLVE_LEAD_DUPLICATE: "记录本次归属判断，保留已有客户事实。",
  COMPLETE_LEAD_INGRESS: "本次补全联系方式；后续联系由新的责任承接。",
  ASSIGN_LEAD: "记录准确分配结果，后续联系由新的责任承接。",
  RESOLVE_LEAD_ROUTING_GAP:
    "记录本次调配处置。停止受理请求仍需来源负责人确认。",
  ACK_SOURCE_INTAKE_STOP_REQUEST: "仅确认收到停止受理请求，来源状态保持原样。",
  CONTACT_LEAD: "记录本次联系事实；后续行动由新的责任承接。",
  REVIEW_LEAD_VALIDITY: "记录本次复核决定，保留历史联系事实。",
};
interface Props {
  card: Card;
  composer: Schema["ChatComposer"];
  busy: boolean;
  blocked: boolean;
  save: (v: Values) => Promise<void>;
  submit: (v: Values) => Promise<void>;
}
export function CurrentCard({
  card,
  composer,
  busy,
  blocked,
  save,
  submit,
}: Props) {
  const [values, setValues] = useState<Values>({
    ...(card.actionDraft?.values ?? card.commandForm.values),
  });
  const context = {
    taskId: card.taskId,
    taskRevision: card.taskRevision,
    preconditions: card.preconditions,
    form: card.commandForm,
    draft: card.actionDraft,
  };
  const previousContext = useRef(context);
  const [notice, setNotice] = useState<string | null>(null);
  useEffect(() => {
    if (sameValues(previousContext.current, context)) return;
    previousContext.current = context;
    setValues({ ...(card.actionDraft?.values ?? card.commandForm.values) });
    setNotice("候选来源或版本已更新，已重新载入当前内容，请核对后继续。");
  }, [card]);
  const editable =
    !blocked &&
    card.primaryCommand.enabled &&
    card.actionDraft?.editable !== false;
  const change = (name: string, value: string) =>
    setValues((current) => {
      const next = { ...current, [name]: value };
      if (name === "resultCode" && value !== "CONNECTED_VALID")
        delete next.legalNeed;
      return next;
    });
  const targetName =
    card.taskType === "CONTACT_LEAD"
      ? "resultSummary"
      : card.taskType === "COMPLETE_LEAD_INGRESS"
        ? "sourceSummary"
        : card.taskType === "ASSIGN_LEAD"
          ? null
          : "rationaleSummary";
  const target = card.commandForm.fields.find(
    (f) => f.name === targetName && !f.readOnly,
  );
  let saved = false;
  try {
    saved =
      !!card.actionDraft &&
      sameValues(candidate(card, values).values, card.actionDraft.values);
  } catch {
    /* Incomplete candidates remain editable. */
  }
  return (
    <>
      <article className="current-card" aria-labelledby="current-purpose">
        <div className="card-context">
          <p className="eyebrow">当前责任</p>
          <h1 id="current-purpose">{card.businessPurpose.label}</h1>
          <p className="subject-title">{card.subject.title}</p>
          {card.subject.subtitle && (
            <p className="muted">{card.subject.subtitle}</p>
          )}
          <p className="meta-line">
            <User aria-hidden="true" size={21} />
            负责人：{card.owner.displayName} · {card.owner.organizationLabel}
          </p>
          <p className="meta-line">
            <Clock aria-hidden="true" size={21} />
            {card.sla.timeHint}
            <span className="status-tag">
              {card.sla.status === "OVERDUE"
                ? "已逾期"
                : card.sla.status === "DUE_SOON"
                  ? "即将到期"
                  : "按期推进"}
            </span>
          </p>
          <div className="purpose-note">
            <span className="eyebrow">业务目的</span>
            <p>{resultCopy[card.taskType]}</p>
          </div>
          <p className="version-note">
            {card.versionStatus === "CURRENT" ? "当前版本" : "建议刷新后核对"}
          </p>
        </div>
        <div className="card-action">
          <h2>请核对本次候选内容</h2>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              void submit(values);
            }}
            noValidate
          >
            <fieldset disabled={busy || !editable}>
              <legend className="sr-only">
                {card.businessPurpose.label}候选表单
              </legend>
              <ActionDraftForm
                card={card}
                values={values}
                onChange={change}
                disabled={busy || !editable}
              />
            </fieldset>
            {notice && (
              <p className="candidate-state" role="status">
                {notice}
              </p>
            )}
            <p className="candidate-state">
              {blocked
                ? "当前候选暂不可提交，请先核对上方状态。"
                : saved
                  ? "候选已保存，请确认处理结果。"
                  : "候选尚未保存，请先保存后确认。"}
            </p>
            <button
              id="primary-confirm"
              type="submit"
              className="primary-action"
              disabled={busy || !editable || !saved}
            >
              {busy ? "正在处理…" : card.primaryCommand.label}
            </button>
          </form>
          <p className="result-note">
            <ShieldCheck aria-hidden="true" size={21} />
            <span>{resultCopy[card.taskType]}</span>
          </p>
        </div>
      </article>
      <section className="composer" aria-label="候选输入">
        <div className="composer-content">
          {target ? (
            <>
              <label htmlFor="chat-candidate">候选输入：{target.label}</label>
              <textarea
                id="chat-candidate"
                rows={2}
                value={String(values[target.name] ?? "")}
                onChange={(e) => change(target.name, e.target.value)}
                placeholder={composer.placeholder}
                disabled={busy || !editable || !composer.enabled}
              />
              <p>内容直接保存到“{target.label}”；其他选项请在卡片中选择。</p>
            </>
          ) : (
            <>
              <p className="composer-title">保存上方所选负责人</p>
              <p>请在卡片中选择允许的销售负责人，再保存候选。</p>
            </>
          )}
        </div>
        <button
          className="secondary-action"
          onClick={() => void save(values)}
          disabled={busy || !editable || !composer.enabled}
        >
          <FloppyDisk size={20} aria-hidden="true" />
          保存候选
        </button>
      </section>
    </>
  );
}
