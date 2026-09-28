import {useEffect,useRef,useState,type ComponentProps} from 'react';
import {TeamLedger,type TeamView} from './TeamLedger';
import type {TeamTransport,TeamPage,TeamRecord} from '../../lib/teamTransport';
import {type WorkbenchSession,TransportError} from '../../lib/sessionTransport';
import {readWithDeadline} from '../../lib/readDeadline';
const remembered=new WeakMap<WorkbenchSession,Partial<Record<TeamView,{search:string;state:string;cursor?:string}>>>();
const states={tasks:['待办理','已逾期'],waiting:['未到约定时间','等待条件满足','已到核对时间'],exceptions:['负责人失效','责任安排待处理','协调中','到期未接续'],history:['已处理','已取消']};
const codes:Record<string,string>={'待办理':'OPEN','已逾期':'OVERDUE','未到约定时间':'WAIT_FUTURE','等待条件满足':'WAIT_CONDITION','已到核对时间':'WAIT_DUE','负责人失效':'OWNER_INVALID','责任安排待处理':'OWNER_EXCEPTION','协调中':'COORDINATING','到期未接续':'CONTINUATION_GAP','已处理':'DONE','已取消':'CANCELLED'};
function TeamManagementSessionPage({session,api,view,onView,onTask,onException,onLeads,onOverview,onTasks,onOpportunities,onContracts}:{session:WorkbenchSession;api:TeamTransport;view:TeamView;onView:(v:TeamView)=>void;onTask:(id:string)=>void|Promise<void>;onException:(id:string)=>void|Promise<void>;onContracts?:()=>void;onTasks?:()=>void;onLeads?:()=>void;onOverview?:()=>void;onOpportunities?:()=>void}){
 const alive=useRef(true),actionRead=useRef<AbortController|null>(null);
 useEffect(()=>{alive.current=true;return()=>{alive.current=false;actionRead.current?.abort();};},[]);
 const saved=remembered.get(session)?.[view];
 const [search,setSearch]=useState(saved?.search??''),[state,setState]=useState(saved?.state??''),[cursor,setCursor]=useState<string|undefined>(saved?.cursor),[refresh,setRefresh]=useState(0),[selected,setSelected]=useState<string|null>(null),[error,setError]=useState(''),[denied,setDenied]=useState(false),[busy,setBusy]=useState(false);
 useEffect(()=>{if(session.isCurrent())remembered.set(session,{...remembered.get(session),[view]:{search,state,cursor}});},[session,view,search,state,cursor]);
 const key=JSON.stringify([view,search,state,cursor,refresh]);const current=useRef({session,key,view});current.current={session,key,view};
 const [page,setPage]=useState<{session:WorkbenchSession;key:string;value:TeamPage}|null>(null),[detail,setDetail]=useState<{session:WorkbenchSession;key:string;value:TeamRecord}|null>(null);
 const valid=()=>alive.current&&session.isCurrent()&&current.current.session===session&&current.current.key===key;
 function failed(e:unknown){setPage(null);setDetail(null);setSelected(null);setDenied(e instanceof TransportError&&[401,403,404].includes(e.status));setError('本次读取失败，请重新查询。');}
 useEffect(()=>{const controller=new AbortController();let live=true;setPage(null);setDetail(null);setSelected(null);setDenied(false);setError('');const timer=window.setTimeout(()=>{
  void readWithDeadline(signal=>api.list(session,view,{search,state:codes[state],cursor},signal),controller.signal).then(value=>{if(live&&valid())setPage({session,key,value});}).catch(e=>{if(live&&valid())failed(e);});
 },search?250:0);return()=>{live=false;window.clearTimeout(timer);controller.abort();};},[session,api,key]);
 useEffect(()=>{const controller=new AbortController();let live=true;setDetail(null);if(selected)void readWithDeadline(signal=>api.detail(session,view,selected,signal),controller.signal).then(value=>{if(live&&valid())setDetail({session,key,value});}).catch(e=>{if(live&&valid())failed(e);});return()=>{live=false;controller.abort();};},[session,api,key,selected]);
 const shown=page?.session===session&&page.key===key?page.value:null;
 const shownDetail=detail?.session===session&&detail.key===key&&detail.value.id===selected?detail.value:null;
 async function handle(id:string){if(busy||!valid())return;setBusy(true);const controller=new AbortController();actionRead.current=controller;try{const fresh=await readWithDeadline(signal=>api.detail(session,view,id,signal),controller.signal);if(!valid())return;if(fresh.action?.kind==='task'&&fresh.taskId)await onTask(fresh.taskId);else if(fresh.action?.kind==='exception'&&fresh.exceptionId)await onException(fresh.exceptionId);else throw Error('changed');}catch{if(valid()){setPage(null);setDetail(null);setSelected(null);setError('当前事项已变化，请重新查询后办理。');}}finally{if(actionRead.current===controller)actionRead.current=null;if(alive.current)setBusy(false);}}
 if(!session.isCurrent())return null;
 return <TeamLedger onClear={()=>{setSearch('');setState('');setCursor(undefined);}} onFirst={cursor?()=>setCursor(undefined):undefined} view={view} rows={shown?.items??[]} detail={shownDetail} selectedId={selected} search={search} state={state} states={states[view]} permitted={!denied} loading={busy||(!shown&&!error)} error={error} nextCursor={shown?.nextCursor} onView={v=>{setSearch('');setState('');setCursor(undefined);setSelected(null);onView(v);}} onSearch={q=>{setSearch(q);setCursor(undefined);}} onState={s=>{setState(s);setCursor(undefined);}} onSelect={setSelected} onHandle={id=>void handle(id)} onReload={()=>{setCursor(undefined);setRefresh(n=>n+1);}} onNext={()=>{if(shown?.nextCursor)setCursor(shown.nextCursor);}} onTasks={onTasks} onLeads={onLeads} onOverview={onOverview} onOpportunities={onOpportunities} onContracts={onContracts}/>;
}

export function TeamManagementPage(props:ComponentProps<typeof TeamManagementSessionPage>){return <TeamManagementSessionPage key={`${props.session.actorScopeKey}:${props.session.identityEpoch}:${props.view}`} {...props}/>;}
