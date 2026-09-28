import {it,expect} from 'vitest';
import {validIdentityOriginal,validIdentityRead} from './identityContract';
import {authorityLabels} from './identityLabels';
import {rows} from './identityWriteFixtures';
const codes=['CONTRACT_SIGNATURE_VERIFY','CONTRACT_EXECUTION_VERIFY','CONTRACT_TERMINATION_REVIEW','PAYMENT_SUBMIT','PAYMENT_CONFIRM','TRANSFER_SUBMIT','TRANSFER_REVIEW','TRANSFER_ACCEPT','MATTER_CLASSIFY','MATTER_RECEIVE'];
it.each(codes)('supports existing sales-chain management authority %s in reads and writes',code=>{
 const id='11111111-1111-4111-8111-111111111111';
 expect(validIdentityOriginal({commandType:'CREATE_AUTHORITY_GRANT',key:id,body:{appointmentId:id,authorityCode:code,scopeOrganizationId:id,validFrom:'2026-09-27T01:00:00Z',validUntil:null}})).toBe(true);
 expect(validIdentityRead('listAuthorityGrants',{items:[{...rows['/admin/identity/authority-grants'],authorityCode:code}],nextCursor:null},{})).toBe(true);
 expect((authorityLabels as Record<string,string>)[code]).toMatch(/[\u4e00-\u9fff]/);
});
