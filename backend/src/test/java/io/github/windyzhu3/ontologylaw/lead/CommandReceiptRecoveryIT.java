package io.github.windyzhu3.ontologylaw.lead;

import io.github.windyzhu3.ontologylaw.api.CommandReceiptRecoveryService;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandReceiptRecoveryIT extends LeadBusinessFixture {
    @Test void legacy_metadata_fails_closed_but_original_request_replay_does_not_backfill() throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL,false);
        var real=io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked("LEGACY_IT");
        runtime=new CommandRuntime(new LeadCommands(sources,LeadProtectionTest.protection()).handlers(),AuthorizationService.databaseBacked(),(c,e)->{
            String summary=CanonicalJson.encode(Map.of("result",e.result(),"authorizationEvidence",e.authorization().evidence()));
            real.append(c,new io.github.windyzhu3.ontologylaw.audit.AuditAppender.Entry(e.id(),e.commandId(),e.commandType(),e.correlationId(),e.result(),e.authorization(),summary,CanonicalJson.digest(summary)));
        },R1AuthorizationReaders.databaseBacked(sources),R1EventReaders.databaseBacked());
        var command=capture("legacy-receipt",false);var original=run(command);var before=counts();
        assertEquals(503,read(command).status());assertEquals(before,counts());assertEquals(original,run(command));assertEquals(before,counts());
    }
    @Test void source_organization_revision_change_never_moves_original_receipt_authorization() throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL,false);var command=capture("original-organization",false);run(command);
        mutate("update identity.organization_unit set display_name='Changed fixture',revision=revision+1 where tenant_id=? and organization_unit_id=?",seed.tenant(),seed.org());
        var before=counts();assertEquals(403,read(command).status());assertEquals(before,counts());
    }
    @Test void successful_get_acknowledgement_loss_withholds_body_even_when_audit_committed() throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL,false);var command=capture("read-ack-loss",false);run(command);var before=counts();
        try(var real=database.apiConnection()) {
            var lost=(java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(java.sql.Connection.class.getClassLoader(),new Class<?>[]{java.sql.Connection.class},(p,m,a)->{
                try{var value=m.invoke(real,a);if(m.getName().equals("commit"))throw new java.sql.SQLException("Synthetic commit acknowledgement loss","08006");return value;}
                catch(java.lang.reflect.InvocationTargetException ex){throw ex.getCause();}
            });
            var response=service().read(lost,command.actor(),command.commandId(),UUID.randomUUID());assertEquals(503,response.status());assertNull(response.body());
        }
        delta(before,List.of(0L,0L,0L,0L,0L,0L,0L,0L,0L,1L));
    }
    @Test void append_failure_before_acknowledged_commit_rolls_back_read_audit_and_withholds_body() throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL,false);var command=capture("read-append-failure",false);run(command);var before=counts();
        try(var real=database.apiConnection()) {
            var failing=(java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(java.sql.Connection.class.getClassLoader(),new Class<?>[]{java.sql.Connection.class},(p,m,a)->{
                if(m.getName().equals("prepareStatement")&&a[0] instanceof String sql&&sql.startsWith("insert into \"audit\"."))throw new java.sql.SQLException("Synthetic append failure","08006");
                try{return m.invoke(real,a);}catch(java.lang.reflect.InvocationTargetException ex){throw ex.getCause();}
            });
            var response=service().read(failing,command.actor(),command.commandId(),UUID.randomUUID());assertEquals(503,response.status());assertNull(response.body());
        }
        assertEquals(before,counts());
    }
    @Test void later_same_natural_key_lead_deny_blocks_a_previously_rejected_capture_receipt() throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL,false);
        mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_code='LEAD_INGRESS_COMPLETE'",seed.tenant());
        var command=capture("later-natural-key",false);assertEquals(CommandOutcome.Status.REJECTED,run(command).status());
        io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject lead;
        try(var c=database.apiConnection()){lead=io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.inTransaction(c,io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.Capability.COMMAND,x->{
            var protection=LeadProtectionTest.protection();return LeadIngressService.databaseBacked(protection).capture(x,seed.tenant(),captureValues("later-natural-key",false),protection.hmac(seed.tenant(),LeadProtection.Purpose.SOURCE_RECORD_KEY,"FIXTURE","later-natural-key"),java.time.Instant.now()).selector();
        });}
        mutate("insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values (?,?,?,?,'LEAD_CAPTURE','DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),'lead.lead',?,?)",seed.tenant(),UUID.randomUUID(),seed.principal(),seed.appointment(),lead.id(),lead.revision());
        var before=counts();assertEquals(403,read(command).status());assertEquals(before,counts());
    }
    private CommandReceiptRecoveryService service() {return new CommandReceiptRecoveryService(sources,LeadProtectionTest.protection(),null,"RECEIPT_IT");}
    private CommandReceiptRecoveryService.Response read(CommandEnvelope command) throws Exception {
        try(var c=database.apiConnection()){return service().read(c,command.actor(),command.commandId(),UUID.randomUUID());}
    }
    @Test void repeated_get_returns_original_immutable_projection_after_one_committed_disclosure_each() throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL,false);var command=capture("receipt-readable",false);var outcome=run(command);
        var before=counts();var first=read(command);assertEquals(200,first.status());
        assertEquals(command.commandId().toString(),first.body().get("commandId"));assertEquals(outcome.receiptId().toString(),first.body().get("receiptId"));
        assertFalse(first.body().toString().contains(outcome.resultFact().id().toString()));assertEquals("no-store",first.cacheControl());
        delta(before,List.of(0L,0L,0L,0L,0L,0L,0L,0L,0L,1L));
        assertEquals(first.body(),read(command).body());delta(before,List.of(0L,0L,0L,0L,0L,0L,0L,0L,0L,2L));
    }
    @Test void rejected_capture_without_a_lead_is_readable_under_original_current_source_scope() throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL,false);
        mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_code='LEAD_INGRESS_COMPLETE'",seed.tenant());
        var command=capture("receipt-rejected",false);assertEquals(CommandOutcome.Status.REJECTED,run(command).status());
        var response=read(command);assertEquals(200,response.status());assertEquals("REJECTED",response.body().get("outcome"));assertFalse(response.body().containsKey("resultFact"));
        assertEquals("0",scalar("select count(*) from lead.lead where tenant_id=?",seed.tenant()));
    }
    @Test void another_appointment_of_same_principal_is_not_original_actor_and_discloses_nothing() throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL,false);var command=capture("original-actor",false);run(command);
        UUID other=UUID.randomUUID();mutate("insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'OWNER',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),other,seed.principal(),seed.org());
        var actor=new AuthorizationService.Actor(seed.tenant(),seed.principal(),other,null,null);var before=counts();
        try(var c=database.apiConnection()){var response=service().read(c,actor,command.commandId(),UUID.randomUUID());assertEquals(404,response.status());assertNull(response.body());}
        assertEquals(before,counts());
    }
    @Test void current_capture_revocation_denies_read_even_though_original_audit_allowed() throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL,false);var command=capture("revoked-read",false);run(command);
        mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_code='LEAD_CAPTURE'",seed.tenant());
        var before=counts();assertEquals(403,read(command).status());assertEquals(before,counts());
    }
}
