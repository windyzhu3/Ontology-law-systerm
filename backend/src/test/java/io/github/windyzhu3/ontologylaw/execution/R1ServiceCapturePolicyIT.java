package io.github.windyzhu3.ontologylaw.execution;

import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.lead.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class R1ServiceCapturePolicyIT extends R1CommandPolicyIT {
    @AfterEach void resetReaders(){readers=R1AuthorizationReaders.databaseBacked(sources("ROOT"));}
    R1ServiceSourceBinding.Entry entry(Service service,Set<String> accounts) {
        var actor=service.actor();return new R1ServiceSourceBinding.Entry("https://trusted.example","law-api",actor.principalId().toString(),actor.tenantId(),actor.principalId(),actor.appointmentId(),accounts);
    }
    R1ServiceSourceBinding bind(List<R1ServiceSourceBinding.Entry> entries)throws Exception {
        try(var c=database.apiConnection()){return inTransaction(c,Capability.QUERY,x->R1ServiceSourceBinding.validate(x,entries,sources("ROOT")));}
    }
    void allow(Service service)throws Exception {readers=R1AuthorizationReaders.databaseBacked(sources("ROOT"),bind(List.of(entry(service,Set.of("FIXTURE")))));}
    CommandHandler.Context serviceContext(Handler h,Service service,String account) {
        return request(capture(h,account,captureGrant(h)),service.actor(),h.seed.org(),new Requirement("LEAD_CAPTURE","SOURCE_INTAKE_OWNER",Path.SYSTEM,service.grant()));
    }
    CommandEnvelope serviceEnvelope(Handler h,Service service,String account) {
        return envelope(h,CommandEnvelope.Type.CAPTURE_LEAD,service.actor(),Map.of("sourceAccountCode",account));
    }
    @Test void trusted_service_capture_uses_source_owner_grant()throws Exception {
        var h=captureHandler();var service=service(h,"LEAD_CAPTURE",h.seed.org());
        allow(service);var context=serviceContext(h,service,"FIXTURE");
        assertTrue(decision(envelope(h,CommandEnvelope.Type.CAPTURE_LEAD,service.actor(),Map.of("sourceAccountCode","FIXTURE")),context).allowed());
    }
    @Test void supported_but_unbound_source_and_unknown_source_are_both_denied()throws Exception {
        var h=captureHandler();var service=service(h,"LEAD_CAPTURE",h.seed.org());allow(service);
        for(String account:List.of("SECOND","UNKNOWN"))assertFalse(decision(serviceEnvelope(h,service,account),serviceContext(h,service,account)).allowed());
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
            assertNotNull(readers.capture(x,h.seed.tenant(),"SECOND",hash(h.seed.subject().toString())));
            assertNull(readers.capture(x,h.seed.tenant(),"UNKNOWN",hash(h.seed.subject().toString())));return null;
        });}
    }
    @Test void binding_startup_rejects_wrong_identity_provider_kind_tenant_appointment_and_duplicate_actor()throws Exception {
        var h=captureHandler();var service=service(h,"LEAD_CAPTURE",h.seed.org());var good=entry(service,Set.of("FIXTURE"));
        var invalid=List.of(
            new R1ServiceSourceBinding.Entry(good.issuer(),good.audience(),"wrong-provider",good.tenantId(),good.principalId(),good.appointmentId(),good.sourceAccountCodes()),
            new R1ServiceSourceBinding.Entry(good.issuer(),good.audience(),good.identityProviderCode(),UUID.randomUUID(),good.principalId(),good.appointmentId(),good.sourceAccountCodes()),
            new R1ServiceSourceBinding.Entry(good.issuer(),good.audience(),good.identityProviderCode(),good.tenantId(),h.seed.principal(),good.appointmentId(),good.sourceAccountCodes()),
            new R1ServiceSourceBinding.Entry(good.issuer(),good.audience(),"FIXTURE",good.tenantId(),h.seed.principal(),h.seed.appointment(),good.sourceAccountCodes()),
            new R1ServiceSourceBinding.Entry(good.issuer(),good.audience(),good.identityProviderCode(),good.tenantId(),good.principalId(),UUID.randomUUID(),good.sourceAccountCodes()),
            new R1ServiceSourceBinding.Entry(good.issuer(),good.audience(),good.identityProviderCode(),good.tenantId(),good.principalId(),good.appointmentId(),Set.of("UNKNOWN")));
        for(var row:invalid)assertThrows(IllegalArgumentException.class,()->bind(List.of(row)));
        var alternate=new R1ServiceSourceBinding.Entry("https://other.example",good.audience(),good.identityProviderCode(),good.tenantId(),good.principalId(),good.appointmentId(),Set.of("SECOND"));
        assertThrows(IllegalArgumentException.class,()->bind(List.of(good,alternate)));
        assertThrows(IllegalArgumentException.class,()->entry(service,Set.of()));
        assertEquals(List.of(good),bind(List.of(good)).entries());
    }
    @Test void trusted_kind_must_match_database_and_service_only_uses_system_capture_grant()throws Exception {
        var h=captureHandler();var service=service(h,"LEAD_CAPTURE",h.seed.org());allow(service);var ctx=serviceContext(h,service,"FIXTURE");
        for(Path path:List.of(Path.DIRECT,Path.DELEGATED,Path.OBJECT))assertFalse(decision(serviceEnvelope(h,service,"FIXTURE"),request(ctx,service.actor(),h.seed.org(),new Requirement("LEAD_CAPTURE","SOURCE_INTAKE_OWNER",path,service.grant()))).allowed());
        var legacy=new Actor(service.actor().tenantId(),service.actor().principalId(),service.actor().appointmentId(),null,null);
        assertFalse(decision(envelope(h,CommandEnvelope.Type.CAPTURE_LEAD,legacy,Map.of("sourceAccountCode","FIXTURE")),request(ctx,legacy,h.seed.org(),ctx.authorization().requirement())).allowed());
        var falseKind=new Actor(h.seed.tenant(),h.seed.principal(),h.seed.appointment(),null,null,PrincipalKind.SERVICE);
        assertFalse(decision(envelope(h,CommandEnvelope.Type.CAPTURE_LEAD,falseKind,Map.of("sourceAccountCode","FIXTURE")),request(ctx,falseKind,h.seed.org(),ctx.authorization().requirement())).allowed());
        var recovery=service(h,"CONTACT_TASK_RECOVER",h.seed.org());allow(recovery);
        assertFalse(decision(serviceEnvelope(h,recovery,"FIXTURE"),serviceContext(h,recovery,"FIXTURE")).allowed());
    }
    @Test void service_capture_rereads_principal_appointment_grant_and_source_organization()throws Exception {
        for(int scenario:List.of(0,1,4,5,6,8)) {
            var h=captureHandler();var service=service(h,"LEAD_CAPTURE",h.seed.org());allow(service);
            var ctx=serviceContext(h,service,"FIXTURE");var e=serviceEnvelope(h,service,"FIXTURE");assertTrue(decision(e,ctx).allowed());
            switch(scenario) {
                case 0 -> mutate(h,"update identity.principal set state='SUSPENDED',revision=revision+1 where tenant_id=? and principal_id=?",h.seed.tenant(),service.actor().principalId());
                case 1 -> mutate(h,"update identity.appointment set state='SUSPENDED',revision=revision+1 where tenant_id=? and appointment_id=?",h.seed.tenant(),service.actor().appointmentId());
                case 4 -> mutate(h,"update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='TEST',revision=revision+1 where tenant_id=? and authority_grant_id=?",h.seed.tenant(),service.grant());
                case 5 -> mutate(h,"update identity.organization_unit set state='CLOSED',closed_at=clock_timestamp(),revision=revision+1 where tenant_id=? and organization_unit_id=?",h.seed.tenant(),h.seed.org());
                case 6 -> mutate(h,"update identity.organization_unit set display_name='changed',revision=revision+1 where tenant_id=? and organization_unit_id=?",h.seed.tenant(),h.seed.org());
                case 8 -> readers=R1AuthorizationReaders.databaseBacked(sources("MISSING"),bind(List.of(entry(service,Set.of("FIXTURE")))));
            }
            assertFalse(decision(e,ctx).allowed(),"scenario "+scenario);
        }
    }
    @Test void capture_scope_is_source_root_even_when_service_appointment_is_in_another_organization()throws Exception {
        var h=captureHandler();UUID serviceOrg=UUID.randomUUID();
        mutate(h,"insert into identity.organization_unit (tenant_id,organization_unit_id,unit_code,display_name,state,created_at) values (?,?,'SERVICE_ORG','service','ACTIVE',clock_timestamp())",h.seed.tenant(),serviceOrg);
        var service=identity(h,"LEAD_CAPTURE",h.seed.org(),"SERVICE",serviceOrg);allow(service);
        assertTrue(decision(serviceEnvelope(h,service,"FIXTURE"),serviceContext(h,service,"FIXTURE")).allowed());
        var uncovered=identity(h,"LEAD_CAPTURE",serviceOrg,"SERVICE",serviceOrg);allow(uncovered);
        assertFalse(decision(serviceEnvelope(h,uncovered,"FIXTURE"),serviceContext(h,uncovered,"FIXTURE")).allowed());
    }
    @Test void service_capture_rejects_expired_appointment_and_expired_capture_grant()throws Exception {
        for(boolean expiredAppointment:List.of(false,true)) {
            var h=captureHandler();var original=service(h,"LEAD_CAPTURE",h.seed.org());UUID app=expiredAppointment?UUID.randomUUID():original.actor().appointmentId();UUID grant=UUID.randomUUID();
            if(expiredAppointment)mutate(h,"insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,effective_until,state,created_at) values (?,?,?,?,'SERVICE',clock_timestamp()-interval '2 days',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",h.seed.tenant(),app,original.actor().principalId(),h.seed.org());
            mutate(h,"insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,valid_until,state,created_at) values (?,?,?,?,?,'LEAD_CAPTURE',clock_timestamp()-interval '2 days',clock_timestamp()"+(expiredAppointment?"+":"-")+"interval '1 day','ACTIVE',clock_timestamp())",h.seed.tenant(),grant,app,h.seed.appointment(),h.seed.org());
            var svc=new Service(new Actor(h.seed.tenant(),original.actor().principalId(),app,null,null,PrincipalKind.SERVICE),grant);allow(svc);
            assertFalse(decision(serviceEnvelope(h,svc,"FIXTURE"),serviceContext(h,svc,"FIXTURE")).allowed());
        }
    }
    @Test void changed_source_is_a_scope_conflict_when_both_sources_are_bound()throws Exception {
        var h=captureHandler();var svc=service(h,"LEAD_CAPTURE",h.seed.org());
        readers=R1AuthorizationReaders.databaseBacked(sources("ROOT"),bind(List.of(entry(svc,Set.of("FIXTURE","SECOND")))));
        var handler=new NoChangeHandler(h,serviceContext(h,svc,"FIXTURE"),h.seed.request().subject()) {
            @Override public Context resolve(Connection c,CommandEnvelope e){return serviceContext(h,svc,(String)((Map<?,?>)e.payload()).get("sourceAccountCode"));}
        };
        var runtime=new CommandRuntime(List.of(handler),auth,"SERVICE_IT",readers);var original=serviceEnvelope(h,svc,"FIXTURE");
        var receipt=run(runtime,original);
        var changed=new CommandEnvelope(original.type(),original.commandId(),UUID.randomUUID(),svc.actor(),Map.of("sourceAccountCode","SECOND"));
        assertEquals(receipt.receiptId(),assertInstanceOf(CommandResult.Conflict.class,runResult(runtime,changed)).receiptId());
        assertEquals(List.of(1L,1L,1L,0L,0L),counts(h).subList(0,5));
    }
    @Test void service_receipt_replay_is_exact_and_human_service_envelope_switch_conflicts()throws Exception {
        for(boolean serviceFirst:List.of(false,true)) {
            var h=captureHandler();var service=service(h,"LEAD_CAPTURE",h.seed.org());allow(service);
            var svc=serviceContext(h,service,"FIXTURE");var human=capture(h,"FIXTURE",captureGrant(h));
            var handler=new NoChangeHandler(h,svc,h.seed.request().subject()) {
                @Override public Context resolve(Connection c,CommandEnvelope e){return e.actor().principalKind()==PrincipalKind.SERVICE?svc:human;}
            };
            var runtime=new CommandRuntime(List.of(handler),auth,"SERVICE_IT",readers);
            var firstActor=serviceFirst?service.actor():h.seed.request().actor();var nextActor=serviceFirst?h.seed.request().actor():service.actor();
            var e=envelope(h,CommandEnvelope.Type.CAPTURE_LEAD,firstActor,Map.of("sourceAccountCode","FIXTURE"));
            var receipt=run(runtime,e);assertEquals(receipt,run(runtime,e));
            var changed=new CommandEnvelope(e.type(),e.commandId(),UUID.randomUUID(),nextActor,e.payload());
            assertInstanceOf(CommandResult.Conflict.class,runResult(runtime,changed));
            var payload=new CommandEnvelope(e.type(),e.commandId(),UUID.randomUUID(),firstActor,Map.of("sourceAccountCode","FIXTURE","extra","changed"));
            assertInstanceOf(CommandResult.Conflict.class,runResult(runtime,payload));
            assertEquals(List.of(1L,1L,1L,0L,0L),counts(h).subList(0,5));
            if(serviceFirst)try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
                assertEquals("SERVICE_ACTOR",scalar(x,"select envelope_type from execution.command_execution_slot where tenant_id='"+h.seed.tenant()+"'"));
                assertEquals("SYSTEM:SOURCE_INTAKE_OWNER",scalar(x,"select authorization_path_code||':'||authorization_slot_code from audit.audit_entry_classified_v where tenant_id='"+h.seed.tenant()+"'"));
                assertEquals("t",scalar(x,"select actor_principal_id='"+service.actor().principalId()+"' and actor_appointment_id='"+service.actor().appointmentId()+"' and on_behalf_of_principal_id is null and on_behalf_of_appointment_id is null from audit.audit_entry_classified_v where tenant_id='"+h.seed.tenant()+"'"));return null;
            });}
            deny(h,h.seed.request().subject(),"LEAD_CAPTURE",firstActor.principalId());
            assertThrows(CommandHandler.Rejected.class,()->run(runtime,e));
        }
    }
    @Test void revocation_while_waiting_on_business_fence_prevents_roots_and_every_write()throws Exception {
      for(boolean leadDeny:List.of(false,true)) {
        var h=captureHandler();var service=service(h,"LEAD_CAPTURE",h.seed.org());allow(service);
        var ctx=serviceContext(h,service,"FIXTURE");var roots=new CountDownLatch(1);
        var handler=new NoChangeHandler(h,ctx,h.seed.request().subject()) {
            @Override public void lockRoots(Connection c,CommandEnvelope e,Context context)throws SQLException {roots.countDown();super.lockRoots(c,e,context);}
        };
        var runtime=new CommandRuntime(List.of(handler),auth,"SERVICE_IT",readers);
        try(var read=database.apiConnection();var writer=database.apiConnection();var observer=database.apiConnection();var pool=Executors.newSingleThreadExecutor()) {
            read.setAutoCommit(false);R1BusinessFenceIT.readFence(read,h.seed.tenant());
            int pid=Integer.parseInt(scalar(writer,"select pg_backend_pid()"));
            var work=pool.submit(()->runtime.execute(writer,serviceEnvelope(h,service,"FIXTURE")));
            try {
                R1BusinessFenceIT.awaitBlocked(observer,pid);assertEquals(1L,roots.getCount());
                if(leadDeny)deny(h,h.seed.request().subject(),"LEAD_CAPTURE",service.actor().principalId());
                else mutate(h,"update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='TEST',revision=revision+1 where tenant_id=? and authority_grant_id=?",h.seed.tenant(),service.grant());
            } finally {read.rollback();}
            assertInstanceOf(CommandHandler.Rejected.class,assertThrows(ExecutionException.class,()->work.get(15,TimeUnit.SECONDS)).getCause());
            assertEquals(1L,roots.getCount());assertEquals(List.of(0L,0L,0L,0L,0L),counts(h).subList(0,5));
        }
      }
    }
}
