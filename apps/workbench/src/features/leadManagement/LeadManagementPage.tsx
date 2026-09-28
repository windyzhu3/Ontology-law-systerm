import {useEffect,useRef,useState,type ReactNode} from 'react';
import {leadStates,type LeadManagementTransport,type LeadPage,type LeadDetail,type LeadSource,type LeadQuery} from '../../lib/leadManagementTransport';
import {TransportError,type WorkbenchSession} from '../../lib/sessionTransport';
import {readWithDeadline} from '../../lib/readDeadline';
import {BusinessNavigation} from '../workcard/BusinessNavigation';
import master from '../opportunities/ledgerMaster.css?inline';
import scales from '../opportunities/assets/Scales-green.svg';
export type LeadManagementProps={session:WorkbenchSession;api:LeadManagementTransport;onTask:(id:string)=>void|Promise<void>;sessionActions?:ReactNode;onTasks?:()=>void;onOpportunities?:()=>void;onContracts?:()=>void;onTeam?:()=>void;onOverview?:()=>void;onIntake?:()=>void};
const businessTime=new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hourCycle:'h23'});
function time(value:string){return /^\d{4}-\d{2}-\d{2}T/.test(value)&&!Number.isNaN(Date.parse(value))?businessTime.format(new Date(value)):value;}
function SessionPage(p:LeadManagementProps){
 const {session,api}=p;
 const [view,setView]=useState<'leads'|'sources'>('leads'),[query,setQuery]=useState<LeadQuery>({}),[refresh,setRefresh]=useState(0),[selected,setSelected]=useState<string|null>(null),[selectedSource,setSelectedSource]=useState<string|null>(null);
 const [page,setPage]=useState<LeadPage|null>(null),[detail,setDetail]=useState<LeadDetail|null>(null),[sources,setSources]=useState<LeadSource[]>([]),[owners,setOwners]=useState<Record<string,string>>({});
 const [error,setError]=useState(''),[denied,setDenied]=useState(false),[loading,setLoading]=useState(true),[busy,setBusy]=useState(false);
 const alive=useRef(true),actionRead=useRef<AbortController|null>(null),listPane=useRef<HTMLElement>(null);
 const key=JSON.stringify([view,query,refresh]);const current=useRef({session,key,selected});current.current={session,key,selected};
 const valid=()=>alive.current&&session.isCurrent()&&current.current.session===session&&current.current.key===key;
 useEffect(()=>{alive.current=true;const style=document.createElement('style');style.textContent=master;document.head.append(style);return()=>{alive.current=false;actionRead.current?.abort();style.remove();};},[]);
 function clear(){setPage(null);setDetail(null);setSelected(null);setSources([]);setOwners({});}
 function failed(e:unknown){clear();setDenied(e instanceof TransportError&&[401,403,404].includes(e.status));setError('本次读取失败，请重新查询。');setLoading(false);}
 useEffect(()=>{
  const controller=new AbortController();let live=true;setPage(null);setDetail(null);setSelected(null);setError('');setDenied(false);setLoading(true);
  const timer=window.setTimeout(()=>{
   void Promise.all([readWithDeadline(signal=>api.sources(session,signal),controller.signal),view==='leads'?readWithDeadline(signal=>api.list(session,query,signal),controller.signal):Promise.resolve(null)]).then(([catalog,result])=>{
    if(!live||!valid())return;setSources(catalog.items);setSelectedSource(previous=>catalog.items.some(s=>s.code===previous)?previous:catalog.items[0]?.code??null);setPage(result);
    if(result)setOwners(previous=>{const next={...previous};for(const row of result.items)if(row.ownerId)next[row.ownerId]=row.ownerLabel;return next;});setLoading(false);
   }).catch(e=>{if(live&&valid())failed(e);});
  },query.search?250:0);
  return()=>{live=false;window.clearTimeout(timer);controller.abort();};
 },[session,api,key]);
 useEffect(()=>{
  const controller=new AbortController();let live=true;setDetail(null);
  if(selected&&view==='leads')void readWithDeadline(signal=>api.detail(session,selected,signal),controller.signal).then(value=>{if(live&&valid())setDetail(value);}).catch(e=>{if(live&&valid())failed(e);});
  return()=>{live=false;controller.abort();};
 },[session,api,key,selected]);
 function filter(name:keyof LeadQuery,value:string){setSelected(null);setDetail(null);setQuery(previous=>({...previous,[name]:value||undefined,cursor:undefined}));}
 async function handle(){const id=selected;if(!id||busy||!valid())return;setBusy(true);const controller=new AbortController();actionRead.current=controller;
  try{const fresh=await readWithDeadline(signal=>api.detail(session,id,signal),controller.signal);if(!valid()||current.current.selected!==id)return;if(!fresh.action||!fresh.taskId)throw Error('changed');await p.onTask(fresh.taskId);}
  catch{if(valid()){clear();setError('当前事项已变化，请重新查询后办理。');}}
  finally{if(actionRead.current===controller)actionRead.current=null;if(alive.current)setBusy(false);}
 }
 if(!session.isCurrent())return null;
 const rows=!denied&&!loading&&!error?page?.items??[]:[];
 const shown=!denied&&!loading&&!error&&detail?.id===selected&&rows.some(r=>r.id===selected)?detail:null;
 const source=!denied&&!loading&&!error?sources.find(s=>s.code===selectedSource):null;
 return <><header className="app-header"><div className="brand"><img className="icon" src={scales} alt="" aria-hidden="true"/>律所工作助手</div><div className="session">{p.sessionActions}</div></header>
 <div className="admin-shell"><BusinessNavigation onOverview={p.onOverview} active={view} onLeads={()=>setView('leads')} onSources={()=>setView('sources')} onTasks={p.onTasks} onOpportunities={p.onOpportunities} onContracts={p.onContracts} onTeam={p.onTeam}/><main className="admin-main">
 <h1>{view==='leads'?'客户与线索':'来源与责任'}</h1><p>{view==='leads'?'查询当前有权客户与线索；同一客户的不同接入分别保留。时间按北京时间显示。':'查看有权来源、当前责任范围及分配结果。'}</p>
 {denied?<p role="alert">查看权限已变化，列表与详情已清除。</p>:error?<p role="alert">{error}</p>:null}
 {view==='leads'?<>
 <div className="toolbar"><label className="field"><span className="field-label">客户或联系方式</span><input value={denied?'':query.search??''} disabled={denied||busy} onChange={e=>filter('search',e.target.value)}/></label><label className="field"><span className="field-label">处理状态</span><select value={denied?'':query.state??''} disabled={denied||busy} onChange={e=>filter('state',e.target.value)}><option value="">全部状态</option>{Object.entries(leadStates).map(([code,label])=><option key={code} value={code}>{label}</option>)}</select></label><label className="field"><span className="field-label">来源</span><select value={denied?'':query.source??''} disabled={denied||busy} onChange={e=>filter('source',e.target.value)}><option value="">有权来源</option>{sources.map(s=><option key={s.code} value={s.code}>{s.label}</option>)}</select></label><label className="field"><span className="field-label">负责人</span><select value={denied?'':query.owner??''} disabled={denied||busy} onChange={e=>filter('owner',e.target.value)}><option value="">有权责任人</option>{Object.entries(owners).map(([id,label])=><option key={id} value={id}>{label}</option>)}</select></label></div>
 <div className="ledger-split"><section className="list-pane" ref={listPane} aria-label="线索列表">{rows.length?<table className="record-table"><thead><tr><th>客户与接入</th><th>当前责任</th></tr></thead><tbody>{rows.map(r=><tr key={r.id} className={r.id===selected?'selected':undefined}><td><button aria-current={r.id===selected} disabled={busy} onClick={()=>setSelected(r.id)}>{r.customerLabel}</button><small>{r.sourceLabel} · {r.stateLabel}</small></td><td>{r.ownerLabel}<small>{time(r.capturedAt)}</small></td></tr>)}</tbody></table>:!error&&!denied?<p role="status">{loading?'正在读取当前有权线索…':'当前筛选下没有有权记录，可继续翻页核对。'}</p>:null}
 {!denied&&<><button className="link-button" disabled={loading||busy} onClick={()=>{setQuery(q=>({...q,cursor:undefined}));setRefresh(n=>n+1);}}>重新查询</button>{Object.values(query).some(Boolean)&&<button className="link-button" disabled={busy} onClick={()=>setQuery({})}>清除筛选</button>}{!loading&&page?.nextCursor&&<button className="link-button" disabled={busy} onClick={()=>setQuery(q=>({...q,cursor:page.nextCursor!}))}>下一页</button>}{query.cursor&&<button className="link-button" disabled={busy} onClick={()=>setQuery(q=>({...q,cursor:undefined}))}>返回第一页</button>}</>}
 <p className="help">负责人选项来自本次已读取的有权记录；可翻页继续查找。</p>{p.onIntake&&<button className="link-button" onClick={p.onIntake}>录入线索与本次结果</button>}
 </section><section className="detail-pane" aria-label="线索详情">{shown?<><button className="link-button mobile-return" onClick={()=>listPane.current?.scrollIntoView()}>返回列表</button><h2>{shown.customerLabel}</h2><dl className="detail-facts">{shown.facts.map(([name,value],i)=><div key={name+':'+i}><dt>{name}</dt><dd>{time(value)}</dd></div>)}<div><dt>关联商机</dt><dd>{shown.opportunityId?'已形成商机':'尚未形成商机'}</dd></div></dl><p>{shown.nextAction}</p>{shown.action&&<div className="ledger-detail-actions"><button className="primary" disabled={busy} onClick={()=>void handle()}>{shown.action.label}</button></div>}</>:<p>{selected&&!error&&!denied?'正在核对准确线索详情…':'选择线索查看详情。'}</p>}</section></div>
 </>:<div className="ledger-split"><section className="list-pane" aria-label="来源列表">{!loading&&!denied&&!error?<table className="record-table"><thead><tr><th>来源</th><th>接入责任范围</th></tr></thead><tbody>{sources.map(s=><tr key={s.code} className={s.code===selectedSource?'selected':undefined}><td><button aria-current={s.code===selectedSource} onClick={()=>setSelectedSource(s.code)}>{s.label}</button><small>受控来源</small></td><td>{s.intakeLabel}</td></tr>)}</tbody></table>:loading?<p role="status">正在读取有权来源…</p>:null}{!loading&&!denied&&!error&&!sources.length&&<p>当前没有可见的受控来源。</p>}<button className="link-button" disabled={loading||denied} onClick={()=>setRefresh(n=>n+1)}>重新查询</button></section><section className="detail-pane" aria-label="来源详情">{source?<><h2>{source.label}</h2><dl className="detail-facts"><div><dt>接入责任范围</dt><dd>{source.intakeLabel}</dd></div><div><dt>主管责任范围</dt><dd>{source.supervisorLabel}</dd></div><div><dt>分配依据</dt><dd>{source.assignmentMode==='MANUAL'?'人工指定':'按当前有效规则自动分配'}</dd></div></dl><p>具体负责人和分配结果以各条线索的原责任为准。</p><button className="link-button" onClick={()=>{setQuery({source:source.code});setView('leads');}}>查询此来源线索</button></>:<p>选择来源查看责任范围。</p>}</section></div>}
 </main></div></>;
}
export function LeadManagementPage(props:LeadManagementProps){return <SessionPage key={`${props.session.actorScopeKey}:${props.session.identityEpoch}`} {...props}/>;}
