import {ManagementViewSelect} from './ManagementViewSelect';
import {useEffect,useRef} from 'react';
import {BusinessNavigation} from '../workcard/BusinessNavigation';
import master from '../opportunities/ledgerMaster.css?inline';
export type ManagementView='payments'|'transfer';
export type ManagementRow={id:string;customerLabel:string;basisLabel:string;stateLabel:string;ownerLabel:string};
export type ManagementDetail={id:string;customerLabel:string;facts:readonly (readonly [string,string])[];history:readonly {id:string;label:string}[];canHandle:boolean};
export type ManagementLedgerProps={view:ManagementView;views?:readonly (ManagementView|'contracts')[];rows:readonly ManagementRow[];detail:ManagementDetail|null;selectedId:string|null;search:string;state:string;states:readonly string[];loading:boolean;permitted:boolean;error?:string;nextCursor?:string|null;onView:(view:ManagementView|'contracts')=>void;onSearch:(search:string)=>void;onState:(state:string)=>void;onSelect:(id:string)=>void;onHandle:(id:string)=>void;onReload:()=>void;onNext?:()=>void;onTasks?:()=>void;onTeam?:()=>void;onLeads?:()=>void;onOverview?:()=>void;onOpportunities?:()=>void;onFirst?:()=>void;onClear?:()=>void};

/** Presentation only: the parent supplies authorized pages and independently checked detail.
 * No client-side discovery, authorization, monetary calculation, or business mutation. */
export function ManagementLedger(p:ManagementLedgerProps){
 const list=useRef<HTMLElement>(null);
 useEffect(()=>{const style=document.createElement('style');style.textContent=master;document.head.append(style);return()=>style.remove();},[]);
 const visible=p.permitted&&!p.loading&&!p.error;
 const rows=visible?p.rows:[];
 const detail=visible&&p.detail?.id===p.selectedId&&rows.some(r=>r.id===p.selectedId)?p.detail:null;
 return <div className="admin-shell"><BusinessNavigation active="contracts" onTeam={p.onTeam} onLeads={p.onLeads} onOverview={p.onOverview} onOpportunities={p.onOpportunities} onTasks={p.onTasks}/><main className="admin-main"><h1>合同台账</h1><p>查看当前有权业务；办理回到原工作卡。</p>
  <ManagementViewSelect views={p.views} value={p.view} onChange={p.onView}/>
  <div className="ledger-split"><section className="list-pane" ref={list}><div className="toolbar">
   <label className="field"><span className="field-label">搜索客户</span><input value={p.permitted?p.search:''} disabled={!p.permitted} onChange={e=>p.onSearch(e.target.value)}/></label>
   <label className="field"><span className="field-label">筛选状态</span><select value={p.permitted?p.state:''} disabled={!p.permitted} onChange={e=>p.onState(e.target.value)}><option value="">全部状态</option>{p.permitted&&p.states.map(s=><option key={s}>{s}</option>)}</select></label>
   {(p.search||p.state)&&p.onClear&&<button className="link-button" disabled={!p.permitted||p.loading} onClick={p.onClear}>清除筛选</button>}
  </div>
  {rows.length?<table className="record-table"><thead><tr><th>客户与依据</th><th>当前事项</th></tr></thead><tbody>{rows.map(r=><tr key={r.id} className={r.id===p.selectedId?'selected':undefined}><td><button aria-current={r.id===p.selectedId} onClick={()=>p.onSelect(r.id)}>{r.customerLabel}</button><small>{r.basisLabel}</small></td><td>{r.stateLabel}<small>{r.ownerLabel}</small></td></tr>)}</tbody></table>:<p role={p.error&&p.permitted?'alert':'status'}>{!p.permitted?'查看权限已变化，列表与详情已清除。':p.loading?'正在读取当前有权记录…':p.error||'暂无匹配记录。'}</p>}
  <button className="link-button" disabled={!p.permitted||p.loading} onClick={p.onReload}>重新查询</button>
  {visible&&p.nextCursor&&p.onNext&&<button className="link-button" onClick={p.onNext}>下一页</button>}
  {visible&&p.onFirst&&<button className="link-button" onClick={p.onFirst}>返回第一页</button>}
  </section><section className="detail-pane" aria-label="业务详情">
   {detail?<><button className="link-button mobile-return" onClick={()=>list.current?.scrollIntoView()}>返回列表</button><h2>{detail.customerLabel}</h2><dl className="detail-facts">{detail.facts.map(([key,value],i)=><div key={key+':'+i}><dt>{key}</dt><dd>{value}</dd></div>)}</dl><details className="history"><summary>查看处理记录与依据</summary>{detail.history.length?detail.history.map(h=><p key={h.id}>{h.label}</p>):<p>暂无可展示历史。</p>}</details>{detail.canHandle?<div className="ledger-detail-actions"><button className="primary" onClick={()=>p.onHandle(detail.id)}>前往办理</button></div>:<p>当前无可办理事项；已完成事项及历史保留。</p>}</>:<p>选择一条记录查看详情。</p>}
  </section></div>
 </main></div>;
}
