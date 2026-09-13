package io.github.windyzhu3.ontologylaw.identity;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class AuthorityGrantCommandsIT extends IdentityAdminFixture {
    @Test void grantee_deny_becoming_effective_after_slot_is_checked_again_before_mutation()throws Exception {
        UUID org=organization(),app=appointment(principal(),org);var activates=java.time.Instant.now().plusSeconds(2);
        mutate("insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values (?,?,?,?,'IDENTITY_AUTHORITY_MANAGE','DENY',?::timestamptz,'ACTIVE',clock_timestamp(),'identity.appointment',?,0)",seed.tenant(),UUID.randomUUID(),seed.principal(),seed.appointment(),activates.toString(),app);
        var before=counts();var held=new java.util.concurrent.atomic.AtomicBoolean();
        try(var actual=database.apiConnection()) {
            var delayed=(java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(java.sql.Connection.class.getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{
                if(method.getName().equals("setSavepoint")&&held.compareAndSet(false,true)) {
                    long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);boolean active=false;
                    while(System.nanoTime()<deadline){active="YES".equals(scalar("select case when clock_timestamp()>=?::timestamptz then 'YES' else 'NO' end",activates.toString()));if(active)break;Thread.sleep(25);}assertTrue(active);
                }
                try{return method.invoke(actual,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
            });
            var result=runtime.execute(delayed,envelope(UUID.randomUUID(),"CREATE_AUTHORITY_GRANT",null,null,body("appointmentId",app.toString(),"scopeOrganizationId",org.toString(),"authorityCode","SALES_CONTACT_OWNER","validFrom",java.time.Instant.now().toString(),"validUntil",null)));
            rejected("NOT_AUTHORIZED",result);
        }
        assertTrue(held.get());delta(before,-1);
    }
    @Test void exact_grantee_deny_hides_candidate_and_rejects_known_id_before_slot()throws Exception {
        UUID org=organization(),app=appointment(principal(),org);
        mutate("insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values (?,?,?,?,'IDENTITY_AUTHORITY_MANAGE','DENY',clock_timestamp()-interval '1 hour','ACTIVE',clock_timestamp(),'identity.appointment',?,0)",seed.tenant(),UUID.randomUUID(),seed.principal(),seed.appointment(),app);
        try(var c=database.apiConnection()) {
            var options=new io.github.windyzhu3.ontologylaw.execution.IdentityAdminReadRuntime(audit,protection,candidates,"FIXTURE",directory).read(c,actor,"getIdentityAdminOptions","AUTHORITY_GRANTS","APPOINTMENT",50,null,null);
            @SuppressWarnings("unchecked") var page=(Map<String,Object>)options.get("candidates");
            assertFalse(IdentityAdminReadIT.items(page).stream().anyMatch(i->app.toString().equals(i.get("id"))));
        }
        var before=counts();
        assertEquals("NOT_AUTHORIZED",assertThrows(IdentityCommands.Failure.class,()->grant(app,org)).code());
        assertEquals(before,counts());
    }
    @Test void grant_is_exact_immutable_then_revoked_without_any_event()throws Exception {UUID org=organization(),app=appointment(principal(),org);var before=counts();UUID id=grant(app,org);delta(before,3);before=counts();success(change("REVOKE_AUTHORITY_GRANT",id));delta(before,-1);rejected("IDENTITY_STATE_CONFLICT",change("REVOKE_AUTHORITY_GRANT",id));}
    @ParameterizedTest @ValueSource(strings={"IDENTITY_PRINCIPAL_MANAGE","IDENTITY_ORGANIZATION_MANAGE","IDENTITY_APPOINTMENT_MANAGE","IDENTITY_AUTHORITY_MANAGE","REOPEN_DUE_CONTACT_TASKS","ARBITRARY"})
    void non_business_authority_is_preslot_validation(String code)throws Exception {var before=counts();assertThrows(IdentityCommands.Failure.class,()->execute("CREATE_AUTHORITY_GRANT",null,null,body("appointmentId",seed.appointment().toString(),"scopeOrganizationId",seed.org().toString(),"authorityCode",code,"validFrom","2026-01-01T00:00:00Z","validUntil",null)));assertEquals(before,counts());}
    @Test void self_grant_to_any_appointment_of_caller_is_refused()throws Exception {UUID org=organization(),app=appointment(seed.principal(),org);rejected("IDENTITY_SELF_LOCKOUT",execute("CREATE_AUTHORITY_GRANT",null,null,body("appointmentId",app.toString(),"scopeOrganizationId",org.toString(),"authorityCode","SALES_CONTACT_OWNER","validFrom",java.time.Instant.now().toString(),"validUntil",null)));}
    @Test void last_root_management_grant_cannot_be_revoked()throws Exception {rejected("IDENTITY_LAST_ADMIN",change("REVOKE_AUTHORITY_GRANT",seed.grant()));}
    @Test void grant_window_outside_frozen_appointment_is_terminal_refusal()throws Exception {UUID org=organization(),app=appointment(principal(),org);rejected("IDENTITY_STATE_CONFLICT",execute("CREATE_AUTHORITY_GRANT",null,null,body("appointmentId",app.toString(),"scopeOrganizationId",org.toString(),"authorityCode","SALES_CONTACT_OWNER","validFrom","2025-01-01T00:00:00Z","validUntil",null)));}
}
