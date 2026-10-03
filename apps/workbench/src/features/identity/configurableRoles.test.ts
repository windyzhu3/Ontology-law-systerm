import { expect, it } from "vitest";
import { validIdentityOriginal, validIdentityRead, validIdentityQuery } from "./identityContract";
const id="019c7000-0000-7000-8000-000000000001";
const etag='"identity.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"';
it("accepts custom role syntax without inventing a permission grant",()=>{
 expect(validIdentityOriginal({commandType:"CREATE_APPOINTMENT",key:id,body:{principalId:id,organizationId:id,roleCode:"CUSTOM_ADVISOR",effectiveFrom:"2026-10-01T00:00:00Z",effectiveUntil:null}})).toBe(true);
 expect(validIdentityOriginal({commandType:"CREATE_APPOINTMENT_ROLE",key:id,body:{code:"CUSTOM_ADVISOR",displayName:"业务顾问",authorityCode:"LEAD_CAPTURE"}})).toBe(false);
 expect(validIdentityOriginal({commandType:"CREATE_APPOINTMENT_ROLE",key:id,body:{code:"CUSTOM_ADVISOR",displayName:"业务顾问"}})).toBe(true);
});
it("reads paginated role choices with stable identity, code and server label",()=>{
 const query={page:"APPOINTMENTS",optionKind:"ROLE",limit:20};
 expect(validIdentityQuery("getIdentityAdminOptions",query)).toBe(true);
 expect(validIdentityRead("getIdentityAdminOptions",{...query,limit:undefined} ,query)).toBe(false);
 expect(validIdentityRead("getIdentityAdminOptions",{page:"APPOINTMENTS",optionKind:"ROLE",roleCodes:[],grantableAuthorityCodes:[],candidates:{items:[{id,code:"CUSTOM_ADVISOR",label:"业务顾问"}],nextCursor:"next-page"}},query)).toBe(true);
});
it("shows current server role name on an existing appointment",()=>{
 expect(validIdentityRead("listAppointments",{items:[{id,principal:{id,label:"销售甲"},organization:{id,label:"销售一部"},roleCode:"CUSTOM_ADVISOR",roleName:"高级顾问",effectiveFrom:"2026-10-01T00:00:00Z",effectiveUntil:null,state:"ACTIVE",etag}],nextCursor:null},{})).toBe(true);
});
