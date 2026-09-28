import {createSessionTransport,TransportError,matchesReceipt,type WorkbenchSession} from './sessionTransport';
import {RecoveryStore,uuidPattern} from '../features/session/recoveryMarker';
import {provenWriteOutcome} from '../features/session/recoveryOutcome';
import type {components} from '../generated/api/schema';
export const attemptCommands=['RECORD_OPPORTUNITY_FOLLOWUP_ATTEMPT','RECORD_QUOTE_FOLLOWUP_ATTEMPT'] as const;
export type AttemptCommand=typeof attemptCommands[number];
export type AttemptContext=components['schemas']['FollowupAttemptContextV1'];
export type AttemptWrite={key:string;opportunityId:string;command:AttemptCommand;body:components['schemas']['RecordFollowupAttemptV1']};
const object=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v);
const uuid=(v:unknown)=>typeof v==='string'&&uuidPattern.test(v);
const selector=(v:unknown)=>object(v)&&uuid(v.id)&&Number.isSafeInteger(v.revision)&&Number(v.revision)>=0;
const hash=(v:unknown)=>object(v)&&uuid(v.id)&&typeof v.hash==='string'&&/^[A-Za-z0-9_-]{43}$/.test(v.hash);
const instant=(v:unknown)=>typeof v==='string'&&Number.isFinite(Date.parse(v));
export function validAttemptContext(v:unknown,id:string):v is AttemptContext {
 if(!object(v)||!selector(v.opportunity)||(v.opportunity as {id:string}).id!==id||!selector(v.responsibilityBasis)||!object(v.responsibilityBasis)||!['opportunity.opportunity','opportunity.responsibility_handoff'].includes(String(v.responsibilityBasis.type))||!(v.task===null||selector(v.task))||![null,'OPEN','WAITING'].includes(v.taskState as string|null)||!(v.waitReceipt===null||hash(v.waitReceipt))||!(v.expectedWorkflow===null||selector(v.expectedWorkflow))||!(v.nextCheckAt===null||instant(v.nextCheckAt))||!(v.command===null||attemptCommands.includes(v.command as AttemptCommand))||!Array.isArray(v.history))return false;
 if(v.command!==null&&(!v.task||(v.taskState==='WAITING')!==(v.waitReceipt!==null)||(v.command==='RECORD_QUOTE_FOLLOWUP_ATTEMPT')!==(v.expectedWorkflow!==null)))return false;
 return v.history.every(r=>object(r)&&hash(r.selector)&&['OPPORTUNITY','QUOTE'].includes(String(r.context))&&['NOT_CONNECTED','NO_REPLY','NO_EFFECTIVE_PROGRESS'].includes(String(r.type))&&typeof r.summary==='string'&&instant(r.occurredAt)&&instant(r.nextCheckAt)&&instant(r.recordedAt)&&uuid(r.actorAppointmentId));
}
export function createFollowupAttemptsTransport(recovery=new RecoveryStore(window.sessionStorage),fetcher:typeof fetch=fetch){
 const {auth,assertCurrent,checked}=createSessionTransport(false),base=(id:string)=>'/api/v1/opportunities/'+encodeURIComponent(id);
 async function request(s:WorkbenchSession,path:string,signal:AbortSignal,w?:AttemptWrite){const headers=await auth(s,signal),marker=w?recovery.reserveWrite(w.key,w.command,s.actorScopeKey,w):null;const response=await fetcher(path,{method:w?'POST':'GET',headers:{...headers,...(w?{'Content-Type':'application/json','Idempotency-Key':w.key}:{})},body:w?JSON.stringify(w.body):undefined,cache:'no-store',signal});assertCurrent(s,signal);const data:unknown=await response.json();assertCurrent(s,signal);if(marker&&!response.ok&&provenWriteOutcome(data,response.status,marker)){recovery.clear(marker);throw new TransportError(response.status,(data as {code:string}).code,true);}checked(s,signal,{response,data,error:data});return{response,data};}
 function terminal(data:unknown){const marker=recovery.read();if(!marker||!matchesReceipt(data,marker)||data.outcome==='NO_CHANGE')throw Error('结果尚未确认');recovery.clear(marker);if(data.outcome==='REJECTED')throw new TransportError(data.rejectionCode==='VALIDATION_FAILED'?400:['NOT_AUTHORIZED','APPOINTMENT_INACTIVE'].includes(data.rejectionCode)?403:data.rejectionCode==='NOT_FOUND'?404:['OPPORTUNITY_CLOSED','OPPORTUNITY_HAS_DOWNSTREAM_FACTS'].includes(data.rejectionCode)?409:412,data.rejectionCode,true);return data;}
 return{recovery,async context(s:WorkbenchSession,id:string,signal:AbortSignal){const {data}=await request(s,base(id)+'/followup-attempts',signal);if(!validAttemptContext(data,id))throw Error('联系安排读取结果无法核对');return data;},async write(s:WorkbenchSession,w:AttemptWrite,signal:AbortSignal){const {response,data}=await request(s,base(w.opportunityId)+(w.command==='RECORD_QUOTE_FOLLOWUP_ATTEMPT'?'/quotes':'')+'/followup-attempts',signal,w);if(![200,201].includes(response.status))throw Error('结果尚未确认');return terminal(data);},async receipt(s:WorkbenchSession,signal:AbortSignal){const marker=recovery.read();if(!marker||!attemptCommands.includes(marker.commandType as AttemptCommand)||marker.actorScopeKey!==s.actorScopeKey)throw new TransportError(403);const {response,data}=await request(s,'/api/v1/commands/'+encodeURIComponent(marker.commandId)+'/receipt',signal);if(response.status!==200)throw Error('结果尚未确认');return terminal(data);}};
}
export type FollowupAttemptsTransport=ReturnType<typeof createFollowupAttemptsTransport>;
