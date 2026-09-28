import { useEffect, useState } from "react";
import type { WorkbenchSession } from "../../lib/sessionTransport";
import type { components } from "../../generated/api/schema";
type Task = components["schemas"]["NextSummary"];
type Props = { session: WorkbenchSession; factRef: string; load: (session: WorkbenchSession, factRef: string, signal: AbortSignal) => Promise<Task[]>; onOpen: (taskId: string) => void };
export function LeadCaptureFollowup(props: Props) {
 return <Followup key={`${props.session.identityEpoch}:${props.session.actorScopeKey}:${props.factRef}`} {...props} />;
}
function Followup({ session, factRef, load, onOpen }: Props) {
 const [tasks, setTasks] = useState<Task[] | null>(null), [failed, setFailed] = useState(false), [attempt, setAttempt] = useState(0);
 useEffect(() => {
  const controller = new AbortController(); setTasks(null); setFailed(false);
  void load(session, factRef, controller.signal).then(values => { if (!controller.signal.aborted && session.isCurrent()) setTasks(values); }).catch(() => { if (!controller.signal.aborted && session.isCurrent()) setFailed(true); });
  return () => controller.abort();
 }, [session, factRef, load, attempt]);
 if (!session.isCurrent()) return null;
 return <section className="lead-capture-followup" aria-label="实际后续事项" aria-live="polite"><h2>后续事项</h2>
  {failed ? <><p>线索录入已确认，后续事项暂时无法读取。无需再次录入。</p><button onClick={() => setAttempt(value => value + 1)}>重新读取后续事项</button></> : tasks === null ? <p>正在读取当前任职可处理的后续事项…</p> : tasks.length === 0 ? <><p>当前任职暂无此线索的可处理事项。</p><p className="identity-form-help">这不表示业务已结束；事项可能正在衔接、等待或由其他负责人处理。</p><button onClick={() => setAttempt(value => value + 1)}>重新读取后续事项</button></> : <>{tasks.map((task, index) => <div key={task.taskId}><p>{task.businessPurpose.label}</p><p>{task.timeHint}</p><button className={index === 0 ? "identity-primary" : undefined} onClick={() => { if (session.isCurrent()) onOpen(task.taskId); }}>继续办理：{task.businessPurpose.label}</button></div>)}<p className="identity-form-help">进入后将重新核对当前权限和事项状态。</p></>}
 </section>;
}
