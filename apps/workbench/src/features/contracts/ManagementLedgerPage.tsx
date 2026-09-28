import {useEffect,useRef,useState} from 'react';
import {ManagementLedger,type ManagementView} from './ManagementLedger';
import type {ManagementTransport,ManagementPage,ManagementRecord} from '../../lib/managementTransport';
import {type WorkbenchSession,TransportError} from '../../lib/sessionTransport';
import {readWithDeadline} from '../../lib/readDeadline';
const remembered=new WeakMap<WorkbenchSession,Partial<Record<ManagementView,{search:string;state:string;cursor?:string}>>>();
const states={payments:['待核对本笔收款','待补充收款凭证','本笔收款已核对','责任安排待处理'],transfer:['待提交转案','待独立冲突审查','待案管接收','退回补正','已接收，待分类','已分类及承接','责任安排待处理']};
const codes:Record<string,string>={'待核对本笔收款':'CHECK_RECEIPT','待补充收款凭证':'SUPPLEMENT_RECEIPT','本笔收款已核对':'COMPLETE','已分类及承接':'COMPLETE','责任安排待处理':'OWNER_EXCEPTION','待提交转案':'PREPARE','待独立冲突审查':'REVIEW_TRANSFER','待案管接收':'INTAKE','退回补正':'SUPPLEMENT','已接收，待分类':'CLASSIFY'};
export function ManagementLedgerPage({session,api,view,onView,onTask,onTasks,onTeam,onLeads,onOverview,onOpportunities}:{session:WorkbenchSession;api:ManagementTransport;view:ManagementView;onView:(v:ManagementView|'contracts')=>void;onTask:(id:string)=>void|Promise<void>;onTasks?:()=>void;onTeam?:()=>void;onLeads?:()=>void;onOverview?:()=>void;onOpportunities?:()=>void}){
 const saved=remembered.get(session)?.[view];
 const [search,setSearch]=useState(saved?.search??''),[state,setState]=useState(saved?.state??''),[cursor,setCursor]=useState<string|undefined>(saved?.cursor),[refresh,setRefresh]=useState(0),[selected,setSelected]=useState<string|null>(null),[error,setError]=useState(''),[denied,setDenied]=useState(false),[busy,setBusy]=useState(false);
 useEffect(()=>{if(session.isCurrent())remembered.set(session,{...remembered.get(session),[view]:{search,state,cursor}});},[session,view,search,state,cursor]);
 const key=JSON.stringify([view,search,state,cursor,refresh]);const current=useRef({session,key,view});current.current={session,key,view};
 const [page,setPage]=useState<{session:WorkbenchSession;key:string;value:ManagementPage}|null>(null),[detail,setDetail]=useState<{session:WorkbenchSession;key:string;value:ManagementRecord}|null>(null);
 const valid=()=>session.isCurrent()&&current.current.session===session&&current.current.key===key;
 function failed(e:unknown){setPage(null);setDetail(null);setSelected(null);setDenied(e instanceof TransportError&&[401,403,404].includes(e.status));setError('本次读取失败，请重新查询。');}
 useEffect(()=>{const controller=new AbortController();let live=true;setPage(null);setDetail(null);setSelected(null);setDenied(false);setError('');const timer=window.setTimeout(()=>{
  void readWithDeadline(signal=>api.list(session,view,{search,state:codes[state],cursor},signal),controller.signal).then(value=>{if(live&&valid())setPage({session,key,value});}).catch(e=>{if(live&&valid())failed(e);});
 },search?250:0);return()=>{live=false;window.clearTimeout(timer);controller.abort();};},[session,api,key]);
 useEffect(()=>{const controller=new AbortController();let live=true;setDetail(null);if(selected)void readWithDeadline(signal=>api.detail(session,view,selected,signal),controller.signal).then(value=>{if(live&&valid())setDetail({session,key,value});}).catch(e=>{if(live&&valid())failed(e);});return()=>{live=false;controller.abort();};},[session,api,key,selected]);
 const shown=page?.session===session&&page.key===key?page.value:null;
 const shownDetail=detail?.session===session&&detail.key===key&&detail.value.id===selected?detail.value:null;
 async function handle(id:string){if(busy||!valid())return;setBusy(true);try{const fresh=await readWithDeadline(signal=>api.detail(session,view,id,signal),new AbortController().signal);if(!valid())return;if(!fresh.canHandle||!fresh.taskId)throw Error('changed');await onTask(fresh.taskId);}catch{if(valid()){setPage(null);setDetail(null);setSelected(null);setError('当前事项已变化，请重新查询后办理。');}}finally{setBusy(false);}}
 if(!session.isCurrent())return null;
 return <ManagementLedger views={session.businessManagementViews} onClear={()=>{setSearch('');setState('');setCursor(undefined);}} onFirst={cursor?()=>setCursor(undefined):undefined} view={view} rows={shown?.items??[]} detail={shownDetail} selectedId={selected} search={search} state={state} states={states[view]} permitted={!denied} loading={busy||(!shown&&!error)} error={error} nextCursor={shown?.nextCursor} onView={v=>{setSearch('');setState('');setCursor(undefined);setSelected(null);onView(v);}} onSearch={q=>{setSearch(q);setCursor(undefined);}} onState={s=>{setState(s);setCursor(undefined);}} onSelect={setSelected} onHandle={id=>void handle(id)} onReload={()=>{setCursor(undefined);setRefresh(n=>n+1);}} onNext={()=>{if(shown?.nextCursor)setCursor(shown.nextCursor);}} onTasks={onTasks} onTeam={onTeam} onLeads={onLeads} onOverview={onOverview} onOpportunities={onOpportunities}/>;
}
