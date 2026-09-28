import {createSessionTransport,TransportError,type WorkbenchSession} from './sessionTransport';
import type {TeamView,TeamRow,TeamDetail} from '../features/teamManagement/TeamLedger';
export type TeamPage={items:TeamRow[];nextCursor:string|null};
export type TeamRecord=TeamDetail&{taskId:string|null;exceptionId:string|null};
export type TeamQuery={search?:string;state?:string;cursor?:string};
const obj=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v);
const text=(v:unknown):v is string=>typeof v==='string';
const id=(v:unknown):v is string=>text(v)&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v);
const row=(v:unknown):v is TeamRow=>obj(v)&&id(v.id)&&['customerLabel','purposeLabel','stateLabel','ownerLabel','timeLabel'].every(k=>text(v[k]));
function action(v:Record<string,unknown>){if(v.action===null)return v.taskId===null&&v.exceptionId===null;if(!obj(v.action))return false;return v.action.kind==='task'?v.action.label==='前往办理'&&id(v.taskId)&&v.exceptionId===null:v.action.kind==='exception'&&v.action.label==='查看原异常处置'&&id(v.exceptionId)&&v.taskId===null;}
export function createTeamTransport(fetcher:typeof fetch=fetch){
 const {auth,assertCurrent,checked}=createSessionTransport(false);
 async function read(session:WorkbenchSession,path:string,signal:AbortSignal){const headers=await auth(session,signal);const response=await fetcher(path,{method:'GET',headers,signal,cache:'no-store'});assertCurrent(session,signal);const data:unknown=await response.json();assertCurrent(session,signal);if(response.status===403)throw new TransportError(403,'NOT_AUTHORIZED');checked(session,signal,{response,data,error:data});return data;}
 const base=(view:TeamView)=>'/api/v1/team-management/'+view;
 return {
  async list(s:WorkbenchSession,view:TeamView,q:TeamQuery,signal:AbortSignal):Promise<TeamPage>{const params=new URLSearchParams({limit:'20'});if(q.search)params.set('search',q.search);if(q.state)params.set('state',q.state);if(q.cursor)params.set('cursor',q.cursor);const data=await read(s,base(view)+'?'+params,signal);if(!obj(data)||!Array.isArray(data.items)||data.items.length>20||!data.items.every(row)||new Set(data.items.map(r=>r.id)).size!==data.items.length||!(data.nextCursor===null||text(data.nextCursor)&&data.nextCursor.length<=512))throw Error('团队查询结果无法核对');return data as TeamPage;},
  async detail(s:WorkbenchSession,view:TeamView,requestId:string,signal:AbortSignal):Promise<TeamRecord>{if(!id(requestId))throw Error('事项无法核对');const data=await read(s,base(view)+'/'+encodeURIComponent(requestId),signal);if(!obj(data)||data.id!==requestId||!text(data.customerLabel)||!text(data.nextAction)||!action(data)||!Array.isArray(data.facts)||!data.facts.every(f=>Array.isArray(f)&&f.length===2&&f.every(text))||!Array.isArray(data.history)||!data.history.every(h=>obj(h)&&id(h.id)&&text(h.label)))throw Error('团队详情无法核对');return data as TeamRecord;}
 };
}
export type TeamTransport=ReturnType<typeof createTeamTransport>;
