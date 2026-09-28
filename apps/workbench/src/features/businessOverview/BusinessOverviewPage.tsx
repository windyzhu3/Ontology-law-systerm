import {useEffect,useRef,useState,type ReactNode} from 'react';
import {overviewMetrics,type OverviewMetric,type OverviewSummary,type OverviewPage,type BusinessOverviewTransport} from '../../lib/businessOverviewTransport';
import {TransportError,type WorkbenchSession} from '../../lib/sessionTransport';
import {readWithDeadline} from '../../lib/readDeadline';
import {BusinessNavigation} from '../workcard/BusinessNavigation';
import master from '../opportunities/ledgerMaster.css?inline';
import scales from '../opportunities/assets/Scales-green.svg';
type Props={session:WorkbenchSession;api:BusinessOverviewTransport;sessionActions?:ReactNode;onTasks?:()=>void;onLeads?:()=>void;onOpportunities?:()=>void;onContracts?:()=>void;onTeam?:()=>void};
const formatter=new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hourCycle:'h23'});
const time=(v:string)=>formatter.format(new Date(v));
function SessionPage(p:Props){
 const {session,api}=p;const [summary,setSummary]=useState<OverviewSummary|null>(null),[page,setPage]=useState<OverviewPage|null>(null),[selection,setSelection]=useState<{metric:OverviewMetric;month:string;cursor?:string}|null>(null),[refresh,setRefresh]=useState(0),[loading,setLoading]=useState(true),[detailLoading,setDetailLoading]=useState(false),[error,setError]=useState('');
 const current=useRef(session);current.current=session;const detailPane=useRef<HTMLElement>(null);
 useEffect(()=>{const style=document.createElement('style');style.textContent=master;document.head.append(style);return()=>style.remove();},[]);
 useEffect(()=>{if(selection){detailPane.current?.focus();detailPane.current?.scrollIntoView?.({block:'start'});}},[selection?.metric]);
 function failed(e:unknown){setSummary(null);setSelection(null);setPage(null);setError(e instanceof TransportError&&[401,403].includes(e.status)?'查看权限已变化，概览与明细已清除。':'本次查询未完成，未显示旧数字或部分数字。请重新查询。');}
 useEffect(()=>{
  const controller=new AbortController();let live=true;setSummary(null);setPage(null);setSelection(null);setError('');setLoading(true);
  readWithDeadline(signal=>api.summary(session,undefined,signal),controller.signal).then(value=>{if(live&&session.isCurrent()&&current.current===session)setSummary(value);},e=>{if(live&&session.isCurrent()&&current.current===session)failed(e);}).finally(()=>{if(live)setLoading(false);});
  return()=>{live=false;controller.abort();};
 },[api,session,refresh]);
 useEffect(()=>{
  const controller=new AbortController();let live=true;setPage(null);if(!selection){setDetailLoading(false);return()=>{live=false;controller.abort();};}setDetailLoading(true);
  readWithDeadline(signal=>api.details(session,selection.metric,selection.month,selection.cursor,signal),controller.signal).then(value=>{if(live&&session.isCurrent()&&current.current===session)setPage(value);},e=>{if(live&&session.isCurrent()&&current.current===session)failed(e);}).finally(()=>{if(live)setDetailLoading(false);});
  return()=>{live=false;controller.abort();};
 },[api,session,selection]);
 const shown=session.isCurrent()?summary:null,detail=session.isCurrent()&&selection&&page?.metric===selection.metric&&page.month===selection.month?page:null;
 return <><header className="app-header"><div className="brand"><img className="icon" src={scales} alt="" aria-hidden="true"/>律所工作助手</div><div className="session">{p.sessionActions}</div></header><div className="admin-shell"><BusinessNavigation active="overview" onLeads={p.onLeads} onTasks={p.onTasks} onOpportunities={p.onOpportunities} onContracts={p.onContracts} onTeam={p.onTeam}/><main className="admin-main"><h1>经营概览</h1><p>仅统计当前有权数据。每项可核对同口径明细。</p>
 <div className="toolbar"><label className="field"><span className="field-label">业务期间（北京时间）</span><select value="current" disabled><option value="current">本月</option></select></label><button disabled={loading} onClick={()=>setRefresh(n=>n+1)}>重新查询</button></div>
 {error&&<p role="alert">{error}</p>}{loading&&<p role="status">正在核对当前有权数字…</p>}
 {shown&&<><section className="list-pane" aria-label="五项经营指标"><table className="record-table"><thead><tr><th>指标</th><th>数量与口径</th><th>核对</th></tr></thead><tbody>{shown.metrics.map(m=><tr key={m.key}><td>{m.label}</td><td><strong>{m.status==='AVAILABLE'?m.count:'不可查看'}</strong><small>{overviewMetrics[m.key].basis}</small></td><td>{m.status==='AVAILABLE'?<button aria-current={selection?.metric===m.key} onClick={()=>{setSelection({metric:m.key,month:shown.month});}}>查看明细</button>:<span>当前任职无查询资格</span>}</td></tr>)}</tbody></table></section><p className="help">本次期间：{shown.month} 月初至查询时点 {time(shown.asOf)}（北京时间）。期间数据按形成时间统计；逾期待办为当前时点存量。跟进尝试不计有效商机，签署不等于接收案件。</p></>}
 {selection&&shown&&<section className="list-pane" aria-label="同口径明细" tabIndex={-1} ref={detailPane}><h2>{overviewMetrics[selection.metric].label}明细</h2><p>{overviewMetrics[selection.metric].basis}。仅列出本次有权记录。</p>{detailLoading?<p role="status">正在重新核对本项明细…</p>:detail&&<><table className="record-table"><thead><tr><th>客户</th><th>计数依据</th><th>{selection.metric==='overdueTasks'?'原到期时间':'形成时间'}</th></tr></thead><tbody>{detail.items.map(r=><tr key={r.id}><td>{r.customerLabel}</td><td>{r.stateLabel}</td><td>{time(r.occurredAt)}</td></tr>)}</tbody></table>{!detail.items.length&&<p>本页没有当前有权记录{detail.nextCursor?'，可继续翻页核对。':'。'}</p>}<p className="help">明细查询时点：{time(detail.asOf)}（北京时间）。权限、记录或责任变化后，重新查询概览以核对最新数字。</p>{detail.nextCursor&&<button onClick={()=>setSelection({...selection,cursor:detail.nextCursor!})}>下一页</button>}{selection.cursor&&<button onClick={()=>setSelection({...selection,cursor:undefined})}>返回第一页</button>}</>}</section>}
 </main></div></>;
}
export function BusinessOverviewPage(p:Props){return <SessionPage key={`${p.session.actorScopeKey}:${p.session.identityEpoch}`} {...p}/>;}
