package io.github.windyzhu3.ontologylaw.api;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.lead.ContactFlowFixture;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.time.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
class R2OpportunityRecoveryCommandIT extends ContactFlowFixture {
    private final OpportunityProgressProtection cipher=OpportunityProgressProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES"));
    private CommandRuntime recoveryRuntime;private CommandEnvelope command;private Actor recoveryActor;private Subject next;
    private void setupRecovery(boolean future)throws Exception {
        setupContact();var contact=execute(prepare(contact("CONNECTED_VALID")));
        final Instant due=future?Instant.now().plusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.MICROS):businessAt.plusSeconds(86400);
        Map<String,Object> payload;
        try(var c=database.apiConnection()){payload=inTransaction(c,Capability.COMMAND,x->{
            var opportunity=EventOpportunityReader.databaseBacked().forContact(x,seed.tenant(),contact.resultFact().id()).selector();grant(x,"SALES_OPPORTUNITY_OWNER");
            var first=TaskFactory.databaseBacked().createInitialOpportunity(x,seed.tenant(),seed.appointment(),opportunity,ZoneId.of("Asia/Shanghai"),businessAt);
            var progress=R2OpportunityProgressServices.create(cipher).record(x,seed.request().actor(),opportunity,first.selector(),new OpportunityProgressInput("MEETING","约定下一次沟通",businessAt,due),ZoneId.of("Asia/Shanghai"),businessAt.plusSeconds(60));
            next=progress.nextTask();var wait=EventResponsibilityReader.databaseBacked().latestWait(x,seed.tenant(),next.id());
            return Map.of("opportunityId",opportunity.id().toString(),"expectedOpportunityRevision",opportunity.revision(),"taskId",next.id().toString(),"expectedTaskRevision",next.revision(),"waitReceiptId",wait.selector().id().toString(),"waitReceiptHash",wait.selector().hash(),"progressId",progress.progress().id().toString(),"progressHash",progress.progress().hash(),"dueCutoff",due.toString());
        });}
        recoveryActor=service("OPPORTUNITY_TASK_RECOVER");
        recoveryRuntime=R2OpportunityCommandRuntime.fromSourcePolicy(policies,protection,cipher,null,"R2_RECOVERY_IT");
        command=new CommandEnvelope(CommandEnvelope.Type.valueOf("REOPEN_DUE_OPPORTUNITY_TASKS"),UUID.randomUUID(),UUID.randomUUID(),recoveryActor,payload);
    }
    private CommandResult run(CommandEnvelope input)throws Exception {try(var c=database.apiConnection()){return recoveryRuntime.execute(c,input);}}
    @Test void recovery_writes_one_receipt_audit_and_r2_event_and_exact_replay_is_read_only()throws Exception {
        setupRecovery(false);var first=assertInstanceOf(CommandOutcome.class,run(command));assertEquals(CommandOutcome.Status.SUCCEEDED,first.status());assertEquals(next.id(),first.resultFact().id());
        var before=counts();assertEquals(first,run(command));assertEquals(before,counts());
        assertEquals("OPEN",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),next.id()));
        assertEquals("1",scalar("select count(*) from execution.command_receipt r join execution.command_execution_slot s using (tenant_id,command_execution_slot_id) where s.tenant_id=? and s.command_id=?",seed.tenant(),command.commandId()));
        assertEquals("1",scalar("select count(*) from execution.domain_event d join execution.domain_event_outbox o using (tenant_id,domain_event_id) where d.tenant_id=? and d.command_id=? and d.event_type='OpportunityTaskReopenedV1' and o.queue_owner='R2_PROJECTION'",seed.tenant(),command.commandId()));
        assertEquals("1",scalar("select count(*) from audit.audit_entry_classified_v where tenant_id=? and command_id=?",seed.tenant(),command.commandId()));
    }
    @Test void same_key_changed_body_conflicts_without_another_transition()throws Exception {
        setupRecovery(false);run(command);var changed=new TreeMap<>((Map<String,Object>)command.payload());changed.put("dueCutoff",Instant.parse((String)changed.get("dueCutoff")).plusSeconds(60).toString());
        assertInstanceOf(CommandResult.Conflict.class,run(new CommandEnvelope(command.type(),command.commandId(),UUID.randomUUID(),recoveryActor,changed)));
    }
    @Test void service_revocation_denies_replay()throws Exception {
        setupRecovery(false);run(command);mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and grantee_appointment_id=?",seed.tenant(),recoveryActor.appointmentId());
        assertEquals("NOT_AUTHORIZED",assertThrows(CommandHandler.Rejected.class,()->run(command)).code());
    }
    @Test void future_wait_does_not_occupy_key_or_mutate_business()throws Exception {
        setupRecovery(true);var before=counts();assertEquals("STALE_TASK",assertThrows(CommandHandler.Rejected.class,()->run(command)).code());assertEquals(before,counts());
    }
    @Test void lost_projection_rolls_back_reopening_and_retries_same_key()throws Exception {
        setupRecovery(false);var before=counts();try(var c=database.apiConnection()){
            assertThrows(IllegalStateException.class,()->recoveryRuntime.executeProjected(c,command,(x,result)->{throw new IllegalStateException("projection failed");}));
        }
        assertEquals(before,counts());assertEquals(CommandOutcome.Status.SUCCEEDED,assertInstanceOf(CommandOutcome.class,run(command)).status());
    }
    @Test void current_human_owner_permission_is_rechecked_on_service_replay()throws Exception {
        setupRecovery(false);run(command);mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and grantee_appointment_id=? and authority_code='SALES_OPPORTUNITY_OWNER'",seed.tenant(),seed.appointment());
        assertEquals("NOT_AUTHORIZED",assertThrows(CommandHandler.Rejected.class,()->run(command)).code());
    }
    @Test void source_object_denial_cannot_be_bypassed_by_service_scope()throws Exception {
        setupRecovery(false);Subject source;
        try(var c=database.apiConnection()){source=inTransaction(c,Capability.QUERY,x->io.github.windyzhu3.ontologylaw.lead.R1EventReaders.databaseBacked().lead(x,seed.tenant(),current.subject().id()));}
        mutate("insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values (?,?,?,?,'OPPORTUNITY_TASK_RECOVER','DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),'lead.lead',?,?)",seed.tenant(),UUID.randomUUID(),recoveryActor.principalId(),seed.appointment(),source.id(),source.revision());
        var before=counts();assertThrows(CommandHandler.Rejected.class,()->run(command));assertEquals(before,counts());
    }
    @Test void concurrent_same_key_delivers_the_same_receipt_once()throws Exception {
        setupRecovery(false);try(var workers=java.util.concurrent.Executors.newFixedThreadPool(2)){
            java.util.concurrent.Callable<CommandResult> attempt=()->run(command);var results=workers.invokeAll(List.of(attempt,attempt));
            assertEquals(results.getFirst().get(),results.getLast().get());assertEquals(CommandOutcome.Status.SUCCEEDED,assertInstanceOf(CommandOutcome.class,results.getFirst().get()).status());
        }
        assertEquals(Long.toString(next.revision()+1),scalar("select revision from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),next.id()));
    }
    @Test void extra_business_fields_are_rejected_before_occupying_a_key()throws Exception {
        setupRecovery(false);var changed=new TreeMap<>((Map<String,Object>)command.payload());changed.put("businessCategory","EXECUTION");var before=counts();
        assertEquals("VALIDATION_FAILED",assertThrows(CommandHandler.Rejected.class,()->run(new CommandEnvelope(command.type(),command.commandId(),UUID.randomUUID(),recoveryActor,changed))).code());assertEquals(before,counts());
    }
    @Test void authorization_rejects_payload_subject_or_revision_substitution_against_resolved_context()throws Exception {
        setupRecovery(false);try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
            var context=new R2OpportunityRecoveryCommand(cipher).resolve(x,command);
            var facts=R2OpportunityCommandRuntime.authorization(io.github.windyzhu3.ontologylaw.lead.R1AuthorizationReaders.databaseBacked(policies));
            var policy=new R1CommandPolicy(io.github.windyzhu3.ontologylaw.identity.AuthorizationService.databaseBacked(),facts);
            for(var patch:List.of(Map.<String,Object>of("opportunityId",UUID.randomUUID().toString()),Map.<String,Object>of("expectedOpportunityRevision",88),Map.<String,Object>of("expectedTaskRevision",88))){
                var changed=new TreeMap<>((Map<String,Object>)command.payload());changed.putAll(patch);
                assertFalse(policy.authorize(x,new CommandEnvelope(command.type(),command.commandId(),UUID.randomUUID(),recoveryActor,changed),context,false).allowed());
            }
            var integers=new TreeMap<>((Map<String,Object>)command.payload());integers.put("expectedOpportunityRevision",((Number)integers.get("expectedOpportunityRevision")).intValue());integers.put("expectedTaskRevision",((Number)integers.get("expectedTaskRevision")).intValue());
            assertTrue(policy.authorize(x,new CommandEnvelope(command.type(),command.commandId(),UUID.randomUUID(),recoveryActor,integers),context,false).allowed());return null;
        });}
    }
}
