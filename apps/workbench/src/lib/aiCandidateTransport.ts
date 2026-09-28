import type {components} from '../generated/api/schema';
import {createSessionTransport,TransportError,type WorkbenchSession} from './sessionTransport';
import {createOpportunityLedgerTransport} from './opportunityLedgerTransport';
type S=components['schemas'];
export type AiTask=S['AiCandidateTaskV1'];
export type AiItem=S['AiCandidateItemV1'];
export type AiField=AiItem['field'];
export type AiCandidate=S['AiCandidateResultV1']&{opportunityId:string};
export type AiTarget={opportunityId:string}|{taskId:string};
export const aiFields={FIELDS:['customerName','contactName','contactPhone','customerGoal'],SUMMARY:['progressSummary'],MATERIALS:['CONTRACT_BUSINESS','CORRESPONDENCE']} as const;
export const aiLabels:Record<AiField,string>={customerName:'客户名称',contactName:'联系人',contactPhone:'联系电话',customerGoal:'客户目标',progressSummary:'跟进摘要',CONTRACT_BUSINESS:'合同及业务资料',CORRESPONDENCE:'相关往来记录'};
export type AiCandidateTransport={generate:(s:WorkbenchSession,target:AiTarget,task:AiTask,signal:AbortSignal)=>Promise<AiCandidate>;recheck:(s:WorkbenchSession,target:AiTarget,candidate:AiCandidate,signal:AbortSignal)=>Promise<void>};
const object=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v);
const exact=(v:Record<string,unknown>,keys:string[])=>Object.keys(v).sort().join()===keys.sort().join();
const text=(v:unknown,max:number):v is string=>typeof v==='string'&&v.trim().length>0&&v.length<=max&&!/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/.test(v);
const uuid=(v:unknown):v is string=>typeof v==='string'&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v);
function valid(value:unknown,task:AiTask):value is S['AiCandidateResultV1'] {
 if(!object(value)||!exact(value,['task','items','sources','sourceToken','generatedAt'])||value.task!==task||!text(value.sourceToken,512)||!text(value.generatedAt,64)||!/^\d{4}-\d{2}-\d{2}T/.test(value.generatedAt)||!Number.isFinite(Date.parse(value.generatedAt))||!Array.isArray(value.sources)||!value.sources.length||value.sources.length>50||!Array.isArray(value.items)||!value.items.length||value.items.length>aiFields[task].length)return false;
 const sources=new Map<string,{kind:string;text:string}>();let total=0;
 for(const source of value.sources){if(!object(source)||!exact(source,['id','label','kind','text'])||!text(source.id,100)||!/^[A-Za-z0-9:_-]+$/.test(source.id)||sources.has(source.id)||!text(source.label,200)||!text(source.kind,20)||!['TEXT','PROGRESS','MATERIAL','RULE'].includes(source.kind)||!text(source.text,32000))return false;sources.set(source.id,{kind:source.kind,text:source.text});total+=source.id.length+source.label.length+source.text.length;}
 if(total>32000)return false;const fields=new Set<string>();
 for(const item of value.items){
  if(!object(item)||!exact(item,['field','status','value','citations'])||!text(item.field,50)||!(aiFields[task] as readonly string[]).includes(item.field)||fields.has(item.field)||!['CANDIDATE','MISSING','CONFLICT'].includes(String(item.status))||(item.status==='CANDIDATE'?!text(item.value,item.field==='contactPhone'?50:['customerName','contactName'].includes(item.field)?200:2000):item.value!==null)||!Array.isArray(item.citations)||!item.citations.length||item.citations.length>5)return false;
  fields.add(item.field);const citations=new Set<string>();let rule=false;
  for(const citation of item.citations){if(!object(citation)||!exact(citation,['sourceId','quote'])||!text(citation.sourceId,100)||!text(citation.quote,500)||!sources.get(citation.sourceId)?.text.includes(citation.quote)||citations.has(JSON.stringify(citation)))return false;citations.add(JSON.stringify(citation));rule||=sources.get(citation.sourceId)?.kind==='RULE';}
  if(task==='MATERIALS'&&!rule)return false;
 }
 return true;
}
export function createAiCandidateTransport(fetcher:typeof fetch=fetch):AiCandidateTransport {
 const {auth,assertCurrent,checked}=createSessionTransport(false),ledger=createOpportunityLedgerTransport(fetcher);
 async function resolve(s:WorkbenchSession,target:AiTarget,signal:AbortSignal){
  if('opportunityId' in target){if(!uuid(target.opportunityId))throw Error('商机依据无法核对');return target.opportunityId;}
  if(!uuid(target.taskId))throw Error('原事项无法核对');const detail=await ledger.taskContext(s,target.taskId,signal);
  if(!detail.canHandle||detail.task?.id!==target.taskId)throw new TransportError(403,'NOT_AUTHORIZED');return detail.opportunity.id;
 }
 async function post(s:WorkbenchSession,id:string,task:AiTask,recheck:boolean,body:object,signal:AbortSignal){
  const headers=await auth(s,signal);const response=await fetcher('/api/v1/opportunities/'+id+'/ai-candidates/'+task+(recheck?'/recheck':''),{method:'POST',headers:{...headers,'Content-Type':'application/json'},body:JSON.stringify(body),signal,cache:'no-store'});assertCurrent(s,signal);
  const data:unknown=response.status===204?undefined:await response.json();assertCurrent(s,signal);if(response.status===403)throw new TransportError(403,'NOT_AUTHORIZED');checked(s,signal,{response,data,error:data});
  if(response.status!==(recheck?204:200))throw Error('AI 响应无法核对');return data;
 }
 return {
  async generate(s,target,task,signal){if(!Object.hasOwn(aiFields,task))throw Error('AI 种类无法核对');await auth(s,signal);const id=await resolve(s,target,signal);const result=await post(s,id,task,false,{},signal);if(!valid(result,task))throw Error('AI 建议无法核对');return {...result,opportunityId:id};},
  async recheck(s,target,candidate,signal){await auth(s,signal);const id=await resolve(s,target,signal);if(id!==candidate.opportunityId)throw new TransportError(412,'STALE_SUBJECT');await post(s,id,candidate.task,true,{sourceToken:candidate.sourceToken},signal);}
 };
}
