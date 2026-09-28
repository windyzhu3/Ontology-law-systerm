import { LeadCaptureFollowup } from "./LeadCaptureFollowup";
import type { createLeadIntakeApi } from "./leadIntakeApi";
import { useEffect, useLayoutEffect, useRef, useState, useSyncExternalStore } from "react";
import type { WorkbenchSession } from "../../lib/sessionTransport";
import { LeadImportBatch, type LeadImportStatus } from "./leadImportBatch";
import "../../styles/identity-admin.css";
import "../../styles/lead-intake.css";

const labels: Record<LeadImportStatus, string> = {
  captured: "已录入", unresolved: "结果待确认", pending: "未提交", invalid: "需修正", submitting: "提交中", rejected: "未录入",
};

/** Approved HF-04 content panel. The authorized intake route supplies the existing application shell. */
export function LeadImportResults({ batch, session, onReturn, returnLabel = "返回线索列表", followup, onOpenTask }: {
  batch: LeadImportBatch;
  session: WorkbenchSession;
  onReturn: () => void;
  returnLabel?: string;
  followup?: ReturnType<typeof createLeadIntakeApi>["followup"];
  onOpenTask?: (taskId: string) => void;
}) {
  const state = useSyncExternalStore(batch.subscribe, batch.snapshot);
  const [selection, select] = useState<{ rowNumber: number } | null>(null);
  const [error, setError] = useState("");
  const [receiptRow, setReceiptRow] = useState<number | null>(null);
  const active = useRef<AbortController | null>(null);
  const detail = useRef<HTMLElement>(null), list = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {if (selection !== null && window.innerWidth <= 700) {detail.current?.scrollIntoView({block:"start"});detail.current?.focus({preventScroll:true});}}, [selection]);
  useEffect(() => () => active.current?.abort(), [batch, session.identityEpoch, session.actorScopeKey]);
  if (batch.actorScopeKey !== session.actorScopeKey || !session.isCurrent()) return <p role="alert">会话已变化，请返回当前身份的工作台。</p>;
  const unresolved = state.rows.find(row => row.status === "unresolved");
  const selected = unresolved ?? state.rows.find(row => row.rowNumber === selection?.rowNumber) ?? state.rows[0];
  const pending = state.rows.some(row => row.status === "pending");
  const counts = Object.entries(labels).map(([status, label]) => ({ label, count: state.rows.filter(row => row.status === status).length })).filter(item => item.count);
  const perform = async () => {
    if (active.current || state.busy) return;
    const controller = new AbortController(); active.current = controller; setError("");
    try {
      if (unresolved) await batch.recover(session, controller.signal);
      else await batch.submit(session, controller.signal);
    } catch {
      if (!controller.signal.aborted) setError("会话或导入状态已变化，请重新核对。");
    } finally { if (active.current === controller) active.current = null; }
  };
  return <section className="lead-import-results" aria-label="本次导入结果" aria-busy={state.busy}>
    <header className="identity-page-heading"><div><h1>导入结果</h1><p>本页仅保留本次浏览器进度；离开后可在客户与线索中查询有权的已录入记录。</p></div></header>
    <div className="lead-import-counts" role="status" aria-live="polite">{counts.map(item => <span key={item.label}>{item.count}条{item.label}</span>)}</div>
    {state.notice && <p className="identity-command-feedback" role="status">{state.notice}</p>}
    {error && <p role="alert">{error}</p>}
    <div className="identity-page-grid">
      <div ref={list} className="identity-list-panel" tabIndex={-1}><div className="identity-list-scroll">
        <table className="identity-table lead-import-table"><caption className="lead-import-caption">本次导入行明细</caption>
          <thead><tr><th scope="col">来源行</th><th scope="col">客户／联系人</th><th scope="col">录入结果</th><th scope="col">下一步</th></tr></thead>
          <tbody>{state.rows.map(row => <tr key={row.rowNumber} className={selected?.rowNumber === row.rowNumber ? "selected" : undefined}>
            <td data-label="来源行">{row.rowNumber}</td><td data-label="客户／联系人"><button className="identity-row-button" aria-pressed={selected?.rowNumber === row.rowNumber} disabled={state.busy || !!unresolved} onClick={() => select({ rowNumber: row.rowNumber })}>{row.displayName}</button></td>
            <td data-label="录入结果">{labels[row.status]}</td><td data-label="下一步">{row.message}</td>
          </tr>)}</tbody>
        </table>
      </div><p className="identity-read-only-note">本次 {state.rows.length} 项</p></div>
      <aside ref={detail} tabIndex={-1} className="identity-detail-panel" aria-label="当前行详情"><button className="lead-mobile-return" onClick={() => {list.current?.scrollIntoView({block:"start"}); list.current?.querySelector<HTMLButtonElement>("button[aria-pressed=true]")?.focus({preventScroll:true});}}>返回列表</button>
        <h2>{selected?.displayName}</h2><p>{selected && labels[selected.status]}</p><p>{selected?.message}</p>
        {selected?.receipt && <><button className="link-button" onClick={()=>setReceiptRow(receiptRow===selected.rowNumber?null:selected.rowNumber)}>{receiptRow===selected.rowNumber?'收起原录入回执':'查看原录入回执'}</button>{receiptRow===selected.rowNumber&&<section aria-label="原录入回执"><p>第 {selected.rowNumber} 行 · {selected.receipt.outcome==='REJECTED'?'确认未录入':'确认已录入'}</p><p>确认时间（北京时间）：{new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hourCycle:'h23'}).format(new Date(selected.receipt.completedAt))}</p><p>回执编号：{selected.receipt.receiptId}</p></section>}</>}
        {selected?.status==='invalid'&&<p>本行未提交，尚未形成线索。请修正本行后通过录入入口重新核对；无需重复提交已录入行。</p>}
        {selected?.status==='rejected'&&<p>已确认本行未录入。请核对错误或权限后通过录入入口处理；不要重复导入其他已成功行。</p>}
        {unresolved && <p>核对原请求的结果，不会再次提交这条线索。</p>}
        {unresolved && <p className="identity-form-help">离开页面后，请通过恢复入口核对未决结果。</p>}
        <div className="identity-form-actions">
          {(unresolved || pending) && <button className="identity-primary" disabled={state.busy} onClick={() => { void perform(); }}>{state.busy ? "正在核对或提交…" : unresolved ? "核对提交结果" : "继续提交剩余行"}</button>}
          <button disabled={state.busy} onClick={onReturn}>{returnLabel}</button>
        </div>
        {!state.busy && !pending && !unresolved && selected?.status === "captured" && selected.subjectFactRef && followup && onOpenTask && <LeadCaptureFollowup session={session} factRef={selected.subjectFactRef} load={followup} onOpen={onOpenTask} />}
      </aside>
    </div>
  </section>;
}

