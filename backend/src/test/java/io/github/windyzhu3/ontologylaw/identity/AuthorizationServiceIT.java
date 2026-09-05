package io.github.windyzhu3.ontologylaw.identity;

import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.testing.PostgresIntegrationTest;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;

public class AuthorizationServiceIT extends PostgresIntegrationTest {
    final AuthorizationService service = AuthorizationService.databaseBacked();
    public record Seed(UUID tenant, UUID principal, UUID appointment, UUID org, UUID grant, UUID subject) {
        public AuthorizationService.Request request() {
            return new AuthorizationService.Request(new AuthorizationService.Actor(tenant, principal, appointment, null, null),
                    new AuthorizationService.Subject("lead.lead", subject, 0L, null), org,
                    new AuthorizationService.Requirement("LEAD_INGRESS_COMPLETE", "SOURCE_INTAKE_OWNER", AuthorizationService.Path.DIRECT, grant));
        }
    }
    Seed seed() throws Exception { return seed(database); }
    public static Seed seed(Database database) throws Exception {
        return seed(database,"HUMAN");
    }
    public static Seed seed(Database database,String kind) throws Exception {
        return seed(database,kind,"LEAD_INGRESS_COMPLETE");
    }
    public static Seed seed(Database database,String kind,String authorityCode) throws Exception {
        Seed s = new Seed(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        try (var c = database.apiConnection()) {
            inTransaction(c, Capability.COMMAND, x -> {
                sql(x, "insert into identity.tenant (tenant_id,tenant_code,display_name,state,created_at) values (?,?,'fixture','ACTIVE',clock_timestamp())", s.tenant, s.tenant.toString());
                sql(x, "insert into identity.principal (tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) values (?,?,?,'FIXTURE',decode(repeat('00',32),'hex'),'fixture','ACTIVE',clock_timestamp())", s.tenant,s.principal,kind);
                sql(x, "insert into identity.organization_unit (tenant_id,organization_unit_id,unit_code,display_name,state,created_at) values (?,?,'ROOT','fixture','ACTIVE',clock_timestamp())",s.tenant,s.org);
                sql(x, "insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'OWNER',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",s.tenant,s.appointment,s.principal,s.org);
                sql(x, "insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,?,clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",s.tenant,s.grant,s.appointment,s.appointment,s.org,authorityCode);
                return null;
            });
        }
        return s;
    }
    public static void sql(Connection c, String query, Object... args) throws SQLException {
        try (var p=c.prepareStatement(query)) { for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]); p.executeUpdate(); }
    }
    @Test void reloads_revocation_after_initial_allow_on_same_transaction() throws Exception {
        Seed s=seed();
        try(var c=database.apiConnection()) {
            inTransaction(c, Capability.QUERY, x -> {
                assertTrue(service.evaluate(x,s.request(),false).allowed());
                try(var writer=database.apiConnection()) { inTransaction(writer,Capability.COMMAND,w -> {
                    service.lockForMutation(w,s.tenant);
                    sql(w,"update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='TEST',revision=revision+1 where tenant_id=? and authority_grant_id=?",s.tenant,s.grant); return null;
                }); }
                var result=service.evaluate(x,s.request(),true);
                assertFalse(result.allowed()); assertEquals("NOT_AUTHORIZED",result.rejectionCode()); assertEquals(32,result.digest().length);
                return null;
            });
        }
    }
    @Test void exact_subject_deny_wins_over_the_selected_direct_grant() throws Exception {
        Seed s=seed();
        try(var c=database.apiConnection()) {
            inTransaction(c,Capability.COMMAND,x -> {
                sql(x,"insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values (?,?,?,?,'LEAD_INGRESS_COMPLETE','DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),'lead.lead',?,0)",s.tenant,UUID.randomUUID(),s.principal,s.appointment,s.subject);return null;
            });
            inTransaction(c,Capability.QUERY,x -> { assertFalse(service.evaluate(x,s.request(),true).allowed());return null; });
        }
    }
    @Test void system_path_requires_real_service_principal_and_an_exact_direct_grant() throws Exception {
        Seed s=seed();
        var system=new AuthorizationService.Request(s.request().actor(),s.request().subject(),s.org,
                new AuthorizationService.Requirement("LEAD_INGRESS_COMPLETE","SOURCE_INTAKE_OWNER",AuthorizationService.Path.SYSTEM,s.grant));
        try(var c=database.apiConnection()) {
            inTransaction(c,Capability.QUERY,x -> {assertFalse(service.evaluate(x,system,true).allowed());return null;});
        }
        Seed serviceSeed=seed(database,"SERVICE");
        var serviceRequest=new AuthorizationService.Request(serviceSeed.request().actor(),serviceSeed.request().subject(),serviceSeed.org,
                new AuthorizationService.Requirement("LEAD_INGRESS_COMPLETE","SOURCE_INTAKE_OWNER",AuthorizationService.Path.SYSTEM,serviceSeed.grant));
        try(var c=database.apiConnection()) {
            inTransaction(c,Capability.QUERY,x->{assertTrue(service.evaluate(x,serviceRequest,true).allowed());assertFalse(service.evaluate(x,serviceSeed.request(),true).allowed());return null;});
        }
    }
    @Test void object_allow_requires_exact_subject_and_active_actor_appointment() throws Exception {
        Seed s=seed(); UUID objectGrant=UUID.randomUUID();
        var object=new AuthorizationService.Request(s.request().actor(),s.request().subject(),s.org,
                new AuthorizationService.Requirement("LEAD_INGRESS_COMPLETE","SOURCE_INTAKE_OWNER",AuthorizationService.Path.OBJECT,objectGrant));
        try(var c=database.apiConnection()) {
            inTransaction(c,Capability.COMMAND,x -> {
                sql(x,"insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values (?,?,?,?,'LEAD_INGRESS_COMPLETE','ALLOW',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),'lead.lead',?,0)",s.tenant,objectGrant,s.principal,s.appointment,s.subject); return null;
            });
            inTransaction(c,Capability.QUERY,x -> {assertTrue(service.evaluate(x,object,true).allowed());return null;});
            inTransaction(c,Capability.COMMAND,x -> {sql(x,"update identity.appointment set state='SUSPENDED',revision=revision+1 where tenant_id=? and appointment_id=?",s.tenant,s.appointment);return null;});
            inTransaction(c,Capability.QUERY,x -> {assertFalse(service.evaluate(x,object,true).allowed());return null;});
        }
    }
    @Test void delegated_path_is_complete_and_never_borrows_an_unrelated_appointment() throws Exception {
        Seed s=seed(); UUID delegate=UUID.randomUUID(), delegateAppointment=UUID.randomUUID(), delegation=UUID.randomUUID();
        var actor=new AuthorizationService.Actor(s.tenant,delegate,delegateAppointment,s.principal,s.appointment);
        var requirement=new AuthorizationService.Requirement("LEAD_INGRESS_COMPLETE","SOURCE_INTAKE_OWNER",AuthorizationService.Path.DELEGATED,delegation);
        var request=new AuthorizationService.Request(actor,s.request().subject(),s.org,requirement);
        try(var c=database.apiConnection()) {
            inTransaction(c,Capability.COMMAND,x -> {
                sql(x,"insert into identity.principal (tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) values (?,?,'HUMAN','DELEGATE',decode(repeat('01',32),'hex'),'fixture','ACTIVE',clock_timestamp())",s.tenant,delegate);
                sql(x,"insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'OWNER',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",s.tenant,delegateAppointment,delegate,s.org);
                sql(x,"insert into identity.delegation_grant (tenant_id,delegation_grant_id,source_authority_grant_id,delegator_appointment_id,delegate_appointment_id,scope_organization_unit_id,valid_from,state,created_at) values (?,?,?,?,?,?,clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",s.tenant,delegation,s.grant,s.appointment,delegateAppointment,s.org);return null;
            });
            inTransaction(c,Capability.QUERY,x -> {assertTrue(service.evaluate(x,request,true).allowed());
                var wrong=new AuthorizationService.Request(s.request().actor(),s.request().subject(),s.org,requirement);
                assertFalse(service.evaluate(x,wrong,true).allowed());return null;});
        }
    }
    @Test void final_shared_lock_blocks_identity_writer_until_transaction_ends() throws Exception {
        Seed s=seed();
        try(var c=database.apiConnection();var writer=database.apiConnection()) {
            inTransaction(c,Capability.QUERY,x -> {
                assertTrue(service.evaluate(x,s.request(),true).allowed());
                SQLException blocked=assertThrows(SQLException.class,()->inTransaction(writer,Capability.COMMAND,w->{
                    sql(w,"set local lock_timeout='100ms'"); service.lockForMutation(w,s.tenant);return null;
                }));
                assertEquals("55P03",blocked.getSQLState());return null;
            });
            inTransaction(writer,Capability.COMMAND,w->{service.lockForMutation(w,s.tenant);return null;});
        }
    }
    @Test void expiry_uses_fresh_clock_after_transaction_start() throws Exception {
        Seed s=seed();UUID timed=UUID.randomUUID();
        try(var c=database.apiConnection()) {
            inTransaction(c,Capability.COMMAND,x->{
                sql(x,"insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,valid_until,state,created_at) values (?,?,?,?,?,'LEAD_INGRESS_COMPLETE',clock_timestamp()-interval '1 day',clock_timestamp()+interval '1 second','ACTIVE',clock_timestamp())",s.tenant,timed,s.appointment,s.appointment,s.org);return null;
            });
            var request=new AuthorizationService.Request(s.request().actor(),s.request().subject(),s.org,new AuthorizationService.Requirement("LEAD_INGRESS_COMPLETE","SOURCE_INTAKE_OWNER",AuthorizationService.Path.DIRECT,timed));
            inTransaction(c,Capability.QUERY,x->{
                var start=service.evaluate(x,request,false);assertTrue(start.allowed());
                try(var p=x.prepareStatement("select pg_sleep(1.1)")){p.execute();}
                var end=service.evaluate(x,request,true);assertFalse(end.allowed());assertTrue(end.checkedAt().isAfter(start.checkedAt()));return null;
            });
        }
    }
    @Test void current_organization_reparenting_and_cycles_fail_closed()throws Exception {
        Seed s=seed();UUID child=UUID.randomUUID(),otherRoot=UUID.randomUUID();
        try(var c=database.apiConnection()) {
            inTransaction(c,Capability.COMMAND,x->{
                sql(x,"insert into identity.organization_unit (tenant_id,organization_unit_id,unit_code,display_name,parent_organization_unit_id,state,created_at) values (?,?,'CHILD','fixture',?,'ACTIVE',clock_timestamp())",s.tenant,child,s.org);
                sql(x,"insert into identity.organization_unit (tenant_id,organization_unit_id,unit_code,display_name,state,created_at) values (?,?,'OTHER_ROOT','fixture','ACTIVE',clock_timestamp())",s.tenant,otherRoot);return null;
            });
            var request=new AuthorizationService.Request(s.request().actor(),s.request().subject(),child,s.request().requirement());
            inTransaction(c,Capability.QUERY,x->{
                assertTrue(service.evaluate(x,request,false).allowed());
                try(var writer=database.apiConnection()){inTransaction(writer,Capability.COMMAND,w->{
                    service.lockForMutation(w,s.tenant);sql(w,"update identity.organization_unit set parent_organization_unit_id=?,revision=revision+1 where tenant_id=? and organization_unit_id=?",otherRoot,s.tenant,child);return null;
                });}
                assertFalse(service.evaluate(x,request,true).allowed());return null;
            });
            inTransaction(c,Capability.COMMAND,x->{
                service.lockForMutation(x,s.tenant);
                sql(x,"update identity.organization_unit set parent_organization_unit_id=?,revision=revision+1 where tenant_id=? and organization_unit_id=?",s.org,s.tenant,child);
                sql(x,"update identity.organization_unit set parent_organization_unit_id=?,revision=revision+1 where tenant_id=? and organization_unit_id=?",child,s.tenant,s.org);return null;
            });
            inTransaction(c,Capability.QUERY,x->{assertFalse(service.evaluate(x,request,true).allowed());return null;});
        }
    }
}
