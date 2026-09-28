import {MyTasksControl} from './MyTasksControl';
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { IdentityDialog } from "../identity/IdentityActionConfirmation";
import type { Schema } from "./contract";
import "../../styles/identity-admin.css";
export function MyTasks({tasks, recommendedTaskId, selectedTaskId, dirty, blocked, select, saveDraft, onDialogChange, openRequest=0}: {
  tasks: Schema["NextSummary"][];
  openRequest?: number;
  recommendedTaskId?: string | null;
  selectedTaskId?: string;
  dirty: boolean;
  blocked: boolean;
  select: (taskId: string | null) => void;
  saveDraft?: () => Promise<boolean>;
  onDialogChange?: (open: boolean) => void;
}) {
  const queue=useRef<HTMLDetailsElement>(null);
  useLayoutEffect(()=>{if(openRequest>0&&queue.current)queue.current.open=true;},[openRequest]);
  const [pending,setPending] = useState<{taskId: string | null; trigger: HTMLElement | null} | null>(null);
  const [saving,setSaving] = useState(false), [error,setError] = useState("");
  const saveLock = useRef(false), alive = useRef(true);
  useEffect(() => {alive.current=true; return () => {alive.current=false;};}, []);
  useEffect(() => {onDialogChange?.(!!pending); return () => onDialogChange?.(false);}, [!!pending,onDialogChange]);
  const choose = (taskId: string | null) => {
    if (blocked || saveLock.current) return;
    if (dirty) {setError(""); setPending({taskId,trigger: document.activeElement instanceof HTMLElement ? document.activeElement : null}); return;}
    select(taskId);
  };
  const saveAndSwitch = async () => {
    if (!pending || !saveDraft || blocked || saveLock.current) return;
    saveLock.current=true; setSaving(true); setError("");
    try {
      if (await saveDraft()) {if (alive.current) {select(pending.taskId);setPending(null);}}
      else if (alive.current) setError("草稿尚未确认保存，请留在当前事项核对。未切换待办。");
    } catch {if (alive.current) setError("暂时无法保存，请留在当前事项核对。");}
    finally {saveLock.current=false; if (alive.current) setSaving(false);}
  };
  return <>
    <details ref={queue} className="my-tasks" inert={!!pending}>
      <MyTasksControl summary count={tasks.length}/>
      <button className="refresh-button" disabled={blocked} onClick={() => choose(null)}>回到系统推荐</button>
      {tasks.length === 0 ? <p className="muted">当前没有可选择的待办。</p> : <ul>{tasks.map((task,index) => <li key={task.taskId}>
        <button className="refresh-button" aria-current={task.taskId === selectedTaskId ? "true" : undefined} disabled={blocked || task.taskId === selectedTaskId} onClick={() => choose(task.taskId)}>{task.subjectTitle ?? `${index+1}.`} · {task.businessPurpose.label}{task.taskId === recommendedTaskId ? " · 系统推荐" : ""}{task.taskId === selectedTaskId ? " · 当前" : ""}</button><span className="muted">{task.timeHint}</span>
      </li>)}</ul>}
    </details>
    {pending && createPortal(<IdentityDialog title="保留当前填写内容？" returnFocus={pending.trigger} locked={saving} onCancel={() => setPending(null)}>
      <p>{saveDraft?'当前事项尚有未保存内容。保存草稿后可以继续处理其他事项。':'当前事项尚有未提交内容，可继续编辑，或放弃后切换。'}</p>
      {error && <p role="alert">{error}</p>}
      <div className="identity-form-actions"><button disabled={saving} onClick={() => setPending(null)}>继续编辑</button><button disabled={blocked || saving} onClick={() => {select(pending.taskId);setPending(null);}}>放弃未保存内容并切换</button>{saveDraft && <button className="identity-primary" disabled={blocked || saving} onClick={() => void saveAndSwitch()}>{saving ? "正在保存草稿…" : "保存草稿并切换"}</button>}</div>
    </IdentityDialog>,document.body)}
  </>;
}
