import type {WaitingSummaryProps} from '../workcard/WaitingSummary';
import {useEffect,useState,type ReactNode} from 'react';
import type {WorkbenchSession} from '../../lib/api';
import type {QuotesTransport,QuoteContext} from '../../lib/quotesTransport';
import {QuoteCard} from './QuoteCard';
export function QuoteTaskCard({session,taskId,api,onTasks,onLedger,sessionActions,waiting}:{session:WorkbenchSession;taskId:string;api:QuotesTransport;onTasks:()=>void;onLedger?:()=>void;sessionActions?:ReactNode;waiting?:WaitingSummaryProps}){
 const [resolved,setResolved]=useState<{session:WorkbenchSession;taskId:string;context:QuoteContext}|null>(null),[error,setError]=useState(false),[refresh,setRefresh]=useState(0);
 useEffect(()=>{let live=true;const controller=new AbortController();setResolved(null);setError(false);api.taskContext(session,taskId,controller.signal).then(data=>{if(live&&session.isCurrent())setResolved({session,taskId,context:data.context});}).catch(()=>{if(live){setResolved(null);setError(true);}});return()=>{live=false;controller.abort();};},[session,taskId,api,refresh]);
 const context=resolved?.session===session&&resolved.taskId===taskId?resolved.context:null;
 if(!session.isCurrent())return null;
 if(!context)return <main className="workbench"><article className="work-card"><div className="context"><h1>核对报价责任</h1></div><section className="card-action"><p role="status">{error?'当前责任或权限已变化，请重新核对。':'正在读取有权报价事项…'}</p>{error&&<><button className="primary" onClick={()=>setRefresh(n=>n+1)}>重新读取报价责任</button><button className="link-button" onClick={onTasks}>返回我的待办</button></>}</section></article></main>;
 return <QuoteCard waiting={waiting} initialContext={context} session={session} detail={{opportunity:context.opportunity,customerLabel:context.customerName,ownerLabel:context.workflow?.ownerLabel??'当前责任人',taskState:context.workflow?.nextCheckAt&&Date.parse(context.workflow.nextCheckAt)>Date.now()?'WAITING':'OPEN',canHandle:false,dueAt:context.workflow?.dueAt??undefined}} api={api} sessionActions={sessionActions} backLabel={onLedger?'返回商机台账':'返回工作台'} onBack={onLedger??onTasks} onTasks={onTasks} onDenied={()=>{setResolved(null);setError(true);}}/>;
}

