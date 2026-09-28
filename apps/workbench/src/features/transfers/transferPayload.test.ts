import {expect,it} from 'vitest';
import {correctionPayload,classificationPayload} from './transferPayload';
const basis={clientIdentity:{versionId:'original-client',sha256:'a'.repeat(64)},signatureArchive:{versionId:'original-signature',sha256:'b'.repeat(64)},explanation:'原交接说明',consistencyChecked:true as const,corrections:[] as []};
it('retains the checked submission and replaces only explicitly returned material',()=>{
 const material={versionId:'corrected',sha256:'c'.repeat(64)},result=correctionPayload(basis,[{id:'r1',requirement:'CLIENT_IDENTITY'}],{corrections:[{returnItemId:'r1',response:'补齐证明',material}]});
 expect(result.clientIdentity).toEqual(material);expect(result.signatureArchive).toEqual(basis.signatureArchive);expect(basis.clientIdentity.versionId).toBe('original-client');expect(result.corrections).toHaveLength(1);
});
it('allows explanation-only correction without silently substituting materials',()=>{
 const result=correctionPayload(basis,[{id:'r1',requirement:'HANDOVER_EXPLANATION'}],{corrections:[{returnItemId:'r1',response:'补充交接说明'}]});expect(result.explanation).toBe('补充交接说明');expect(result.clientIdentity).toEqual(basis.clientIdentity);
});
it('rejects missing, duplicate and foreign return items before submission',()=>{
 for(const corrections of [[],[{returnItemId:'other',response:'答复'}],[{returnItemId:'r1',response:'一'},{returnItemId:'r1',response:'二'}]])expect(()=>correctionPayload(basis,[{id:'r1',requirement:'HANDOVER_EXPLANATION'}],{corrections})).toThrow();
 expect(()=>correctionPayload(basis,[{id:'r1',requirement:'CLIENT_IDENTITY'}],{corrections:[{returnItemId:'r1',response:'缺材料'}]})).toThrow();
});
it('maps the approved classification form to the exact server command',()=>{expect(classificationPayload({matterId:'case',classification:'GENERAL',receivingAppointmentId:'receiver',explanation:'说明'})).toEqual({matterId:'case',category:'GENERAL',recipient:'receiver',explanation:'说明'});});
