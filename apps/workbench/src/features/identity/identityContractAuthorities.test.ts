import {it,expect} from 'vitest';
import {validIdentityOriginal,validIdentityRead} from './identityContract';
import {authorityLabels} from './identityLabels';
import {rows} from './identityWriteFixtures';
const codes=['CONTRACT_READ','CONTRACT_PREPARE','CONTRACT_PREPARATION_DECIDE','CONTRACT_REVIEW','CONTRACT_APPROVE'] as const;
it.each(codes)('retains named authorization %s across grant writes and reads',code=>{const id='11111111-1111-4111-8111-111111111111';expect(validIdentityOriginal({commandType:'CREATE_AUTHORITY_GRANT',key:id,body:{appointmentId:id,authorityCode:code,scopeOrganizationId:id,validFrom:'2026-09-20T01:00:00Z',validUntil:null}})).toBe(true);expect(validIdentityRead('listAuthorityGrants',{items:[{...rows['/admin/identity/authority-grants'],authorityCode:code}],nextCursor:null},{})).toBe(true);expect(authorityLabels[code]).toMatch(/[\u4e00-\u9fff]/);});
