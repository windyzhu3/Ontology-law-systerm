import {createSessionTransport,TransportError,type WorkbenchSession} from './sessionTransport';
export const leadStates={INCOMPLETE:'待补齐资料',DUPLICATE:'重复待核',ASSIGNMENT:'待分配',CONTACT:'待联系',VALIDITY_REVIEW:'有效性待复核',SOURCE_REVIEW:'来源请求待处置',ROUTING:'分配安排待核对',WAITING:'等待约定核对',INVALID:'已判无效',CLOSED:'本次线索已终止',OPPORTUNITY:'已转商机',NEEDS_REVIEW:'责任依据待核对'} as const;
export type LeadQuery={search?:string;source?:string;owner?:string;state?:string;cursor?:string};
export type LeadRow={id:string;customerLabel:string;contactLabel:string;sourceLabel:string;ownerId:string|null;ownerLabel:string;state:keyof typeof leadStates;stateLabel:string;capturedAt:string};
export type LeadDetail=LeadRow&{facts:readonly (readonly [string,string])[];nextAction:string;taskId:string|null;opportunityId:string|null;action:null|{kind:'task';label:'前往原工作卡'}};
export type LeadPage={items:LeadRow[];nextCursor:string|null};
export type LeadSource={code:string;label:string;channel:string;assignmentMode:'MANUAL'|'AUTOMATIC';intakeLabel:string;supervisorLabel:string};
export type LeadManagementTransport={list:(s:WorkbenchSession,q:LeadQuery,signal:AbortSignal)=>Promise<LeadPage>;detail:(s:WorkbenchSession,id:string,signal:AbortSignal)=>Promise<LeadDetail>;sources:(s:WorkbenchSession,signal:AbortSignal)=>Promise<{items:LeadSource[]}>};
const object=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v);
const text=(v:unknown):v is string=>typeof v==='string';
const id=(v:unknown):v is string=>text(v)&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v);
const nullableId=(v:unknown)=>v===null||id(v);
const exact=(v:Record<string,unknown>,keys:readonly string[])=>Object.keys(v).sort().join()===Array.from(keys).sort().join();
const rowKeys=['id','customerLabel','contactLabel','sourceLabel','ownerId','ownerLabel','state','stateLabel','capturedAt'];
function row(v:unknown):v is LeadRow{return object(v)&&id(v.id)&&nullableId(v.ownerId)&&['customerLabel','contactLabel','sourceLabel','ownerLabel','stateLabel','capturedAt'].every(k=>text(v[k]))&&text(v.state)&&Object.hasOwn(leadStates,v.state)&&text(v.capturedAt)&&!Number.isNaN(Date.parse(v.capturedAt));}
export function createLeadManagementTransport(fetcher:typeof fetch=fetch):LeadManagementTransport {
 const {auth,assertCurrent,checked}=createSessionTransport(false);
 async function read(s:WorkbenchSession,path:string,signal:AbortSignal){const headers=await auth(s,signal);const response=await fetcher(path,{method:'GET',headers,signal,cache:'no-store'});assertCurrent(s,signal);const data:unknown=await response.json();assertCurrent(s,signal);if(response.status===403)throw new TransportError(403,'NOT_AUTHORIZED');checked(s,signal,{response,data,error:data});return data;}
 return {
  async list(s,q,signal){
   const params=new URLSearchParams({limit:'20'});for(const key of ['search','source','owner','state','cursor'] as const)if(q[key])params.set(key,q[key]!);
   const data=await read(s,'/api/v1/lead-management/leads?'+params,signal);
   if(!object(data)||!exact(data,['items','nextCursor'])||!Array.isArray(data.items)||data.items.length>20||!data.items.every(v=>row(v)&&exact(v,rowKeys))||new Set(data.items.map(v=>v.id)).size!==data.items.length||!(data.nextCursor===null||text(data.nextCursor)&&data.nextCursor.length<=512))throw Error('线索查询结果无法核对');return data as LeadPage;
  },
  async detail(s,requestId,signal){
   if(!id(requestId))throw Error('线索编号无法核对');const data=await read(s,'/api/v1/lead-management/leads/'+encodeURIComponent(requestId),signal);
   if(!row(data)||data.id!==requestId||!exact(data,[...rowKeys,'facts','nextAction','taskId','opportunityId','action']))throw Error('线索详情无法核对');
   const v=data as unknown as Record<string,unknown>;
   if(!Array.isArray(v.facts)||!v.facts.every(f=>Array.isArray(f)&&f.length===2&&f.every(text))||!text(v.nextAction)||!nullableId(v.opportunityId)||!(v.action===null?v.taskId===null:object(v.action)&&exact(v.action,['kind','label'])&&v.action.kind==='task'&&v.action.label==='前往原工作卡'&&id(v.taskId)))throw Error('原责任入口无法核对');return data as LeadDetail;
  },
  async sources(s,signal){
   const data=await read(s,'/api/v1/lead-management/sources',signal);
   if(!object(data)||!exact(data,['items'])||!Array.isArray(data.items)||data.items.length>50||!data.items.every(v=>object(v)&&exact(v,['code','label','channel','assignmentMode','intakeLabel','supervisorLabel'])&&text(v.code)&&/^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(v.code)&&['label','channel','intakeLabel','supervisorLabel'].every(k=>text(v[k]))&&['MANUAL','AUTOMATIC'].includes(String(v.assignmentMode)))||new Set(data.items.map(v=>v.code)).size!==data.items.length)throw Error('来源查询结果无法核对');return data as {items:LeadSource[]};
  }
 };
}
