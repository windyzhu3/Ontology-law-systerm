import {expect,it} from 'vitest';
import {createAuditRecordApi,validAuditRecord} from './auditRecordApi';
import {testSession} from '../../test/fixtures';
const row={id:'11111111-1111-4111-8111-111111111111',trustedAt:'2026-10-01T01:00:00Z',authorizationPathLabel:'直接授权',recordOrganizationLabel:'销售二部',onBehalfLabel:'本人办理',actorLabel:'销售五',appointmentLabel:'销售二部 · 销售',objectLabel:'线索',actionLabel:'执行操作',scopeLabel:'对象',resultLabel:'成功',summary:'安全摘要',verificationLabel:'已核验',hasCorrelation:true,hasCorrection:false};
it('rejects extra raw metadata and names containing controls',()=>{expect(validAuditRecord(row)).toBe(true);expect(validAuditRecord({...row,authorizationEvidence:'HMAC_SECRET'})).toBe(false);expect(validAuditRecord({...row,actorLabel:'坏\n姓名'})).toBe(false);});
it('queries through the current own actor and invalidates denied sessions',async()=>{
 const requests:Request[]=[];let denied=false,invalidated=0;const session={...testSession(),invalidate:()=>{invalidated++;}};
 const api=createAuditRecordApi(async request=>{requests.push(request);return new Response(JSON.stringify(denied?{}:{items:[row]}),{status:denied?403:200,headers:{'Content-Type':'application/json','Cache-Control':'no-store'}});},location.origin);
 expect((await api.list(session,{limit:20},new AbortController().signal)).items).toHaveLength(1);expect(requests[0].headers.get('X-Appointment-Id')).toBe(session.selectedAppointmentId);expect(requests[0].method).toBe('GET');denied=true;await expect(api.list(session,{limit:20},new AbortController().signal)).rejects.toThrow();expect(invalidated).toBeGreaterThan(0);
});
