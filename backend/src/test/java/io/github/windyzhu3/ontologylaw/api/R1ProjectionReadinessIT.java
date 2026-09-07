package io.github.windyzhu3.ontologylaw.api;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.lead.*;
import io.github.windyzhu3.ontologylaw.responsibility.TaskFactory;
import java.util.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.sql.Connection;
import java.lang.reflect.*;

class R1ProjectionReadinessIT extends ContactFlowFixture {
    @Test void readiness_never_logs_owner_queries_or_results_with_jooq_debug_enabled()throws Exception{
        setupContact();var actor=service("R1_PROJECTION_CONSUME");var logs=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();logs.start();
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger("org.jooq.tools.LoggerListener");var level=logger.getLevel();boolean additive=logger.isAdditive();logger.setAdditive(false);logger.setLevel(ch.qos.logback.classic.Level.DEBUG);logger.addAppender(logs);
        try{
            logger.debug("READINESS_LOG_CAPTURE_CONTROL");assertEquals(1,logs.list.size());logs.list.clear();
            assertEquals(204,check(actor,policies).status());
            var missing=new R1SourcePolicyRegistry(Map.of("MISSING",new R1SourcePolicyRegistry.SourcePolicy(R1SourcePolicyRegistry.AssignmentMode.MANUAL,List.of("ROOT"),"ROOT","MISSING","Asia/Shanghai")));assertEquals(403,check(actor,missing).status());
            try(var raw=database.apiConnection()){
                var failing=(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{if(method.getName().equals("prepareStatement")&&args[0] instanceof String query&&query.contains("organization_unit"))throw new java.sql.SQLException("READINESS_QUERY_FAILURE");try{return method.invoke(raw,args);}catch(InvocationTargetException failure){throw failure.getCause();}});
                assertEquals(503,new R1ProjectionReadinessService(policies).check(failing,actor).status());
            }
            assertTrue(logs.list.isEmpty(),"Readiness must not emit SQL or fetched identity rows");
        }finally{logger.detachAppender(logs);logger.setLevel(level);logger.setAdditive(additive);logs.stop();}
    }
    @Test void retained_assignment_without_task_adds_real_owner_scope_and_active_child_cannot_hide_closed_ancestor()throws Exception{
        setupContact();cancelCurrent();var actor=service("R1_PROJECTION_CONSUME");UUID parent=UUID.randomUUID(),child=UUID.randomUUID(),principal=UUID.randomUUID(),appointment=UUID.randomUUID();
        mutate("insert into identity.organization_unit (tenant_id,organization_unit_id,unit_code,display_name,state,created_at) values (?,?,'OUTSIDE','fixture','ACTIVE',clock_timestamp())",seed.tenant(),parent);
        mutate("insert into identity.organization_unit (tenant_id,organization_unit_id,parent_organization_unit_id,unit_code,display_name,state,created_at) values (?,?,?,'OUTSIDE_CHILD','fixture','ACTIVE',clock_timestamp())",seed.tenant(),child,parent);
        mutate("insert into identity.principal (tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) values (?,?,'HUMAN','FIXTURE',?,'fixture','ACTIVE',clock_timestamp())",seed.tenant(),principal,io.github.windyzhu3.ontologylaw.execution.CanonicalJson.digest(principal.toString()));
        mutate("insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'OWNER',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),appointment,principal,child);
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{var leads=LeadIngressService.databaseBacked(protection);var lead=leads.capture(x,seed.tenant(),input(true),io.github.windyzhu3.ontologylaw.execution.CanonicalJson.digest(UUID.randomUUID().toString()),businessAt);var assignment=leads.assign(x,seed.tenant(),lead,appointment,"MANUAL_SELECTION",businessAt);leads.update(x,seed.tenant(),lead,null,null,null,appointment,assignment.selector().id(),businessAt);return null;});}
        assertEquals(403,check(actor,policies).status());mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,'R1_PROJECTION_CONSUME',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),actor.appointmentId(),seed.appointment(),parent);assertEquals(204,check(actor,policies).status());
        mutate("update identity.appointment set state='SUSPENDED',revision=revision+1 where tenant_id=? and appointment_id=?",seed.tenant(),appointment);assertEquals(204,check(actor,policies).status());mutate("update identity.organization_unit set state='CLOSED',closed_at=clock_timestamp(),revision=revision+1 where tenant_id=? and organization_unit_id=?",seed.tenant(),parent);assertEquals(403,check(actor,policies).status());
    }
    @Test void readiness_coverage_and_grants_never_cross_tenant_boundaries()throws Exception{
        setupContact();var actor=service("R1_PROJECTION_CONSUME");var other=AuthorizationServiceIT.seed(database,"SERVICE","R1_PROJECTION_CONSUME");var otherActor=new Actor(other.tenant(),other.principal(),other.appointment(),null,null,PrincipalKind.SERVICE);assertEquals(204,check(actor,policies).status());assertEquals(204,check(otherActor,policies).status());
        mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and grantee_appointment_id=?",other.tenant(),other.appointment());assertEquals(403,check(otherActor,policies).status());assertEquals(204,check(actor,policies).status());
    }
    private Connection pauseAtOwnerScan(Connection delegate,CountDownLatch entered,CountDownLatch release){
        var once=new AtomicBoolean();return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            if(method.getName().equals("prepareStatement")&&args[0] instanceof String sql&&sql.contains("task_occurrence")&&once.compareAndSet(false,true)){entered.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("Readiness latch timeout");}
            try{return method.invoke(delegate,args);}catch(InvocationTargetException failure){throw failure.getCause();}
        });
    }
    @Test void natural_expiry_during_owner_universe_read_is_checked_at_final_database_evaluation()throws Exception{
        setupContact();var actor=service("R1_PROJECTION_CONSUME");mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and grantee_appointment_id=?",seed.tenant(),actor.appointmentId());
        mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,valid_until,state,created_at) values (?,?,?,?,?,'R1_PROJECTION_CONSUME',clock_timestamp()-interval '1 day',clock_timestamp()+interval '1 second','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),actor.appointmentId(),seed.appointment(),seed.org());
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor();var c=database.apiConnection()){
            var response=executor.submit(()->new R1ProjectionReadinessService(policies).check(pauseAtOwnerScan(c,entered,release),actor));assertTrue(entered.await(5,TimeUnit.SECONDS));Thread.sleep(1200);release.countDown();assertEquals(403,response.get(5,TimeUnit.SECONDS).status());
        }finally{release.countDown();}
    }
    @Test void readiness_shared_identity_fence_blocks_revocation_until_decision_then_next_check_observes_it()throws Exception{
        setupContact();var actor=service("R1_PROJECTION_CONSUME");var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var writerStarted=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor();var c=database.apiConnection()){
            var response=executor.submit(()->new R1ProjectionReadinessService(policies).check(pauseAtOwnerScan(c,entered,release),actor));assertTrue(entered.await(5,TimeUnit.SECONDS));
            var writer=executor.submit(()->{try(var writerConnection=database.apiConnection()){return inTransaction(writerConnection,Capability.COMMAND,x->{writerStarted.countDown();AuthorizationService.databaseBacked().lockForMutation(x,seed.tenant());sql(x,"update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and grantee_appointment_id=?",seed.tenant(),actor.appointmentId());return null;});}});assertTrue(writerStarted.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->writer.get(200,TimeUnit.MILLISECONDS));release.countDown();assertEquals(204,response.get(5,TimeUnit.SECONDS).status());writer.get(5,TimeUnit.SECONDS);assertEquals(403,check(actor,policies).status());
        }finally{release.countDown();}
    }
    private ResponseCheck check(Actor actor,R1SourcePolicyRegistry registry)throws Exception {
        try(var c=database.apiConnection()) {
            var result=new R1ProjectionReadinessService(registry).check(c,actor);
            assertTrue(c.getAutoCommit());
            assertEquals("no-store",result.cacheControl());
            return new ResponseCheck(result.status(),result.errorCode());
        }
    }
    private record ResponseCheck(int status,String code) {}
    @Test void valid_exact_service_covers_source_and_retained_terminal_owner_without_writes()throws Exception {
        setupContact();var actor=service("R1_PROJECTION_CONSUME");cancelCurrent();
        var before=counts();
        assertEquals(new ResponseCheck(204,null),check(actor,policies));
        assertEquals(before,counts());
    }
    @Test void empty_universe_still_requires_effective_projection_grant_and_real_service()throws Exception {
        seed=AuthorizationServiceIT.seed(database,"HUMAN","LEAD_CAPTURE");
        var empty=new R1SourcePolicyRegistry(Map.of());
        assertEquals(403,check(seed.request().actor(),empty).status());
        assertEquals(401,check(null,empty).status());
        assertEquals(403,check(service("CONTACT_TASK_RECOVER"),empty).status());
        assertEquals(403,check(service("R1_PROJECTION_CONSUME",true),empty).status());
        assertEquals(204,check(service("R1_PROJECTION_CONSUME"),empty).status());
    }
    @Test void broad_other_appointment_never_repairs_selected_appointment_grant()throws Exception {
        setupContact();var good=service("R1_PROJECTION_CONSUME");var narrow=service("LEAD_CAPTURE");
        assertEquals(204,check(good,policies).status());
        assertEquals(403,check(narrow,policies).status());
        var forged=new Actor(good.tenantId(),good.principalId(),narrow.appointmentId(),null,null,PrincipalKind.SERVICE);
        assertEquals(403,check(forged,policies).status());
    }
    @Test void new_source_root_requires_its_own_complete_grant_and_unknown_root_fails_closed()throws Exception {
        setupContact();var actor=service("R1_PROJECTION_CONSUME");UUID root=UUID.randomUUID();
        mutate("insert into identity.organization_unit (tenant_id,organization_unit_id,unit_code,display_name,state,created_at) values (?,?,'NEWROOT','fixture','ACTIVE',clock_timestamp())",seed.tenant(),root);
        var extra=new R1SourcePolicyRegistry(Map.of("NEW",new R1SourcePolicyRegistry.SourcePolicy(R1SourcePolicyRegistry.AssignmentMode.MANUAL,List.of("ROOT"),"ROOT","NEWROOT","Asia/Shanghai")));
        assertEquals(403,check(actor,extra).status());
        // ROOT is retained by real Task/Assignment facts; NEWROOT is required by policy.
        // A scope from one grant cannot borrow code or Appointment from another.
        var other=service("R1_PROJECTION_CONSUME");
        mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,'R1_PROJECTION_CONSUME',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),other.appointmentId(),seed.appointment(),root);
        mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,'LEAD_CAPTURE',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),actor.appointmentId(),seed.appointment(),root);
        assertEquals(403,check(actor,extra).status());
        mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,'R1_PROJECTION_CONSUME',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),actor.appointmentId(),seed.appointment(),root);
        assertEquals(204,check(actor,extra).status());
        mutate("update identity.organization_unit set state='CLOSED',closed_at=clock_timestamp(),revision=revision+1 where tenant_id=? and organization_unit_id=?",seed.tenant(),root);
        assertEquals(403,check(actor,extra).status());
        var missing=new R1SourcePolicyRegistry(Map.of("MISSING",new R1SourcePolicyRegistry.SourcePolicy(R1SourcePolicyRegistry.AssignmentMode.MANUAL,List.of("ROOT"),"ROOT","MISSING","Asia/Shanghai")));
        assertEquals(403,check(actor,missing).status());
    }
    @Test void revoked_service_grant_is_rejected_but_retained_historical_owner_inactivity_is_not_service_inactivity()throws Exception {
        setupContact();var actor=service("R1_PROJECTION_CONSUME");
        assertEquals(204,check(actor,policies).status());
        mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and grantee_appointment_id=?",seed.tenant(),actor.appointmentId());
        assertEquals(403,check(actor,policies).status());
        var repaired=service("R1_PROJECTION_CONSUME");
        mutate("update identity.appointment set state='SUSPENDED',revision=revision+1 where tenant_id=? and appointment_id=?",seed.tenant(),seed.appointment());
        assertEquals(204,check(repaired,policies).status());
        mutate("update identity.appointment set state='SUSPENDED',revision=revision+1 where tenant_id=? and appointment_id=?",seed.tenant(),repaired.appointmentId());
        assertEquals(403,check(repaired,policies).status());
    }
}
