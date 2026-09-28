import { LeadCaptureFollowup } from "./LeadCaptureFollowup";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { Scales, User } from "@phosphor-icons/react";
import type { WorkbenchSession } from "../../lib/sessionTransport";
import { RecoveryStore } from "../session/recoveryMarker";
import { IdentityDialog } from "../identity/IdentityActionConfirmation";
import type { IdentityLeaveGuard } from "../identity/IdentityAdminApplication";
import { createLeadIntakeApi, type LeadCaptureWrite } from "./leadIntakeApi";
import { captureBody, type CaptureFields, type IntakeSource } from "./leadCapture";
import { readLeadImportFile, type LeadImportFile } from "./leadImportFile";
import type { LeadImportField, LeadImportMapping, LeadImportRow } from "./leadImport";
import { LeadImportBatch } from "./leadImportBatch";
import { LeadImportResults } from "./LeadImportResults";
import "../../styles/tokens.css";
import "../../styles/workbench.css";
import "../../styles/identity-admin.css";
import "../../styles/lead-intake.css";

export const leadIntakeRoute = "/business/leads/intake";
type Props = { session: WorkbenchSession; api: ReturnType<typeof createLeadIntakeApi>; recovery: RecoveryStore; onReturn: () => void; returnLabel?: string; sessionActions: ReactNode; registerLeaveGuard?: (guard: IdentityLeaveGuard | null) => void; onRecover?: () => void; onOpenTask?: (taskId: string) => void };
const importLabels = { sourceRecordKey: "来源记录号", customerName: "客户名称", contactName: "联系人", phone: "手机号", email: "邮箱", legalNeedSummary: "需求描述" } as const;
export function LeadIntakeApplication(props: Props) {
  return <Workspace key={`${props.session.identityEpoch}:${props.session.actorScopeKey}`} {...props} />;
}
function Workspace({ session, api, recovery, onReturn, returnLabel, sessionActions, registerLeaveGuard, onRecover, onOpenTask }: Props) {
  const [sources, setSources] = useState<IntakeSource[] | null>(null), [account, setAccount] = useState("");
  const [fields, setFields] = useState<CaptureFields>({ legalNeedSummary: "" });
  const [mode, setMode] = useState<"manual" | "file" | "preview" | "result" | "success" | "unknown">("manual");
  const [busy, setBusy] = useState(false), [error, setError] = useState("");
  const [factRef, setFactRef] = useState<string | null>(null);
  const [write, setWrite] = useState<LeadCaptureWrite | null>(null);
  const [file, setFile] = useState<{ name: string; value: LeadImportFile } | null>(null), [mapping, setMapping] = useState<LeadImportMapping>({});
  const [batch, setBatch] = useState<LeadImportBatch | null>(null);
  const [, changed] = useState(0);
  const [discard, setDiscard] = useState<{ next: () => void; trigger: HTMLElement | null } | null>(null);
  const [recoveryDialog, setRecoveryDialog] = useState<HTMLElement | null>(null);
  const active = useRef<AbortController | null>(null);
  const sourceKey = useRef(crypto.randomUUID());
  const source = sources?.find(value => value.sourceAccountCode === account);
  const dirty = mode !== "success" && mode !== "result" && (Object.values(fields).some(Boolean) || !!file);
  const pending = () => { try { return recovery.read() !== null; } catch { return true; } };
  const leave: IdentityLeaveGuard = next => {
    if (active.current || busy || batch?.snapshot().busy) { setError("当前操作正在进行，请等待结果。"); return; }
    if (pending()) { setError("仍有操作结果待确认，请先核对原请求。"); return; }
    if (dirty || batch?.snapshot().rows.some(row => row.status === "pending")) setDiscard({ next, trigger: document.activeElement as HTMLElement | null });
    else next();
  };
  const latestLeave = useRef(leave); latestLeave.current = leave;
  useEffect(() => { registerLeaveGuard?.(next => latestLeave.current(next)); return () => registerLeaveGuard?.(null); }, [registerLeaveGuard]);
  useEffect(() => batch?.subscribe(() => changed(value => value + 1)), [batch]);
  useEffect(() => { const warn = (event: BeforeUnloadEvent) => { if (dirty || busy || pending() || batch?.snapshot().busy || batch?.snapshot().rows.some(row => row.status === "pending")) { event.preventDefault(); event.returnValue = ""; } }; window.addEventListener("beforeunload", warn); return () => window.removeEventListener("beforeunload", warn); }, [dirty, busy, batch, recovery]);
  useEffect(() => () => active.current?.abort(), []);
  async function loadSources(signal: AbortSignal) {
    try { const values = await api.sources(session, signal); if (signal.aborted || !session.isCurrent()) return; setSources(values); setAccount(old => values.some(value => value.sourceAccountCode === old) ? old : values[0]?.sourceAccountCode ?? ""); setError(""); }
    catch { if (!signal.aborted && session.isCurrent()) { setSources(null); setError("来源暂时不可用，请重新读取。"); } }
  }
  useEffect(() => { const controller = new AbortController(); void loadSources(controller.signal); return () => controller.abort(); }, [api, session]);
  const reset = () => { setFields({ legalNeedSummary: "" }); setFile(null); setMapping({}); setBatch(null); setWrite(null); setFactRef(null); sourceKey.current = crypto.randomUUID(); setMode("manual"); setError(""); };
  async function save() {
    if (!source || active.current || pending()) { setError("请先选择可用来源并核对未决结果。"); return; }
    let original: LeadCaptureWrite;
    try { original = { key: crypto.randomUUID(), body: captureBody(source, fields, sourceKey.current) }; } catch (failure) { setError((failure as Error).message); return; }
    const controller = new AbortController(); active.current = controller; setBusy(true); setError(""); setWrite(original);
    try { const result = await api.capture(session, original, controller.signal); if (session.isCurrent() && !controller.signal.aborted) { setFactRef(result.data.resultFact.factRef); setMode("success"); } }
    catch { if (session.isCurrent() && !controller.signal.aborted) { if (pending()) setMode("unknown"); else setError("本次未确认录入成功，请核对字段、来源和权限后继续。"); } }
    finally { if (active.current === controller) { active.current = null; setBusy(false); } }
  }
  async function recover() {
    if (active.current || !write) return;
    const controller = new AbortController(); active.current = controller; setBusy(true); setError("");
    try { const result = await api.receipt(session, write.key, controller.signal); if (session.isCurrent() && !controller.signal.aborted) { if (result.data.outcome === "REJECTED") { setMode("manual"); setError("已确认本次未录入，请核对后处理。"); } else { setFactRef(result.data.resultFact.factRef); setMode("success"); } } }
    catch { if (!controller.signal.aborted) setError("结果仍未确认，请稍后核对；不会再次提交。"); }
    finally { if (active.current === controller) { active.current = null; setBusy(false); } }
  }
  async function chooseFile(selected: File) {
    if (active.current || pending()) return;
    const controller = new AbortController(); active.current = controller; setBusy(true); setError("");
    try { const value = await readLeadImportFile(selected, controller.signal); if (!session.isCurrent() || controller.signal.aborted) return;
      const initial: LeadImportMapping = {};
      for (const [key, label] of Object.entries(importLabels)) if (value.headers.includes(label)) initial[key as LeadImportField] = label;
      setFile({ name: selected.name, value }); setMapping(initial); setMode("preview");
    } catch (failure) { if (!controller.signal.aborted) setError((failure as Error).message); }
    finally { if (active.current === controller) { active.current = null; setBusy(false); } }
  }
  let preview: LeadImportRow[] = [], previewError = "";
  if (file && mode === "preview") try { preview = file.value.preview(mapping); } catch (failure) { previewError = (failure as Error).message; }
  async function submitBatch() {
    if (!source || active.current || pending() || previewError || !preview.some(row => !row.errors.length)) return;
    const now = new Date().toISOString().replace(/Z$/, "000Z");
    const next = new LeadImportBatch(session.actorScopeKey, preview.map(row => row.errors.length ? { rowNumber: row.rowNumber, errors: row.errors.map(e => e.message) } : {
      rowNumber: row.rowNumber, write: { key: crypto.randomUUID(), body: { sourceAccountCode: source.sourceAccountCode, sourceChannelCode: source.sourceChannelCode, serviceCategoryCode: source.serviceCategoryCode, jurisdictionCode: source.jurisdictionCode, urgencyCode: source.urgencyCode, capturedAt: now, ...row.values, sourceRecordKey: row.values.sourceRecordKey!, legalNeedSummary: row.values.legalNeedSummary! } }
    }), api, recovery);
    const controller = new AbortController(); active.current = controller; setBusy(true); setBatch(next); setMode("result");
    try { await next.submit(session, controller.signal); } catch { if (session.isCurrent() && !controller.signal.aborted) setError("导入已暂停，请按逐条结果核对后继续。"); } finally { if (active.current === controller) { active.current = null; setBusy(false); } }
  }
  if (!session.isCurrent() || session.selectedOnBehalfAppointmentId !== null) return <p role="alert">请使用当前本人任职重新进入录入页面。</p>;
  const sourceField = <label className="identity-field">来源<select aria-label="来源" value={account} onChange={e => setAccount(e.target.value)} disabled={busy}>{sources?.map(item => <option key={item.sourceAccountCode} value={item.sourceAccountCode}>{item.displayName}</option>)}</select></label>;
  let body: ReactNode;
  if (!sources) body = <section className="identity-state"><p>{error || "正在读取可用来源…"}</p>{error && <button onClick={() => { const c = new AbortController(); active.current?.abort(); active.current = c; void loadSources(c.signal).finally(() => { if (active.current === c) active.current = null; }); }}>重新读取来源</button>}</section>;
  else if (!sources.length) body = <section className="identity-state"><h1>当前没有可用的录入来源</h1><p>请确认当前任职，或联系有权人员核对来源与录入权限。</p><button onClick={() => { const c = new AbortController(); active.current?.abort(); active.current = c; void loadSources(c.signal).finally(() => { if (active.current === c) active.current = null; }); }}>刷新来源</button></section>;
  else if (mode === "unknown") body = <section className="identity-detail-panel"><h1>录入结果待确认</h1><p>请核对原请求，不要再次保存或重复导入。</p><button className="identity-primary" onClick={() => void recover()} disabled={busy}>{busy ? "正在核对…" : "核对提交结果"}</button></section>;
  else if (mode === "success") body = <section className="identity-detail-panel"><h1>线索已录入</h1><p>{write?.body.customerName || write?.body.contactName || "本次线索"}</p><p>录入结果已确认，无需再次保存。后续可能进入去重、补齐、分配或联系，请以实际待办显示为准。</p><div className="identity-form-actions"><button onClick={onReturn}>{returnLabel??"前往我的待办"}</button><button onClick={reset}>继续录入</button></div>{factRef && onOpenTask && <LeadCaptureFollowup session={session} factRef={factRef} load={api.followup} onOpen={taskId => leave(() => onOpenTask(taskId))} />}</section>;
  else if (mode === "result" && batch) body = <LeadImportResults batch={batch} session={session} onReturn={() => leave(reset)} returnLabel="返回录入入口" followup={api.followup} onOpenTask={onOpenTask ? taskId => leave(() => onOpenTask(taskId)) : undefined} />;
  else if (mode === "file" || mode === "preview") body = <><header className="identity-page-heading"><div><h1>导入线索</h1><p>选择文件 → 核对预览 → 查看结果</p></div></header><section className="identity-detail-panel">{sourceField}{mode === "file" ? <><p>CSV / XLSX，每次一个文件，最多 1 MB、200 条记录。XLSX 仅支持一个工作表，不支持公式、宏或合并单元格。</p><label className="identity-field">选择文件<input aria-label="选择文件" type="file" accept=".csv,.xlsx" disabled={busy} onChange={e => { const selected = e.target.files?.[0]; if (selected) void chooseFile(selected); e.target.value = ""; }} /></label><p className="identity-form-help">来源记录号与需求描述必填；手机号需带国家或地区代码。文件在本页核对，确认后才提交。</p></> : <><h2>{file?.name}</h2><div className="lead-import-mapping">{Object.entries(importLabels).map(([key, label]) => <label className="identity-field" key={key}>{label}<select aria-label={`映射${label}`} value={mapping[key as LeadImportField] ?? ""} onChange={e => setMapping(old => { const next = { ...old }; if (e.target.value) next[key as LeadImportField] = e.target.value; else delete next[key as LeadImportField]; return next; })}><option value="">请选择文件列{!["sourceRecordKey", "legalNeedSummary"].includes(key) ? "（可不映射）" : ""}</option>{file?.value.headers.map(header => <option key={header}>{header}</option>)}</select></label>)}</div>{previewError ? <p role="alert">{previewError}</p> : <><p>可导入 {preview.filter(row => !row.errors.length).length} 条 · 需修正 {preview.filter(row => row.errors.length).length} 条</p><table className="identity-table lead-preview-table"><thead><tr><th>来源行</th><th>客户／联系人</th><th>检查结果</th></tr></thead><tbody>{preview.map(row => <tr key={row.rowNumber}><td data-label="来源行">{row.rowNumber}</td><td data-label="客户／联系人">{row.values.customerName || row.values.contactName || row.values.capturedName || "未填写"}</td><td data-label="检查结果">{row.errors.map(e => e.message).join("；") || "检查通过"}</td></tr>)}</tbody></table></>}<div className="identity-form-actions"><button className="identity-primary" disabled={busy || !!previewError || !preview.some(row => !row.errors.length)} onClick={() => void submitBatch()}>确认导入 {preview.filter(row => !row.errors.length).length} 条</button><button onClick={() => leave(() => { setFile(null); setMode("file"); })}>更换文件</button></div></>}</section></>;
  else body = <><header className="identity-page-heading"><div><h1>新增线索</h1><p>先录入联系信息和需求，业务分类在案件形成后完成。</p></div></header><div className="identity-page-grid"><form className="identity-detail-panel" id="lead-capture" onSubmit={e => { e.preventDefault(); void save(); }}><fieldset disabled={busy} className="lead-capture-fields">{sourceField}{([['contactName','联系人'],['phone','手机号'],['customerName','客户名称']] as const).map(([key,label]) => <label className="identity-field" key={key}>{label}<input aria-label={label} value={fields[key] ?? ""} onChange={e => setFields(old => ({ ...old, [key]: e.target.value }))} maxLength={key === "phone" ? 30 : 200} /></label>)}<p className="identity-form-help">中国大陆手机号可直接填写；其他号码需带国家或地区代码。</p><label className="identity-field">需求描述<textarea aria-label="需求描述" required maxLength={2000} value={fields.legalNeedSummary} onChange={e => setFields(old => ({ ...old, legalNeedSummary: e.target.value }))} /></label></fieldset></form><aside className="identity-detail-panel"><h2>从材料提取字段</h2><p>字段提取尚未接入，当前可继续手工填写。</p><p className="identity-form-help">AI 候选接入后仍需人工核对，不会自动保存业务信息。</p></aside></div><div className="identity-form-actions lead-capture-actions"><p className="identity-form-help">缺少联系方式时进入补齐流程。</p><button disabled={busy} onClick={() => leave(() => { setFields({ legalNeedSummary: "" }); setMode("file"); })}>批量导入</button><button className="identity-primary" type="submit" form="lead-capture" disabled={busy || !source}>{busy ? "正在保存…" : "保存线索"}</button></div></>;
  return <><div className="identity-admin-shell lead-intake-shell" inert={discard !== null || recoveryDialog !== null}><header className="identity-admin-header"><div className="brand"><Scales size={30} aria-hidden="true" /><span>律所工作助手</span></div>{sessionActions}</header><div className="identity-admin-body"><aside className="identity-admin-sidebar"><p>业务管理</p><nav aria-label="业务管理"><a href={leadIntakeRoute} aria-current="page" onClick={e => { e.preventDefault(); leave(reset); }}><User size={21} aria-hidden="true" />线索与客户</a></nav><button className="lead-return" onClick={() => leave(onReturn)}>{returnLabel??"返回工作台"}</button></aside><main className="identity-admin-main" aria-label="线索录入" aria-busy={busy}>{body}{error && sources && <p className="identity-command-feedback" role="alert">{error}</p>}{pending() && onRecover && <button disabled={busy || !!batch?.snapshot().busy} onClick={event => { if (!active.current && session.isCurrent()) setRecoveryDialog(event.currentTarget); }}>前往恢复入口</button>}</main></div></div>{discard && <IdentityDialog title="放弃本次未保存内容？" locked={false} onCancel={() => setDiscard(null)} returnFocus={discard.trigger}><p>未提交的表单与预览只保留在当前页面。已录入记录不受影响。</p><div className="identity-form-actions"><button onClick={() => setDiscard(null)}>继续填写</button><button className="identity-primary" onClick={() => { const next = discard.next; setDiscard(null); next(); }}>放弃并返回</button></div></IdentityDialog>}{recoveryDialog && <IdentityDialog title="离开本页并核对恢复线索？" locked={busy || !!batch?.snapshot().busy} returnFocus={recoveryDialog} onCancel={() => setRecoveryDialog(null)}><p>离开后，本页原请求正文、未保存输入及导入未提交行将丢失。原恢复线索保持不变，只能查询原回执，不会自动重发。</p><p>离开不代表取消原操作。已录入记录不会撤销。</p><div className="identity-form-actions"><button onClick={() => setRecoveryDialog(null)}>留在本页</button><button onClick={() => { if (!active.current && !busy && !batch?.snapshot().busy && session.isCurrent()) { setRecoveryDialog(null); onRecover?.(); } }}>确认前往恢复</button></div></IdentityDialog>}</>;
}
