import {createSessionTransport,TransportError,type WorkbenchSession} from './sessionTransport';
export type WaitingState='WAIT_FUTURE'|'WAIT_CONDITION'|'WAIT_DUE'|'READY';
export type WaitingRow={id:string;customerLabel:string;purposeLabel:string;state:WaitingState;reason:string;resumeAt:string|null};
export type WaitingDetail=WaitingRow&{ownerLabel:string;nextAction:string;canHandle:boolean;history:{at:string;label:string}[]};
export type WaitingPage={waitingItems:WaitingRow[];totalCount:number;nextCursor:string|null;asOf:string};
export type WaitingRecord={waitingDetail:WaitingDetail;totalCount:number};
const obj=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v);
const text=(v:unknown):v is string=>typeof v==='string'&&v.length>0&&v.length<=2000;
const id=(v:unknown):v is string=>typeof v==='string'&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v);
const time=(v:unknown):v is string=>typeof v==='string'&&/^\d{4}-\d{2}-\d{2}T/.test(v)&&Number.isFinite(Date.parse(v));
const count=(v:unknown):v is number=>Number.isSafeInteger(v)&&Number(v)>=0;
const row=(v:unknown):v is WaitingRow & Record<string,unknown>=>obj(v)&&id(v.id)&&['customerLabel','purposeLabel','reason'].every(k=>text(v[k]))&&['WAIT_FUTURE','WAIT_CONDITION','WAIT_DUE','READY'].includes(String(v.state))&&(v.resumeAt===null||time(v.resumeAt));
export function createPersonalWaitingTransport(fetcher:typeof fetch=fetch){
 const {auth,checked,assertCurrent}=createSessionTransport(true);
 async function read(s:WorkbenchSession,path:string,signal:AbortSignal){const headers=await auth(s,signal);const response=await fetcher(path,{method:'GET',headers,signal,cache:'no-store'});assertCurrent(s,signal);const data:unknown=await response.json();assertCurrent(s,signal);if([403,404].includes(response.status))throw new TransportError(response.status,'NOT_AUTHORIZED');checked(s,signal,{response,data,error:data});return data;}
 return {
  async list(s:WorkbenchSession,cursor:string|null,signal:AbortSignal):Promise<WaitingPage>{const q=new URLSearchParams({limit:'20'});if(cursor)q.set('cursor',cursor);const data=await read(s,'/api/v1/workbench/waiting?'+q,signal);if(!obj(data)||!count(data.totalCount)||!time(data.asOf)||!Array.isArray(data.waitingItems)||data.waitingItems.length>20||data.waitingItems.length>data.totalCount||!data.waitingItems.every(v=>row(v)&&v.state!=='READY')||new Set(data.waitingItems.map(v=>v.id)).size!==data.waitingItems.length||!(data.nextCursor===null||typeof data.nextCursor==='string'&&/^[0-9]{1,8}\.[A-Za-z0-9_-]{43}$/.test(data.nextCursor)))throw Error('等待事项无法核对');return data as WaitingPage;},
  async detail(s:WorkbenchSession,taskId:string,signal:AbortSignal):Promise<WaitingRecord>{if(!id(taskId))throw Error('等待事项无法核对');const data=await read(s,'/api/v1/workbench/waiting/'+taskId,signal);if(!obj(data)||!count(data.totalCount)||!obj(data.waitingDetail))throw Error('等待详情无法核对');const d=data.waitingDetail;if(!row(d)||d.id!==taskId||!text(d.ownerLabel)||!text(d.nextAction)||typeof d.canHandle!=='boolean'||d.canHandle!==(d.state==='READY')||!Array.isArray(d.history)||d.history.length>50||!d.history.every(h=>obj(h)&&time(h.at)&&text(h.label)))throw Error('等待详情无法核对');return data as WaitingRecord;}
 };
}
export type PersonalWaitingTransport=ReturnType<typeof createPersonalWaitingTransport>;
