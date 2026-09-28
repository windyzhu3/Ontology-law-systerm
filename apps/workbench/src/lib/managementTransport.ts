import {createSessionTransport,TransportError,type WorkbenchSession} from './sessionTransport';
import type {ManagementView,ManagementRow,ManagementDetail} from '../features/contracts/ManagementLedger';
type Row=ManagementRow&{opportunityId:string};
export type ManagementPage={items:Row[];nextCursor:string|null};
export type ManagementRecord=ManagementDetail&{opportunityId:string;taskId:string|null};
export type ManagementQuery={search?:string;state?:string;cursor?:string};
const obj=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v);
const text=(v:unknown):v is string=>typeof v==='string';
const id=(v:unknown):v is string=>text(v)&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v);
const row=(v:unknown):v is Row=>obj(v)&&id(v.id)&&id(v.opportunityId)&&['customerLabel','basisLabel','stateLabel','ownerLabel'].every(k=>text(v[k]));
export function createManagementTransport(fetcher:typeof fetch=fetch){
 const {auth,assertCurrent,checked}=createSessionTransport(false);
 async function read(session:WorkbenchSession,path:string,signal:AbortSignal){const headers=await auth(session,signal);const response=await fetcher(path,{method:'GET',headers,signal,cache:'no-store'});assertCurrent(session,signal);const data:unknown=await response.json();assertCurrent(session,signal);if(response.status===403)throw new TransportError(403,'NOT_AUTHORIZED');checked(session,signal,{response,data,error:data});return data;}
 const base=(view:ManagementView)=>'/api/v1/business-management/'+view;
 return {
  async list(s:WorkbenchSession,view:ManagementView,q:ManagementQuery,signal:AbortSignal):Promise<ManagementPage>{
   const params=new URLSearchParams({limit:'20'});if(q.search)params.set('search',q.search);if(q.state)params.set('state',q.state);if(q.cursor)params.set('cursor',q.cursor);
   const data=await read(s,base(view)+'?'+params,signal);if(!obj(data)||!Array.isArray(data.items)||data.items.length>20||!data.items.every(row)||new Set(data.items.map(r=>r.id)).size!==data.items.length||!(data.nextCursor===null||text(data.nextCursor)&&data.nextCursor.length<=512))throw Error('管理查询结果无法核对');return data as ManagementPage;
  },
  async detail(s:WorkbenchSession,view:ManagementView,requestId:string,signal:AbortSignal):Promise<ManagementRecord>{
   const data=await read(s,base(view)+'/'+encodeURIComponent(requestId),signal);
   if(!obj(data)||data.id!==requestId||!id(data.opportunityId)||!text(data.customerLabel)||typeof data.canHandle!=='boolean'||!(data.taskId===null||id(data.taskId))||data.canHandle&&data.taskId===null||!Array.isArray(data.facts)||!data.facts.every(f=>Array.isArray(f)&&f.length===2&&f.every(text))||!Array.isArray(data.history)||!data.history.every(h=>obj(h)&&id(h.id)&&text(h.label)))throw Error('管理详情无法核对');return data as ManagementRecord;
  }
 };
}
export type ManagementTransport=ReturnType<typeof createManagementTransport>;
