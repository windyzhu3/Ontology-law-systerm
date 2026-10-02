import createClient from 'openapi-fetch';
import type {components,paths,operations} from '../../generated/api/schema';
import {createSessionTransport,type WorkbenchSession} from '../../lib/sessionTransport';
import {uuidPattern} from '../session/recoveryMarker';
export type AuditRecord=components['schemas']['AuditRecordV1'];
export type AuditPage=components['schemas']['AuditRecordPageV1'];
export type AuditQuery=NonNullable<operations['listAuditRecords']['parameters']['query']>;
export type AuditRelation='CORRELATION'|'CORRECTION';
const strings=['authorizationPathLabel','recordOrganizationLabel','onBehalfLabel','actorLabel','appointmentLabel','objectLabel','actionLabel','scopeLabel','resultLabel','summary','verificationLabel'] as const;
export function validAuditRecord(value:unknown):value is AuditRecord{
 if(!value||typeof value!=='object')return false;const row=value as Record<string,unknown>;
 return Object.keys(row).sort().join()===['id','trustedAt',...strings,'hasCorrelation','hasCorrection'].sort().join()&&typeof row.id==='string'&&uuidPattern.test(row.id)&&typeof row.trustedAt==='string'&&Number.isFinite(Date.parse(row.trustedAt))&&strings.every(k=>typeof row[k]==='string'&&[...row[k]].length<=200&&!/[\p{Cc}\p{Cf}]/u.test(row[k]))&&typeof row.hasCorrelation==='boolean'&&typeof row.hasCorrection==='boolean';
}
function validPage(value:unknown):value is AuditPage{if(!value||typeof value!=='object')return false;const page=value as Record<string,unknown>;return Object.keys(page).every(k=>['items','nextCursor'].includes(k))&&Array.isArray(page.items)&&page.items.length<=50&&page.items.every(validAuditRecord)&&new Set(page.items.map(r=>r.id)).size===page.items.length&&(page.nextCursor===undefined||typeof page.nextCursor==='string'&&/^[A-Za-z0-9_-]{1,2048}$/.test(page.nextCursor));}
export function createAuditRecordApi(fetcher?:(request:Request)=>Promise<Response>,baseUrl=location.origin){
 const client=createClient<paths>({baseUrl,...(fetcher?{fetch:fetcher}:{})}),transport=createSessionTransport(false);
 async function read<T>(session:WorkbenchSession,signal:AbortSignal,dispatch:(headers:HeadersInit,middleware:Parameters<typeof transport.request>[2] extends (m:infer M)=>unknown?M:never)=>Promise<{response:Response;data?:unknown;error?:unknown}>,valid:(value:unknown)=>value is T):Promise<T>{
  if(session.selectedOnBehalfAppointmentId!==null)throw new Error('当前任职不能查询审计记录。');
  const headers=await transport.auth(session,signal),result=await transport.request(session,signal,m=>dispatch(headers,m));transport.assertCurrent(session,signal);
  if(!result.response.ok){transport.checked(session,signal,result);throw new Error('审计记录暂时不可用，请重读后再试。');}
  if(result.response.status!==200||!result.response.headers.get('Cache-Control')?.toLowerCase().includes('no-store')||!valid(result.data))throw new Error('审计记录暂时不可用，请重读后再试。');return result.data;
 }
 return {
  list:(session:WorkbenchSession,query:AuditQuery,signal:AbortSignal)=>read(session,signal,(headers,middleware)=>client.GET('/api/v1/admin/audit-records',{params:{query},headers,middleware:[middleware],signal,cache:'no-store'}),validPage),
  detail:(session:WorkbenchSession,id:string,signal:AbortSignal)=>read(session,signal,(headers,middleware)=>client.GET('/api/v1/admin/audit-records/{auditRecordId}',{params:{path:{auditRecordId:id}},headers,middleware:[middleware],signal,cache:'no-store'}),validAuditRecord),
  related:(session:WorkbenchSession,id:string,relation:AuditRelation,query:AuditQuery,signal:AbortSignal)=>read(session,signal,(headers,middleware)=>client.GET('/api/v1/admin/audit-records/{auditRecordId}/related',{params:{path:{auditRecordId:id},query:{...query,relation}},headers,middleware:[middleware],signal,cache:'no-store'}),validPage),
 };
}
export type AuditRecordApi=ReturnType<typeof createAuditRecordApi>;
