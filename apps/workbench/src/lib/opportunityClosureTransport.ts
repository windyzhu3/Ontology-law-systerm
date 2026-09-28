import {createSessionTransport,TransportError,type WorkbenchSession} from './sessionTransport';
import {RecoveryStore} from '../features/session/recoveryMarker';
import {allowedCommandError,provenWriteOutcome} from '../features/session/recoveryOutcome';
import {validReceipt} from '../features/workcard/contract';
type S=import('../generated/api/schema').components['schemas'];
export type ClosureReason=S['OpportunityCloseReasonV1'];
export type ClosureContext=S['OpportunityCloseContextV1'];
export interface ClosureWrite {key:string;opportunityId:string;body:S['CloseOpportunityV1']}
const object=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v);
const keys=(v:Record<string,unknown>,allowed:string[])=>Object.keys(v).every(k=>allowed.includes(k));
const text=(v:unknown)=>typeof v==='string'&&v.trim().length>0;
const revision=(v:unknown)=>Number.isSafeInteger(v)&&Number(v)>=0;
const selector=(v:unknown,hash=false)=>object(v)&&keys(v,['type','id',hash?'hash':'revision'])&&text(v.type)&&text(v.id)&&(hash?typeof v.hash==='string'&&/^[A-Za-z0-9_-]{43}$/.test(v.hash):revision(v.revision));
export function validClosureContext(v:unknown,id:string):v is ClosureContext {
 if(!object(v)||!keys(v,['opportunity','status','expectedResponsibility','expectedTask','expectedWait','closure'])||!object(v.opportunity)||!keys(v.opportunity,['id','revision'])||v.opportunity.id!==id||!revision(v.opportunity.revision))return false;
 if(v.status==='READY')return selector(v.expectedResponsibility)&&(v.expectedTask===null||selector(v.expectedTask))&&(v.expectedWait===null||selector(v.expectedWait,true))&&v.closure===undefined;
 if(v.expectedResponsibility!==undefined||v.expectedTask!==undefined||v.expectedWait!==undefined)return false;
 if(v.status==='READ_ONLY'||v.status==='BLOCKED')return v.closure===undefined;
 return v.status==='CLOSED'&&(v.closure===undefined||(object(v.closure)&&keys(v.closure,['reasonCode','summary','closedAt'])&&['CLIENT_DECLINED','NEED_CANCELLED','OTHER'].includes(String(v.closure.reasonCode))&&(v.closure.summary===undefined||text(v.closure.summary))&&typeof v.closure.closedAt==='string'&&Number.isFinite(Date.parse(v.closure.closedAt))));
}
export function createOpportunityClosureTransport(recovery=new RecoveryStore(window.sessionStorage),fetcher:typeof fetch=fetch){
 const {auth,assertCurrent,checked}=createSessionTransport(false);
 async function request(s:WorkbenchSession,path:string,signal:AbortSignal,w?:ClosureWrite){
 const headers=await auth(s,signal);const marker=w?recovery.reserveWrite(w.key,'CLOSE_OPPORTUNITY',s.actorScopeKey,w):null;
 const response=await fetcher(path,{method:w?'POST':'GET',headers:{...headers,...(w?{'Content-Type':'application/json','Idempotency-Key':w.key}:{})},body:w?JSON.stringify(w.body):undefined,cache:'no-store',signal});assertCurrent(s,signal);const data:unknown=await response.json();assertCurrent(s,signal);
 if(w&&!response.ok&&provenWriteOutcome(data,response.status,marker!)){recovery.clear(marker!);throw new TransportError(response.status,(data as {code:string}).code,true);}
 return {response,data:checked(s,signal,{response,data,error:data}).data};
 }
 function terminal(data:unknown,key:string){if(!validReceipt(data,key)||(data.outcome==='REJECTED'&&!allowedCommandError('CLOSE_OPPORTUNITY',data.rejectionCode))||data.outcome==='NO_CHANGE'||(data.outcome!=='REJECTED'&&(String(data.resultFact.factType)!=='OPPORTUNITY_CLOSURE'||(!('revision' in data.resultFact)||data.resultFact.revision!==0))))throw Error('结果尚未确认');return data;}
 return {recovery,async context(s:WorkbenchSession,id:string,signal:AbortSignal){const {data}=await request(s,'/api/v1/opportunities/'+encodeURIComponent(id)+'/closure',signal);if(!validClosureContext(data,id))throw Error('结束依据无法核对');return data;},
 async write(s:WorkbenchSession,w:ClosureWrite,signal:AbortSignal){const {response,data}=await request(s,'/api/v1/opportunities/'+encodeURIComponent(w.opportunityId)+'/commands/close',signal,w);if(response.status!==200&&response.status!==201)throw Error('结果尚未确认');const r=terminal(data,w.key),m=recovery.read();if(m?.commandId===w.key)recovery.clear(m);if(r.outcome==='REJECTED')throw new TransportError(412,r.rejectionCode,true);},
 async receipt(s:WorkbenchSession,signal:AbortSignal){const m=recovery.read();if(!m||m.commandType!=='CLOSE_OPPORTUNITY'||m.actorScopeKey!==s.actorScopeKey)throw new TransportError(403);const {response,data}=await request(s,'/api/v1/commands/'+encodeURIComponent(m.commandId)+'/receipt',signal);if(response.status!==200)throw Error('结果尚未确认');const r=terminal(data,m.commandId);recovery.clear(m);if(r.outcome==='REJECTED')throw new TransportError(412,r.rejectionCode,true);}};
}
export type OpportunityClosureTransport=ReturnType<typeof createOpportunityClosureTransport>;

