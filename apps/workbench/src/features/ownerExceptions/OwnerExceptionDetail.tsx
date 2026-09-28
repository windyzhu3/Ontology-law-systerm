import type { OwnerException } from '../../lib/ownerExceptionTransport';
export const reasonLabels:Record<string,string>={OWNER_INACTIVE:'原负责人任职已失效',OWNER_AUTHORITY_MISSING:'原负责人业务授权已失效',OWNER_DENIED:'原负责人无法继续办理',SUPERVISOR_UNRESOLVED:'待核对主管配置',SOURCE_INCONSISTENT:'来源依据待核对'};
export const stateLabels={ACTIVE:'待处置',COORDINATING:'协调中 · 仍待解决',RESOLVED:'已解决',NO_LONGER_APPLICABLE:'已不适用'};
export const when=(value?:string)=>value?new Date(value).toLocaleString('zh-CN',{hour12:false}):'—';
export function deadline(d:OwnerException){return d.resumeDueAt?`仍等待至 ${when(d.resumeDueAt)}，不提前开始办理。`:d.task?`保留原期限 ${when(d.taskDueAt)} 与逾期记录。`:'承接后按业务日历起算首次办理期限。';}
export function OwnerExceptionDetail({detail:d}:{detail:OwnerException}){return <><h2>{d.opportunityLabel||'商机责任异常'}</h2><p className="status">{stateLabels[d.state]}</p><dl className="detail-facts"><div><dt>原负责人</dt><dd>{d.frozenOwnerLabel||'负责人信息暂不可用'}</dd></div><div><dt>当前责任人</dt><dd>{d.currentOwnerLabel||'负责人信息暂不可用'}</dd></div><div><dt>原约定</dt><dd>{deadline(d)}</dd></div>{d.reviewDueAt&&<div><dt>下次核对</dt><dd>{when(d.reviewDueAt)}</dd></div>}</dl><p className="feedback">{d.reasonCodes.map(x=>reasonLabels[x]||'责任依据待核对').join('；')}</p>{d.state==='COORDINATING'&&<p>协调安排已记录，该异常仍待解决，原期限继续保留。</p>}<details><summary>来源与处理记录</summary><p>首次发现：{when(d.firstObservedAt)}</p><p>最近核对：{when(d.lastObservedAt)}</p></details></>;}

