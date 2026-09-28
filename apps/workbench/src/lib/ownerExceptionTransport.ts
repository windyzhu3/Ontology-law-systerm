import { provenWriteOutcome } from '../features/session/recoveryOutcome';
import { createSessionTransport, TransportError, type WorkbenchSession } from './sessionTransport';
import { RecoveryStore } from '../features/session/recoveryMarker';
import { validReceipt } from '../features/workcard/contract';
export type Selector = {type:string;id:string;revision:number;hash?:never}|{type:string;id:string;hash:string;revision?:never};
export interface OwnerException {exception:Selector;opportunity:Selector;basis:Selector;task?:Selector|null;wait?:Selector|null;currentOwnerAppointmentId:string;frozenOwnerAppointmentId:string;opportunityLabel?:string;currentOwnerLabel?:string;frozenOwnerLabel?:string;state:'ACTIVE'|'COORDINATING'|'RESOLVED'|'NO_LONGER_APPLICABLE';reasonCodes:string[];firstObservedAt:string;lastObservedAt:string;reviewDueAt?:string;allowedActions:('TRANSFER'|'COORDINATE')[];etag:string;taskState?:string;taskDueAt?:string;resumeDueAt?:string}
export interface Candidate {appointmentId:string;displayName?:string;organizationLabel?:string}
export interface OperationsException {exceptionId:string;reasonCodes:string[];state:OwnerException['state'];firstObservedAt:string;lastObservedAt:string;organizationLabel:string;repairGuidance:string}
export interface Page<T>{items:T[];nextCursor?:string}
export type DispositionInput = {opportunityId:string;expectedOpportunityRevision:number;exceptionId:string;expectedExceptionRevision:number;expectedBasis:Selector;expectedTask:Selector|null;expectedWait:Selector|null;reason:string} & ({receiverAppointmentId:string;reviewDueAt?:never}|{reviewDueAt:string;receiverAppointmentId?:never});
export interface OwnerExceptionWrite {key:string;kind:'transfer'|'coordinate';body:DispositionInput}
export function createOwnerExceptionTransport(recovery = new RecoveryStore(window.sessionStorage), fetcher:typeof fetch = fetch){
 const {auth,assertCurrent,checked}=createSessionTransport(false);
 const request=async<T>(session:WorkbenchSession,path:string,signal:AbortSignal,write?:OwnerExceptionWrite):Promise<T>=>{
  const headers=await auth(session,signal);
  const commandType=write?.kind==='transfer'?'TRANSFER_OPPORTUNITY_RESPONSIBILITY':'RECORD_OPPORTUNITY_OWNER_COORDINATION';
  const marker=write?recovery.reserveWrite(write.key,commandType,session.actorScopeKey,write):null;
  const response=await fetcher(path,{method:write?'POST':'GET',headers:{...headers,...(write?{'Content-Type':'application/json','Idempotency-Key':write.key}:{})},body:write?JSON.stringify(write.body):undefined,cache:'no-store',signal});
  assertCurrent(session,signal);
  const data:unknown=await response.json();
  assertCurrent(session,signal);
  if(write && !response.ok && provenWriteOutcome(data,response.status,marker!)){recovery.clear(marker!);throw new TransportError(response.status,(data as {code:string}).code,true);}
  if(write && response.ok){
   const fact='OPPORTUNITY_OWNER_EXCEPTION';
   if(!validReceipt(data,write.key))throw Error('结果尚未确认');
   if(data.outcome==='REJECTED'){recovery.clear(marker!);throw new TransportError(412,data.rejectionCode,true);}
   if(String(data.resultFact.factType)!==fact)throw Error('结果尚未确认');
   recovery.clear(marker!);
  }
  return checked(session,signal,{response,data:data as T,error:data}).data!;
 };
 return {recovery,
 list:(s:WorkbenchSession,signal:AbortSignal,cursor?:string)=>request<Page<OwnerException>>(s,`/api/v1/opportunity-owner-exceptions?limit=20${cursor?'&cursor='+encodeURIComponent(cursor):''}`,signal),
 detail:(s:WorkbenchSession,id:string,signal:AbortSignal)=>request<OwnerException>(s,`/api/v1/opportunity-owner-exceptions/${encodeURIComponent(id)}`,signal),
 candidates:(s:WorkbenchSession,d:OwnerException,signal:AbortSignal,cursor?:string)=>request<Page<Candidate>&{exception:Selector;etag:string}>(s,`/api/v1/opportunity-owner-exceptions/${encodeURIComponent(d.exception.id)}/candidates?expectedRevision=${d.exception.revision}&limit=20${cursor?'&cursor='+encodeURIComponent(cursor):''}`,signal),
 operations:(s:WorkbenchSession,signal:AbortSignal,cursor?:string)=>request<Page<OperationsException>>(s,`/api/v1/opportunity-owner-exceptions/operations?limit=20${cursor?'&cursor='+encodeURIComponent(cursor):''}`,signal),
 write:(s:WorkbenchSession,w:OwnerExceptionWrite,signal:AbortSignal)=>request<unknown>(s,`/api/v1/opportunity-owner-exceptions/commands/${w.kind}`,signal,w),
 async receipt(s:WorkbenchSession,signal:AbortSignal){const marker=recovery.read();if(!marker||marker.actorScopeKey!==s.actorScopeKey)throw new TransportError(403);const value=await request<unknown>(s,`/api/v1/commands/${marker.commandId}/receipt`,signal);if(!validReceipt(value,marker.commandId))throw Error('结果尚未确认');if(value.outcome!=='REJECTED'&&String(value.resultFact.factType)!=='OPPORTUNITY_OWNER_EXCEPTION')throw Error('结果尚未确认');recovery.clear(marker);if(value.outcome==='REJECTED')throw new TransportError(412,value.rejectionCode,true);return value;}
 };
}
export type OwnerExceptionTransport=ReturnType<typeof createOwnerExceptionTransport>;




