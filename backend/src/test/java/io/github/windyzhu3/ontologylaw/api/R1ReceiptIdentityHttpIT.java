package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.execution.R1BusinessFence;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class R1ReceiptIdentityHttpIT extends R1HttpFixture {
    @Test void database_lock_timeout_returns_safe_503_without_a_disclosure()throws Exception {
        setupContact();var command=prepare(contact("CONNECTED_VALID"));execute(command);
        disclosureConnection=c->{try(var s=c.createStatement()){s.execute("set lock_timeout='100ms'");return c;}catch(java.sql.SQLException e){throw new AssertionError(e);}};
        try(var http=new HttpHarness();var writer=database.apiConnection()) {writer.setAutoCommit(false);setLocalRole(writer,Capability.COMMAND);R1BusinessFence.databaseBacked().exclusive(writer,seed.tenant());var before=counts();
            try{var response=http.request("GET","/api/v1/commands/"+command.commandId()+"/receipt",null,Map.of());assertEquals(503,response.statusCode(),response.body());assertTrue(response.headers().firstValue("ETag").isEmpty());assertFalse(response.body().contains("receiptRef"));assertEquals(before,counts());}finally{writer.rollback();}}
    }
    @ParameterizedTest @ValueSource(strings={"DELEGATION_REVOKED","DELEGATION_EXPIRED","ORGANIZATION_CLOSED"})
    void original_delegated_receipt_rechecks_current_delegation_and_organization(String defect)throws Exception {
        setupContact();var command=prepare(contact("CONNECTED_VALID"));String subject="delegated receipt credential";var delegate=credentialActor(PrincipalKind.HUMAN,subject,"LEAD_CAPTURE");var actor=new Actor(delegate.tenantId(),delegate.principalId(),delegate.appointmentId(),seed.principal(),seed.appointment(),PrincipalKind.HUMAN);UUID delegation=UUID.randomUUID();
        try(var http=new HttpHarness(actor,subject,List.of())) {
            mutate("insert into identity.delegation_grant (tenant_id,delegation_grant_id,source_authority_grant_id,delegator_appointment_id,delegate_appointment_id,scope_organization_unit_id,valid_from,valid_until,state,created_at) values (?,?,?,?,?,?,clock_timestamp()-interval '1 day',"+(defect.equals("DELEGATION_EXPIRED")?"clock_timestamp()+interval '8 seconds'":"null")+",'ACTIVE',clock_timestamp())",seed.tenant(),delegation,seed.grant(),seed.appointment(),delegate.appointmentId(),seed.org());
            var written=http.request("POST","/api/v1/tasks/"+current.selector().id()+"/commands/record-contact-result",command.payload(),Map.of("Idempotency-Key",command.commandId().toString(),"If-Match",io.github.windyzhu3.ontologylaw.responsibility.R1ResourceTags.task(actor,current.selector(),current.state())));assertEquals(200,written.statusCode(),written.body());String path="/api/v1/commands/"+command.commandId()+"/receipt";assertEquals(200,http.request("GET",path,null,Map.of()).statusCode());
            if(defect.equals("DELEGATION_REVOKED"))mutate("update identity.delegation_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and delegation_grant_id=?",seed.tenant(),delegation);
            else if(defect.equals("ORGANIZATION_CLOSED"))mutate("update identity.organization_unit set state='CLOSED',closed_at=clock_timestamp(),revision=revision+1 where tenant_id=? and organization_unit_id=?",seed.tenant(),seed.org());
            else {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<until&&"1".equals(scalar("select count(*)::text from identity.delegation_grant where tenant_id=? and delegation_grant_id=? and valid_until>clock_timestamp()",seed.tenant(),delegation)))Thread.sleep(100);assertEquals("0",scalar("select count(*)::text from identity.delegation_grant where tenant_id=? and delegation_grant_id=? and valid_until>clock_timestamp()",seed.tenant(),delegation));}
            var before=counts();var denied=http.request("GET",path,null,Map.of());assertEquals(403,denied.statusCode(),denied.body());assertFalse(denied.body().contains("receiptRef"));assertEquals(before,counts());
        }
    }
    @ParameterizedTest @ValueSource(strings={"PRINCIPAL","APPOINTMENT","TENANT"})
    void verified_credential_with_inactive_current_identity_is_403_not_an_authentication_challenge(String target)throws Exception {
        setupContact();var command=prepare(contact("CONNECTED_VALID"));execute(command);
        try(var http=new HttpHarness()) {
            String table=target.toLowerCase(Locale.ROOT);mutate("update identity."+table+" set state='SUSPENDED',revision=revision+1 where tenant_id=?"+(target.equals("TENANT")?"":" and "+table+"_id=?"),target.equals("TENANT")?new Object[]{seed.tenant()}:new Object[]{seed.tenant(),target.equals("PRINCIPAL")?seed.principal():seed.appointment()});
            var before=counts();var response=http.request("GET","/api/v1/commands/"+command.commandId()+"/receipt",null,Map.of());assertEquals(403,response.statusCode(),response.body());assertTrue(response.headers().firstValue("WWW-Authenticate").isEmpty());assertFalse(response.body().contains("receiptRef"));assertEquals(before,counts());
        }
    }
    @Test void same_principal_different_appointment_and_different_tenant_remain_safe_not_found()throws Exception {
        setupContact();var command=prepare(contact("CONNECTED_VALID"));var outcome=execute(command);UUID appointment=UUID.randomUUID();
        mutate("insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'HTTP_FIXTURE',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),appointment,seed.principal(),seed.org());
        try(var http=new HttpHarness(new Actor(seed.tenant(),seed.principal(),appointment,null,null,PrincipalKind.HUMAN),SUBJECT,List.of())){var before=counts();assertEquals(404,http.request("GET","/api/v1/commands/"+command.commandId()+"/receipt",null,Map.of()).statusCode());assertEquals(before,counts());}
        UUID originalTenant=seed.tenant();String original=scalar("select row_to_json(r)::text from execution.command_receipt r where tenant_id=? and command_receipt_id=?",originalTenant,outcome.receiptId());setupContact();
        try(var http=new HttpHarness()){var before=counts();assertEquals(404,http.request("GET","/api/v1/commands/"+command.commandId()+"/receipt",null,Map.of()).statusCode());assertEquals(before,counts());assertEquals(original,scalar("select row_to_json(r)::text from execution.command_receipt r where tenant_id=? and command_receipt_id=?",originalTenant,outcome.receiptId()));}
    }
    @Test void authorization_uses_fresh_database_time_after_real_business_lock_wait()throws Exception {
        setupContact();var command=prepare(contact("CONNECTED_VALID"));execute(command);
        var enteringFence=new CountDownLatch(1);disclosureConnection=c->(java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(java.sql.Connection.class.getClassLoader(),new Class<?>[]{java.sql.Connection.class},(p,m,a)->{if(m.getName().equals("prepareStatement")&&a[0] instanceof String sql&&sql.contains("pg_advisory_xact_lock_shared"))enteringFence.countDown();try{return m.invoke(c,a);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}});
        try(var http=new HttpHarness();var writer=database.apiConnection();var pool=Executors.newSingleThreadExecutor()) {
            mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_code='SALES_CONTACT_OWNER'",seed.tenant());
            mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,valid_until,state,created_at) values (?,?,?,?,?,'SALES_CONTACT_OWNER',clock_timestamp()-interval '1 day',clock_timestamp()+interval '3 seconds','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),seed.appointment(),seed.appointment(),seed.org());
            writer.setAutoCommit(false);setLocalRole(writer,Capability.COMMAND);R1BusinessFence.databaseBacked().exclusive(writer,seed.tenant());var before=counts();
            var response=pool.submit(()->http.request("GET","/api/v1/commands/"+command.commandId()+"/receipt",null,Map.of()));
            try{assertTrue(enteringFence.await(2,TimeUnit.SECONDS));assertEquals("1",scalar("select count(*)::text from identity.authority_grant where tenant_id=? and authority_code='SALES_CONTACT_OWNER' and state='ACTIVE' and valid_until>clock_timestamp()",seed.tenant()));assertThrows(TimeoutException.class,()->response.get(200,TimeUnit.MILLISECONDS));long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<until&&"1".equals(scalar("select count(*)::text from identity.authority_grant where tenant_id=? and authority_code='SALES_CONTACT_OWNER' and state='ACTIVE' and valid_until>clock_timestamp()",seed.tenant())))Thread.sleep(100);assertEquals("0",scalar("select count(*)::text from identity.authority_grant where tenant_id=? and authority_code='SALES_CONTACT_OWNER' and state='ACTIVE' and valid_until>clock_timestamp()",seed.tenant()));}finally{writer.rollback();}
            var denied=response.get(10,TimeUnit.SECONDS);assertEquals(403,denied.statusCode(),denied.body());assertFalse(denied.body().contains("receiptRef"));assertEquals(before,counts());
        }
    }
}
