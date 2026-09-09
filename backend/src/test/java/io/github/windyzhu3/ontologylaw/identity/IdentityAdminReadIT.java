package io.github.windyzhu3.ontologylaw.identity;

import io.github.windyzhu3.ontologylaw.audit.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdentityAdminReadIT extends IdentityAdminFixture {
    @Test void object_allow_does_not_replace_direct_management_authority()throws Exception {
        revoke();mutate("insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values (?,?,?,?,'IDENTITY_ORGANIZATION_MANAGE','ALLOW',clock_timestamp()-interval '1 hour','ACTIVE',clock_timestamp(),'identity.organization_unit',?,0)",seed.tenant(),UUID.randomUUID(),seed.principal(),seed.appointment(),seed.org());var before=counts();assertThrows(IdentityCommands.Failure.class,()->read("listOrganizationUnits",null,null,20,null));assertEquals(before,counts());
    }
    @Test void final_disclosure_rechecks_each_exact_resource_when_a_scheduled_deny_becomes_effective()throws Exception {
        UUID org=organization();var activates=Instant.now().plusSeconds(2);
        mutate("insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values (?,?,?,?,'IDENTITY_ORGANIZATION_MANAGE','DENY',?::timestamptz,'ACTIVE',clock_timestamp(),'identity.organization_unit',?,0)",seed.tenant(),UUID.randomUUID(),seed.principal(),seed.appointment(),activates.toString(),org);
        var before=counts();var finalCheck=new java.util.concurrent.atomic.AtomicInteger();
        try(var actual=database.apiConnection()) {
            var delayed=(Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                if(method.getName().equals("prepareStatement")&&args[0] instanceof String sql&&sql.startsWith("select distinct scope_organization_unit_id")&&finalCheck.incrementAndGet()==2) {
                    long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);boolean active=false;
                    while(System.nanoTime()<deadline){active="YES".equals(scalar("select case when clock_timestamp()>=?::timestamptz then 'YES' else 'NO' end",activates.toString()));if(active)break;Thread.sleep(25);}assertTrue(active);
                }
                try{return method.invoke(actual,args);}catch(java.lang.reflect.InvocationTargetException error){throw error.getCause();}
            });
            assertThrows(IdentityCommands.Failure.class,()->new IdentityAdminReadRuntime(audit,protection,candidates,"FIXTURE",directory).read(delayed,actor,"listOrganizationUnits",null,null,20,null,null));
        }
        assertEquals(2,finalCheck.get());assertEquals(before,counts());
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"2026-01-01T00:00:00.123456789Z","2026-01-01T00:00:00.999999999Z"}) void original_receipt_preserves_postgres_timestamp_precision_for_immutable_terms(String instant)throws Exception {
        UUID key=UUID.randomUUID();success(execute(key,"CREATE_APPOINTMENT",null,null,body("principalId",principal().toString(),"organizationId",organization().toString(),"roleCode","CONTACT_OPERATOR","effectiveFrom",instant,"effectiveUntil",null)));
        assertEquals("SUCCEEDED",assertDoesNotThrow(()->receipt(actor,key),"Immutable term comparisons must use PostgreSQL timestamp precision").get("outcome"));
    }
    @Test void known_foreign_tenant_identity_ids_do_not_disclose_or_occupy_a_slot()throws Exception {
        var foreign=AuthorizationServiceIT.seed(database);var before=counts();assertThrows(IdentityCommands.Failure.class,()->execute("RENAME_ORGANIZATION_UNIT",foreign.org(),"\"identity."+"A".repeat(43)+"\"",Map.of("displayName","Foreign")));assertEquals(before,counts());
        assertFalse(items(read("listOrganizationUnits",null,null,50,null)).stream().anyMatch(i->i.get("id").equals(foreign.org().toString())));assertEquals("0",scalar("select count(*) from execution.command_execution_slot where tenant_id=?",foreign.tenant()));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"PRINCIPAL,providerCode","PRINCIPAL,subjectHmac","ORGANIZATION,parentId","ORGANIZATION,code","APPOINTMENT,principalId","APPOINTMENT,organizationId","APPOINTMENT,roleCode","APPOINTMENT,effectiveFrom","APPOINTMENT,effectiveUntil","AUTHORITY_GRANT,appointmentId","AUTHORITY_GRANT,authorityCode","AUTHORITY_GRANT,scopeOrganizationId","AUTHORITY_GRANT,validFrom","AUTHORITY_GRANT,validUntil"})
    void all_immutable_create_bindings_are_checked_against_the_current_identity_fact(String kind,String field)throws Exception {
        UUID key=UUID.randomUUID();String command;Map<String,Object> payload;
        switch(kind) {
            case "PRINCIPAL"->{command="CREATE_IDENTITY_PRINCIPAL";payload=Map.of("providerUserSelector",candidates.issue(actor,"FIXTURE",directory.issuer(),"synthetic",UUID.randomUUID().toString(),Instant.now()),"displayName","Created");}
            case "ORGANIZATION"->{command="CREATE_ORGANIZATION_UNIT";payload=Map.of("parentOrganizationId",seed.org().toString(),"code","BOUND","displayName","Bound");}
            case "APPOINTMENT"->{command="CREATE_APPOINTMENT";payload=body("principalId",principal().toString(),"organizationId",organization().toString(),"roleCode","CONTACT_OPERATOR","effectiveFrom","2026-01-01T00:00:00Z","effectiveUntil",null);}
            default->{command="CREATE_AUTHORITY_GRANT";UUID org=organization(),app=appointment(principal(),org);payload=body("appointmentId",app.toString(),"scopeOrganizationId",org.toString(),"authorityCode","SALES_CONTACT_OWNER","validFrom",Instant.now().toString(),"validUntil",null);}
        }
        success(execute(key,command,null,null,payload));assertEquals("SUCCEEDED",receipt(actor,key).get("outcome"));
        try(var c=database.apiConnection()) {
            c.setAutoCommit(false);io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.setLocalRole(c,io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.Capability.QUERY);
            var original=CommandReceiptAuthorizationReader.databaseBacked().read(c,actor,key);var metadata=original.recovery();var scope=new LinkedHashMap<String,Object>(metadata.identityScope());
            @SuppressWarnings("unchecked") var target=new LinkedHashMap<>((Map<String,Object>)scope.get("target"));
            Object changed=field.endsWith("Id")?UUID.randomUUID().toString():field.endsWith("From")?"2000-01-01T00:00:00Z":field.endsWith("Until")?"2099-01-01T00:00:00Z":switch(field){case "subjectHmac"->Base64.getUrlEncoder().withoutPadding().encodeToString(key());case "roleCode"->"INTAKE_OPERATOR";case "authorityCode"->"LEAD_ASSIGN";default->"OTHER";};target.put(field,changed);scope.put("target",target);
            var corrupted=new ReceiptRecoveryMetadata(command,Map.of("profile","R1_IDENTITY_RECEIPT_RECOVERY_V1","scope",scope,"target",IdentityCommandRuntime.selector(metadata.identityTarget()),"authorizationAnchor",IdentityCommandRuntime.selector(metadata.identityAnchor())));
            var forged=new CommandReceiptAuthorizationReader.Original(key,command,original.outcome(),original.subject(),original.scopeOrganization(),corrupted,original.resultFact(),original.rejectionCode());assertThrows(IdentityCommands.Failure.class,()->IdentityCommandRuntime.authorizeOriginal(c,actor,forged));c.rollback();
        }
    }
    Map<String,Object> read(String operation,String page,String option,Integer limit,String cursor)throws Exception {
        try(var c=database.apiConnection()){return new IdentityAdminReadRuntime(audit,protection,candidates,"FIXTURE",directory).read(c,actor,operation,page,option,limit,cursor,null);}
    }
    @SuppressWarnings("unchecked") static List<Map<String,Object>> items(Map<String,Object> response){return (List<Map<String,Object>>)response.get("items");}
    @Test void local_keyset_pages_are_bounded_nonoverlapping_and_tampered_or_cross_operation_cursors_never_disclose()throws Exception {
        organization();organization();organization();var first=read("listOrganizationUnits",null,null,2,null);assertEquals(2,items(first).size());String cursor=(String)first.get("nextCursor");assertNotNull(cursor);
        var second=read("listOrganizationUnits",null,null,2,cursor);assertEquals(2,items(second).size());var ids=new HashSet<Object>();items(first).forEach(i->assertTrue(ids.add(i.get("id"))));items(second).forEach(i->assertTrue(ids.add(i.get("id"))));
        var before=counts();assertThrows(IdentityCommands.Failure.class,()->read("listAppointments",null,null,2,cursor));assertThrows(IdentityCommands.Failure.class,()->read("listOrganizationUnits",null,null,2,cursor+"x"));assertEquals(before,counts());
    }
    @Test void options_use_page_authority_and_root_principal_boundary_not_option_kind()throws Exception {
        UUID local=organization(),hidden=organization();replaceGrants("IDENTITY_APPOINTMENT_MANAGE",local);
        assertEquals(List.of(local.toString()),items(options("APPOINTMENTS","ORGANIZATION")).stream().map(i->i.get("id")).toList());
        var before=counts();assertThrows(IdentityCommands.Failure.class,()->read("getIdentityAdminOptions","ORGANIZATIONS","ORGANIZATION",20,null));assertThrows(IdentityCommands.Failure.class,()->read("getIdentityAdminOptions","APPOINTMENTS","PRINCIPAL",20,null));assertThrows(IdentityCommands.Failure.class,()->read("listIdentityPrincipals",null,null,20,null));assertEquals(before,counts());assertNotEquals(local,hidden);
    }
    @SuppressWarnings("unchecked") Map<String,Object> options(String page,String option)throws Exception{return (Map<String,Object>)read("getIdentityAdminOptions",page,option,20,null).get("candidates");}
    @Test void revoked_cursor_scope_does_not_recover_and_new_scope_never_reuses_old_cursor()throws Exception {
        UUID local=organization();organization();var first=read("listOrganizationUnits",null,null,1,null);String cursor=(String)first.get("nextCursor");replaceGrants("IDENTITY_ORGANIZATION_MANAGE",local);var before=counts();assertThrows(IdentityCommands.Failure.class,()->read("listOrganizationUnits",null,null,1,cursor));assertEquals(before,counts());assertEquals(List.of(local.toString()),items(read("listOrganizationUnits",null,null,50,null)).stream().map(i->i.get("id")).toList());
    }
    @Test void disclosure_audit_failure_rolls_back_and_returns_no_body()throws Exception {
        organization();var before=counts();AuditAppender unavailable=(c,e)->{throw new SQLException("injected audit failure","08006");};
        try(var c=database.apiConnection()){assertThrows(SQLException.class,()->new IdentityAdminReadRuntime(unavailable,protection,candidates,"FIXTURE",directory).read(c,actor,"listOrganizationUnits",null,null,20,null,null));}assertEquals(before,counts());
    }
    @Test void original_receipt_requires_same_actor_current_grant_and_can_recover_with_new_valid_grant()throws Exception {
        UUID key=UUID.randomUUID();var result=execute(key,"CREATE_ORGANIZATION_UNIT",null,null,Map.of("parentOrganizationId",seed.org().toString(),"code","RECOVERY","displayName","Recovery"));success(result);
        assertEquals("SUCCEEDED",receipt(actor,key).get("outcome"));UUID otherApp=appointment(seed.principal(),seed.org());var otherActor=new Actor(seed.tenant(),seed.principal(),otherApp,null,null);
        assertEquals(404,assertThrows(CommandReceiptReadRuntime.Failure.class,()->receipt(otherActor,key)).status());
        revoke();assertEquals(403,assertThrows(CommandReceiptReadRuntime.Failure.class,()->receipt(actor,key)).status());addGrant("IDENTITY_ORGANIZATION_MANAGE",seed.org());assertEquals("SUCCEEDED",receipt(actor,key).get("outcome"));
    }
    @Test void rejected_original_receipt_remains_rejected_after_target_advances_and_bootstrap_is_not_public()throws Exception {
        UUID org=organization(),key=UUID.randomUUID();String old=tag("RENAME_ORGANIZATION_UNIT",org);success(execute("RENAME_ORGANIZATION_UNIT",org,old,Map.of("displayName","Advanced")));
        rejected("STALE_IDENTITY",execute(key,"RENAME_ORGANIZATION_UNIT",org,old,Map.of("displayName","Rejected")));success(execute("RENAME_ORGANIZATION_UNIT",org,tag("RENAME_ORGANIZATION_UNIT",org),Map.of("displayName","Later")));
        var before=counts();assertEquals("REJECTED",receipt(actor,key).get("outcome"));var after=counts();assertEquals(before.get(4),after.get(4));assertEquals(before.get(5),after.get(5));assertEquals(before.get(6)+1,after.get(6));
        UUID bootstrap=UUID.fromString(scalar("select command_id from execution.command_execution_slot where tenant_id=? and command_type='BOOTSTRAP_IDENTITY_ADMIN'",seed.tenant()));assertEquals(404,assertThrows(CommandReceiptReadRuntime.Failure.class,()->receipt(actor,bootstrap)).status());
    }
    @Test void recovery_rejects_scope_that_does_not_match_created_immutable_identity_fact()throws Exception {
        UUID key=UUID.randomUUID();success(execute(key,"CREATE_ORGANIZATION_UNIT",null,null,Map.of("parentOrganizationId",seed.org().toString(),"code","BOUND","displayName","Bound")));
        try(var c=database.apiConnection()) {
            c.setAutoCommit(false);io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.setLocalRole(c,io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.Capability.QUERY);
            var original=CommandReceiptAuthorizationReader.databaseBacked().read(c,actor,key);var metadata=original.recovery();var scope=new LinkedHashMap<String,Object>(metadata.identityScope());
            scope.put("target",Map.of("kind","CREATE_ORGANIZATION","parentId",seed.org().toString(),"code","FORGED"));
            var corrupted=new ReceiptRecoveryMetadata(original.commandType(),Map.of("profile","R1_IDENTITY_RECEIPT_RECOVERY_V1","scope",scope,"target",IdentityCommandRuntime.selector(metadata.identityTarget()),"authorizationAnchor",IdentityCommandRuntime.selector(metadata.identityAnchor())));
            var forged=new CommandReceiptAuthorizationReader.Original(key,original.commandType(),original.outcome(),original.subject(),original.scopeOrganization(),corrupted,original.resultFact(),original.rejectionCode());
            assertThrows(IdentityCommands.Failure.class,()->IdentityCommandRuntime.authorizeOriginal(c,actor,forged));c.rollback();
        }
    }
    @Test void replayed_stale_rejection_has_current_tag_without_reexecuting_the_mutation()throws Exception {
        UUID org=organization(),key=UUID.randomUUID();String old=tag("RENAME_ORGANIZATION_UNIT",org);success(execute("RENAME_ORGANIZATION_UNIT",org,old,Map.of("displayName","Advanced")));var body=Map.<String,Object>of("displayName","Stale");rejected("STALE_IDENTITY",execute(key,"RENAME_ORGANIZATION_UNIT",org,old,body));
        var before=counts();var replay=execute(key,"RENAME_ORGANIZATION_UNIT",org,old,body);assertTrue(replay.replay());assertEquals(tag("RENAME_ORGANIZATION_UNIT",org),replay.currentETag());assertEquals(before,counts());
    }
    Map<String,Object> receipt(Actor reader,UUID key)throws Exception {
        R1AuthorizationFacts business=new R1AuthorizationFacts(){public Capture capture(Connection c,UUID tenant,String account,String digest){throw new AssertionError("Identity must not read business facts");}public Task task(Connection c,UUID tenant,UUID id,Instant now){throw new AssertionError("Identity must not read tasks");}};
        try(var c=database.apiConnection()){return new CommandReceiptReadRuntime(business,audit).read(c,reader,key,UUID.randomUUID()).body();}
    }
    void revoke()throws Exception {mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and state='ACTIVE'",seed.tenant());}
    void addGrant(String code,UUID scope)throws Exception {mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,?,clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),seed.appointment(),seed.appointment(),scope,code);}
    void replaceGrants(String code,UUID scope)throws Exception{revoke();addGrant(code,scope);}
}
