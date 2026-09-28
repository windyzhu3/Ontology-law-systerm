import {useEffect,useRef} from 'react';
import {BusinessNavigation} from '../workcard/BusinessNavigation';
import master from '../opportunities/ledgerMaster.css?inline';
export type TeamView='tasks'|'waiting'|'exceptions'|'history';
export type TeamRow={id:string;customerLabel:string;purposeLabel:string;stateLabel:string;ownerLabel:string;timeLabel:string};
export type TeamDetail={id:string;customerLabel:string;facts:readonly (readonly [string,string])[];history:readonly {id:string;label:string}[];nextAction:string;action:null|{kind:'task'|'exception';label:string}};
export type TeamLedgerProps={view:TeamView;rows:readonly TeamRow[];detail:TeamDetail|null;selectedId:string|null;search:string;state:string;states:readonly string[];loading:boolean;permitted:boolean;error?:string;nextCursor?:string|null;onView:(v:TeamView)=>void;onSearch:(v:string)=>void;onState:(v:string)=>void;onSelect:(id:string)=>void;onHandle:(id:string)=>void;onReload:()=>void;onClear:()=>void;onNext?:()=>void;onFirst?:()=>void;onTasks?:()=>void;onLeads?:()=>void;onOverview?:()=>void;onOpportunities?:()=>void;onContracts?:()=>void};
const views:Record<TeamView,string>={tasks:'团队待办',waiting:'等待事项',exceptions:'责任与衔接异常',history:'已处理事项'};
const businessTime=new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hourCycle:'h23'});
function timeLabel(value:string){
 const match=/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})/.exec(value);
 if(!match)return value;
 const instant=new Date(match[0]);return Number.isNaN(instant.getTime())?value:businessTime.format(instant)+value.slice(match[0].length);
}
/** Frozen S presentation. All facts/actions are supplied by an authorized read; no state inference. */
export function TeamLedger(p:TeamLedgerProps){
 const list=useRef<HTMLElement>(null);
 useEffect(()=>{const style=document.createElement('style');style.textContent=master;document.head.append(style);return()=>style.remove();},[]);
 const visible=p.permitted&&!p.loading&&!p.error,rows=visible?p.rows:[];
 const detail=visible&&p.detail?.id===p.selectedId&&rows.some(r=>r.id===p.selectedId)?p.detail:null;
 return <div className="admin-shell"><BusinessNavigation active="team" onOpportunities={p.onOpportunities} onContracts={p.onContracts} onTasks={p.onTasks} onLeads={p.onLeads} onOverview={p.onOverview}/><main className="admin-main"><h1>团队待办</h1><p>查看授权范围内的责任事项；办理回到原工作卡。时间按北京时间显示。</p>
 <label className="field"><span className="field-label">查看内容</span><select aria-label="查看内容" value={p.view} onChange={e=>p.onView(e.target.value as TeamView)}>{Object.entries(views).map(([k,v])=><option key={k} value={k}>{v}</option>)}</select></label>
 <div className="ledger-split"><section className="list-pane" ref={list} aria-label="团队事项列表"><div className="toolbar"><label className="field"><span className="field-label">搜索客户或责任人</span><input value={p.permitted?p.search:''} disabled={!p.permitted} onChange={e=>p.onSearch(e.target.value)}/></label><label className="field"><span className="field-label">筛选状态</span><select value={p.permitted?p.state:''} disabled={!p.permitted} onChange={e=>p.onState(e.target.value)}><option value="">全部状态</option>{p.permitted&&p.states.map(s=><option key={s}>{s}</option>)}</select></label></div>
 {rows.length?<table className="record-table"><thead><tr><th>客户与事项</th><th>责任与期限</th></tr></thead><tbody>{rows.map(r=><tr key={r.id} className={r.id===p.selectedId?'selected':undefined}><td><button aria-current={r.id===p.selectedId} onClick={()=>p.onSelect(r.id)}>{r.customerLabel}</button><small>{r.purposeLabel} · {r.stateLabel}</small></td><td>{r.ownerLabel}<small>{timeLabel(r.timeLabel)}</small></td></tr>)}</tbody></table>:<p role={p.error&&p.permitted?'alert':'status'}>{!p.permitted?'查看权限已变化，列表与详情已清除。':p.loading?'正在读取当前有权事项…':p.error||'当前筛选下没有事项，不代表全部业务已完成。'}</p>}
 <button className="link-button" disabled={!p.permitted||p.loading} onClick={p.onReload}>重新查询</button>{p.permitted&&(p.search||p.state)&&<button className="link-button" disabled={p.loading} onClick={p.onClear}>清除筛选</button>}{visible&&p.nextCursor&&p.onNext&&<button className="link-button" onClick={p.onNext}>下一页</button>}{visible&&p.onFirst&&<button className="link-button" onClick={p.onFirst}>返回第一页</button>}
 </section><section className="detail-pane" aria-label="当前事项详情">{detail?<><button className="link-button mobile-return" onClick={()=>list.current?.scrollIntoView()}>返回列表</button><h2>{detail.customerLabel}</h2><dl className="detail-facts">{detail.facts.map(([key,value],i)=><div key={key+':'+i}><dt>{key}</dt><dd>{/时间|期限|日期/.test(key)?timeLabel(value):value}</dd></div>)}</dl><p>{detail.nextAction}</p><details className="history"><summary>查看处理记录与依据</summary>{detail.history.length?detail.history.map(h=><p key={h.id}>{timeLabel(h.label)}</p>):<p>暂无可展示历史。</p>}</details>{detail.action?<div className="ledger-detail-actions"><button className="primary" onClick={()=>p.onHandle(detail.id)}>{detail.action.label}</button></div>:<p>当前没有由本任职办理的操作。查看记录不改变业务状态。</p>}</>:<p>选择一项查看责任、下一行动和处理记录。</p>}</section></div></main></div>;
}
