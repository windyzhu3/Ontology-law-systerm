import {it,expect} from 'vitest';
import {validIdentityOriginal,validIdentityRead} from './identityContract';
import {authorityLabels} from './identityLabels';
import {authorities,rows} from './identityWriteFixtures';
const codes=['SALES_OPPORTUNITY_OWNER','OPPORTUNITY_OWNER_EXCEPTION_DISCOVER','OPPORTUNITY_OWNER_EXCEPTION_READ','OPPORTUNITY_OWNER_EXCEPTION_RESOLVE','OPPORTUNITY_OWNER_EXCEPTION_OPERATIONS_READ'] as const;
const id='11111111-1111-4111-8111-111111111111';
const query={page:'AUTHORITY_GRANTS',optionKind:'APPOINTMENT'};
const options={...query,candidates:{items:[],nextCursor:null},roleCodes:[],grantableAuthorityCodes:authorities};
const write=(authorityCode:string)=>({commandType:'CREATE_AUTHORITY_GRANT',key:id,body:{appointmentId:id,authorityCode,scopeOrganizationId:id,validFrom:'2026-09-15T01:00:00Z',validUntil:null}});
it('accepts the complete registered T01 options response',()=>expect(validIdentityRead('getIdentityAdminOptions',options,query)).toBe(true));
it.each(codes)('accepts registered %s reads and writes with a Chinese label',code=>{
 expect(validIdentityOriginal(write(code))).toBe(true);
 expect(validIdentityRead('listAuthorityGrants',{items:[{...rows['/admin/identity/authority-grants'],authorityCode:code}],nextCursor:null},{})).toBe(true);
 expect(authorityLabels[code]).toMatch(/[\u4e00-\u9fff]/);
});
it('still rejects SYSTEM_ADMIN in grant writes, projected rows, and expanded option arrays',()=>{
 expect(validIdentityOriginal(write('SYSTEM_ADMIN'))).toBe(false);
 expect(validIdentityRead('listAuthorityGrants',{items:[{...rows['/admin/identity/authority-grants'],authorityCode:'SYSTEM_ADMIN'}],nextCursor:null},{})).toBe(false);
 expect(validIdentityRead('getIdentityAdminOptions',{...options,grantableAuthorityCodes:[...authorities,'SYSTEM_ADMIN']},query)).toBe(false);
});
