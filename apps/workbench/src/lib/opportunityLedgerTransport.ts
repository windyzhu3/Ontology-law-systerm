import { createSessionTransport, type WorkbenchSession } from "./sessionTransport";
import type {components} from '../generated/api/schema';
type S=components['schemas'];
export type LedgerRow=S['OpportunityLedgerItemV1'];
export type LedgerState=LedgerRow['taskState'];
export type LedgerDetail=S['OpportunityLedgerDetailV1'];
export type LedgerPage=S['OpportunityLedgerPageV1'];
export interface LedgerQuery {search?:string;state?:LedgerState;cursor?:string}
const record=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v);
const text=(v:unknown)=>typeof v==='string'&&v.trim().length>0;
const exact=(v:Record<string,unknown>,keys:string[])=>Object.keys(v).every(k=>keys.includes(k));
const stamp=(v:unknown)=>text(v)&&Number.isFinite(Date.parse(v as string));
const rowKeys=['opportunity','customerLabel','ownerLabel','taskState','nextActionLabel','dueAt'];
export function validLedgerRow(v:unknown,detail=false):v is LedgerRow {return record(v)&&exact(v,detail?[...rowKeys,'lastProgress','canHandle','task']:rowKeys)&&record(v.opportunity)&&exact(v.opportunity,['id','revision'])&&text(v.opportunity.id)&&Number.isSafeInteger(v.opportunity.revision)&&Number(v.opportunity.revision)>=0&&text(v.customerLabel)&&text(v.ownerLabel)&&['OPEN','WAITING','NONE','CLOSED'].includes(String(v.taskState))&&(v.nextActionLabel===undefined||text(v.nextActionLabel))&&(v.dueAt===undefined||stamp(v.dueAt));}
export function validLedgerDetail(v:unknown):v is LedgerDetail {if(!validLedgerRow(v,true))return false;const d=v as unknown as Record<string,unknown>;return typeof d.canHandle==='boolean'&&(d.lastProgress===undefined||(record(d.lastProgress)&&exact(d.lastProgress,['summary','occurredAt'])&&text(d.lastProgress.summary)&&stamp(d.lastProgress.occurredAt)))&&(d.canHandle===true?d.taskState==='OPEN'&&record(d.task)&&exact(d.task,['id','revision','etag'])&&text(d.task.id)&&Number.isSafeInteger(d.task.revision)&&Number(d.task.revision)>=0&&text(d.task.etag):d.task===undefined);}
export function createOpportunityLedgerTransport(fetcher:typeof fetch=fetch){const {auth,assertCurrent,checked}=createSessionTransport(false);async function read(s:WorkbenchSession,path:string,signal:AbortSignal){const headers=await auth(s,signal);const response=await fetcher(path,{headers,signal,cache:'no-store'});assertCurrent(s,signal);const data:unknown=await response.json();assertCurrent(s,signal);return checked(s,signal,{response,data,error:data}).data;}
return {async taskContext(s:WorkbenchSession,id:string,signal:AbortSignal):Promise<LedgerDetail>{const d=await read(s,'/api/v1/opportunity-tasks/'+encodeURIComponent(id)+'/context',signal);if(!validLedgerDetail(d))throw Error('原跟进依据无法核对');return d;},async list(s:WorkbenchSession,q:LedgerQuery,signal:AbortSignal):Promise<LedgerPage>{const query=new URLSearchParams({limit:'20'});for(const [k,v]of Object.entries(q))if(v)query.set(k,v);const d=await read(s,'/api/v1/opportunities?'+query,signal);if(!record(d)||!exact(d,['items','nextCursor'])||!Array.isArray(d.items)||!d.items.every(x=>validLedgerRow(x))||(d.nextCursor!==undefined&&!text(d.nextCursor)))throw Error('商机读取结果无法核对');return d as unknown as LedgerPage;},async detail(s:WorkbenchSession,id:string,signal:AbortSignal):Promise<LedgerDetail>{const d=await read(s,'/api/v1/opportunities/'+encodeURIComponent(id),signal);if(!validLedgerDetail(d)||d.opportunity.id!==id)throw Error('商机详情无法核对');return d;}};}
export type OpportunityLedgerTransport=ReturnType<typeof createOpportunityLedgerTransport>;
