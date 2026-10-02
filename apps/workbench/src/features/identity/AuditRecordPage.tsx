import {useEffect,useState} from 'react';
import type {WorkbenchSession} from '../../lib/sessionTransport';
import type {AuditRecordApi,AuditRecord,AuditQuery,AuditPage,AuditRelation} from './auditRecordApi';
import {useAuditRecordQuery} from './useAuditRecordQuery';
import {DetailRow,DetailRows,InfoNote,IdentityActions} from './IdentityListPage';
import {formatIdentityInstant} from './identityLabels';
const localMinute=(date:Date)=>`${date.getFullYear()}-${String(date.getMonth()+1).padStart(2,'0')}-${String(date.getDate()).padStart(2,'0')}T${String(date.getHours()).padStart(2,'0')}:${String(date.getMinutes()).padStart(2,'0')}`;
const recentWindow=(days:number)=>{const end=new Date(Math.floor(Date.now()/60000)*60000);return {start:localMinute(new Date(end.getTime()-days*86400000)),end:localMinute(end)};};
export function AuditRecordPage({session,api}:{session:WorkbenchSession;api:AuditRecordApi}){
 const [search,setSearch]=useState(''),[range,setRange]=useState('7'),[scope,setScope]=useState(''),[result,setResult]=useState('');
 const [dates,setDates]=useState(()=>recentWindow(7)),[filterError,setFilterError]=useState('');
 const [query,setQuery]=useState<AuditQuery>({limit:20});const list=useAuditRecordQuery(session,api,query);
 const [selection,setSelection]=useState<{key:string;id:string}|null>(null);
 const selected=selection?.key===list.key?selection.id:list.page?.items[0]?.id;
 const detailKey=`${list.key}:${selected??''}`;
 const [detail,setDetail]=useState<{key:string;record?:AuditRecord;error?:string}>({key:''});
 const [chain,setChain]=useState<{key:string;relation:AuditRelation;cursors:(string|undefined)[];index:number}|null>(null);
 const [related,setRelated]=useState<{key:string;page?:AuditPage;error?:string}>({key:''});
 useEffect(()=>{const abort=new AbortController();setDetail({key:detailKey});if(selected)void api.detail(session,selected,abort.signal).then(record=>{if(!abort.signal.aborted&&session.isCurrent())setDetail({key:detailKey,record});},()=>{if(!abort.signal.aborted&&session.isCurrent())setDetail({key:detailKey,error:'详情暂时不可用，请重读后再试。'});});return()=>abort.abort();},[session,api,detailKey,selected]);
 const chainKey=chain?.key===detailKey?`${detailKey}:${chain.relation}:${chain.index}`:'';
 useEffect(()=>{const abort=new AbortController();setRelated({key:chainKey});if(chainKey&&chain&&selected)void api.related(session,selected,chain.relation,{...query,limit:20,...(chain.cursors[chain.index]?{cursor:chain.cursors[chain.index]}:{})},abort.signal).then(page=>{if(!abort.signal.aborted&&session.isCurrent())setRelated({key:chainKey,page});},()=>{if(!abort.signal.aborted&&session.isCurrent())setRelated({key:chainKey,error:'关系链暂时不可用，请重新查询。'});});return()=>abort.abort();},[session,api,chainKey]);
 const record=detail.key===detailKey?detail.record:undefined,chainPage=related.key===chainKey?related.page:undefined;
 function apply(){const start=new Date(dates.start),end=new Date(dates.end);if(!Number.isFinite(start.getTime())||!Number.isFinite(end.getTime())||start>=end||end.getTime()-start.getTime()>31*86400000||end.getTime()>Date.now()){setFilterError('请选择有效起止时间，范围最多 31 天，结束时间不能晚于现在。');return;}setFilterError('');setQuery({limit:20,start:start.toISOString(),end:end.toISOString(),...(search.trim()?{search:search.trim()}:{}),...(scope?{scope:scope as AuditQuery['scope']}:{}) ,...(result?{result:result as AuditQuery['result']}:{})});setSelection(null);setChain(null);}

 return <>
  <section className="identity-page-heading"><div><h1>审计记录</h1><p>查询当前任职获权范围内的操作记录与已有关系链</p></div></section>
  <form className="audit-filters" onSubmit={e=>{e.preventDefault();apply();}}>
   <label>搜索<input type="search" maxLength={100} placeholder="人员、任职或操作类别" value={search} onChange={e=>setSearch(e.target.value)}/></label>
   <label>时间范围<select value={range} onChange={e=>{setRange(e.target.value);if(e.target.value!=="custom")setDates(recentWindow(Number(e.target.value)));}}><option value="7">最近 7 天</option><option value="1">最近 24 小时</option><option value="31">最近 31 天</option><option value="custom">自定义时间</option></select></label>
   <label>开始时间<input type="datetime-local" required value={dates.start} onChange={e=>{setDates({...dates,start:e.target.value});setRange("custom");}}/></label>
   <label>结束时间<input type="datetime-local" required value={dates.end} onChange={e=>{setDates({...dates,end:e.target.value});setRange("custom");}}/></label>
   <label>审计范围<select value={scope} onChange={e=>setScope(e.target.value)}><option value="">全部获权范围</option><option value="TENANT">事务所</option><option value="ORGANIZATION">组织</option><option value="OBJECT">对象</option><option value="SECURITY">安全</option></select></label>
   <label>结果<select value={result} onChange={e=>setResult(e.target.value)}><option value="">全部结果</option><option value="SUCCEEDED">成功</option><option value="NO_CHANGE">无变更</option><option value="REJECTED">已拒绝</option><option value="FAILED">失败</option></select></label>
   <button type="submit" disabled={list.loading}>查询</button>
  </form>
  {filterError&&<p className="identity-state" role="alert">{filterError}</p>}
  <div className="identity-page-grid authority-detail audit-record-grid">
   <section className="identity-list-panel" aria-label="审计记录列表"><p className="identity-read-only-note">仅展示当前页已加载且获权的记录；不提供全部记录总数。</p>
    {list.error?<div className="identity-state" role="alert">{list.error}</div>:list.loading?<p className="identity-state" role="status">正在查询审计记录…</p>:!list.page?.items.length?<p className="identity-state">当前条件下没有可显示的记录。</p>:<div className="identity-list-scroll"><table className="identity-table audit-table"><thead><tr>{['时间','审计范围','人员','授权路径','对象','操作','结果'].map(h=><th key={h}>{h}</th>)}</tr></thead><tbody>{list.page.items.map(row=><tr key={row.id} className={selected===row.id?'selected':undefined}><td><button className="identity-row-button" onClick={()=>{setSelection({key:list.key,id:row.id});setChain(null);}}>{formatIdentityInstant(row.trustedAt)}</button></td><td>{row.scopeLabel}</td><td>{row.actorLabel}</td><td>{row.authorizationPathLabel}</td><td>{row.objectLabel}</td><td>{row.actionLabel}</td><td>{row.resultLabel}</td></tr>)}</tbody></table></div>}
    <footer className="identity-pagination" aria-label="当前页分页"><button disabled={list.loading||!list.canPrevious} onClick={list.previous}>上一页</button><span>{list.page?`本页 ${list.page.items.length} 项`:'本页数量未知'}</span><button disabled={list.loading||!list.canNext} onClick={list.next}>下一页</button><button className="identity-reload" disabled={list.loading} onClick={list.refresh}>刷新</button></footer>
   </section>
   <aside className="identity-detail-panel" aria-label="审计记录详情">
    {record?<><h2>操作详情</h2><DetailRows><DetailRow label="可信时间">{formatIdentityInstant(record.trustedAt)}</DetailRow><DetailRow label="人员">{record.actorLabel}</DetailRow><DetailRow label="任职">{record.appointmentLabel}</DetailRow><DetailRow label="审计范围">{record.scopeLabel}</DetailRow><DetailRow label="记录组织范围">{record.recordOrganizationLabel}</DetailRow><DetailRow label="授权路径">{record.authorizationPathLabel}</DetailRow><DetailRow label="代办情况">{record.onBehalfLabel}</DetailRow><DetailRow label="对象类别">{record.objectLabel}</DetailRow><DetailRow label="操作">{record.actionLabel}</DetailRow><DetailRow label="结果">{record.resultLabel}</DetailRow></DetailRows><h3>安全摘要</h3><p>{record.summary}</p><IdentityActions variant="inline"><button onClick={()=>setChain({key:detailKey,relation:'CORRELATION',cursors:[undefined],index:0})}>相关链</button><button onClick={()=>setChain({key:detailKey,relation:'CORRECTION',cursors:[undefined],index:0})}>更正链</button></IdentityActions><InfoNote>{record.verificationLabel}更正链仅查看已有记录。</InfoNote></>:selected?<p className="identity-state" role={detail.error?'alert':'status'}>{detail.key===detailKey&&detail.error?detail.error:'正在读取详情…'}</p>:<p className="identity-state">请选择记录查看详情。</p>}
    {record&&chainKey&&chain&&<section aria-label={chain.relation==='CORRELATION'?'相关链':'更正链'}><h3>{chain.relation==='CORRELATION'?'相关链':'更正链'}</h3>{related.error?<p role="alert">{related.error}</p>:chainPage?<><p>本页 {chainPage.items.length} 项获权记录</p><ol className="audit-chain">{chainPage.items.map(row=><li key={row.id}><span>{formatIdentityInstant(row.trustedAt)}</span><strong>{row.actionLabel} · {row.resultLabel}</strong><span>{row.actorLabel} · {row.objectLabel}</span></li>)}</ol><IdentityActions variant="inline"><button disabled={chain.index===0} onClick={()=>setChain({...chain,index:chain.index-1})}>链上一页</button><button disabled={!chainPage.nextCursor} onClick={()=>chainPage.nextCursor&&setChain({...chain,cursors:[...chain.cursors.slice(0,chain.index+1),chainPage.nextCursor],index:chain.index+1})}>链下一页</button></IdentityActions></>:<p role="status">正在读取获权关系链…</p>}</section>}
   </aside>
  </div>
 </>;
}
