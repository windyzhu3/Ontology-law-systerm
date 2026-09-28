package io.github.windyzhu3.ontologylaw.opportunity;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.lead.ContactFlowFixture;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.time.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class OpportunityCommandIT extends ContactFlowFixture {
    private final OpportunityProgressProtection cipher=OpportunityProgressProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES"));
    private Map<String,Object> values;
    private void setupOpportunity()throws Exception {
        setupContact();var contact=execute(prepare(contact("CONNECTED_VALID")));
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            var opportunity=EventOpportunityReader.databaseBacked().forContact(x,seed.tenant(),contact.resultFact().id()).selector();
            grant(x,"SALES_OPPORTUNITY_OWNER");current=TaskFactory.databaseBacked().createInitialOpportunity(x,seed.tenant(),seed.appointment(),opportunity,ZoneId.of("Asia/Shanghai"),businessAt);return null;
        });}
        runtime=io.github.windyzhu3.ontologylaw.api.R2OpportunityCommandRuntime.create(policies,protection,cipher,"R2_COMMAND_IT",ZoneId.of("Asia/Shanghai"));
        values=Map.of("progressTypeCode","PHONE_CONNECTED","progressSummary","Confirmed scope and next follow-up","occurredAt",businessAt.toString(),"nextCheckAt",Instant.now().plusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString());
    }
    private CommandEnvelope save(){return new CommandEnvelope(CommandEnvelope.Type.SAVE_ACTION_DRAFT,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),Map.of("actionCode","RECORD_OPPORTUNITY_PROGRESS","schemaVersion",1,"values",values),null,new CommandEnvelope.DraftPrecondition(current.selector().id(),null,"*"));}
    private CommandEnvelope confirmation()throws Exception {
        try(var c=database.apiConnection()){return inTransaction(c,Capability.QUERY,x->{
            var draft=ActionDraftService.databaseBacked().read(x,seed.tenant(),current.selector().id());var body=new TreeMap<String,Object>(values);
            body.put("draftId",draft.selector().id().toString());body.put("expectedDraftRevision",draft.selector().revision());body.put("draftDigest",draft.digest());
            return new CommandEnvelope(CommandEnvelope.Type.RECORD_OPPORTUNITY_PROGRESS,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),body,new CommandEnvelope.TaskPrecondition(current.selector().id(),R1ResourceTags.task(seed.request().actor(),current.selector(),current.state())));
        });}
    }
    @Test void saved_draft_confirmed_progress_receipt_and_replay_share_one_business_result()throws Exception {
        setupOpportunity();var save=save();assertEquals(CommandOutcome.Status.SUCCEEDED,execute(save).status());
        assertEquals("OPEN",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),current.selector().id()));
        var command=confirmation();var result=execute(command);assertEquals(CommandOutcome.Status.SUCCEEDED,result.status());
        assertEquals(result.resultFact(),execute(command).resultFact());assertEquals(CommandOutcome.Status.SUCCEEDED,execute(save).status());
        assertEquals("2",scalar("select count(*) from execution.domain_event_outbox where tenant_id=? and queue_owner='R2_PROJECTION'",seed.tenant()));
        assertEquals("0",scalar("select count(*) from execution.domain_event e join execution.domain_event_outbox o using (tenant_id,domain_event_id) where e.tenant_id=? and e.command_id=? and o.queue_owner='R1_PROJECTION'",seed.tenant(),command.commandId()));
        assertEquals("{}",scalar("select event_payload::text from execution.domain_event where tenant_id=? and command_id=?",seed.tenant(),command.commandId()));
        assertEquals("1",scalar("select count(*) from opportunity.opportunity_progress where tenant_id=?",seed.tenant()));
        assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and predecessor_task_occurrence_id=? and state='WAITING'",seed.tenant(),current.selector().id()));
        assertEquals("CONFIRMED",scalar("select state from responsibility.action_draft where tenant_id=? and task_occurrence_id=?",seed.tenant(),current.selector().id()));
    }
    @Test void changed_confirmation_cannot_consume_draft_or_complete_responsibility()throws Exception {
        setupOpportunity();execute(save());var command=confirmation();var body=new TreeMap<String,Object>((Map<String,Object>)command.payload());body.put("progressSummary","Changed after confirmation");
        var changed=new CommandEnvelope(command.type(),command.commandId(),command.correlationId(),command.actor(),body,command.taskPrecondition());
        assertEquals("DRAFT_DIGEST_MISMATCH",execute(changed).rejectionCode());
        assertEquals("DRAFT",scalar("select state from responsibility.action_draft where tenant_id=? and task_occurrence_id=?",seed.tenant(),current.selector().id()));
        assertEquals("0",scalar("select count(*) from opportunity.opportunity_progress where tenant_id=?",seed.tenant()));
    }

    @Test void rejection_after_draft_confirmation_rolls_back_confirmation_and_all_business_writes()throws Exception {
        setupOpportunity();values=new TreeMap<>(values);values.put("occurredAt",businessAt.minusSeconds(3600).toString());
        execute(save());assertEquals("VALIDATION_FAILED",execute(confirmation()).rejectionCode());
        assertEquals("DRAFT",scalar("select state from responsibility.action_draft where tenant_id=? and task_occurrence_id=?",seed.tenant(),current.selector().id()));
        assertEquals("OPEN",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),current.selector().id()));
        assertEquals("0",scalar("select count(*) from opportunity.opportunity_progress where tenant_id=?",seed.tenant()));
        assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and predecessor_task_occurrence_id=?",seed.tenant(),current.selector().id()));
    }
    @Test void revoked_permission_blocks_receipt_replay_without_disclosing_previous_result()throws Exception {
        setupOpportunity();execute(save());var command=confirmation();assertEquals(CommandOutcome.Status.SUCCEEDED,execute(command).status());
        mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and grantee_appointment_id=? and authority_code='SALES_OPPORTUNITY_OWNER'",seed.tenant(),seed.appointment());
        try(var c=database.apiConnection()){assertEquals("NOT_AUTHORIZED",assertThrows(CommandHandler.Rejected.class,()->runtime.execute(c,command)).code());}
        assertEquals("1",scalar("select count(*) from opportunity.opportunity_progress where tenant_id=?",seed.tenant()));
    }
    @Test void same_command_key_with_changed_body_conflicts_and_does_not_append_progress()throws Exception {
        setupOpportunity();execute(save());var command=confirmation();execute(command);
        var body=new TreeMap<String,Object>((Map<String,Object>)command.payload());body.put("progressSummary","Different request");
        var changed=new CommandEnvelope(command.type(),command.commandId(),command.correlationId(),command.actor(),body,command.taskPrecondition());
        try(var c=database.apiConnection()){assertInstanceOf(CommandResult.Conflict.class,runtime.execute(c,changed));}
        assertEquals("1",scalar("select count(*) from opportunity.opportunity_progress where tenant_id=?",seed.tenant()));
    }

    @Test void composed_runtime_keeps_r1_contact_draft_and_opportunity_opening_connected()throws Exception {
        setupContact();runtime=io.github.windyzhu3.ontologylaw.api.R2OpportunityCommandRuntime.create(policies,protection,cipher,"R2_R1_COMPAT_IT",ZoneId.of("Asia/Shanghai"));
        var contact=contact("CONNECTED_VALID");
        var draftCommand=new CommandEnvelope(CommandEnvelope.Type.SAVE_ACTION_DRAFT,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),Map.of("actionCode","RECORD_CONTACT_RESULT","schemaVersion",1,"values",contact),null,new CommandEnvelope.DraftPrecondition(current.selector().id(),null,"*"));
        assertEquals(CommandOutcome.Status.SUCCEEDED,execute(draftCommand).status());
        try(var c=database.apiConnection()){
            var command=inTransaction(c,Capability.QUERY,x->{var draft=ActionDraftService.databaseBacked().read(x,seed.tenant(),current.selector().id());var body=new TreeMap<String,Object>(contact);body.put("draftId",draft.selector().id().toString());body.put("expectedDraftRevision",draft.selector().revision());body.put("draftDigest",draft.digest());return new CommandEnvelope(CommandEnvelope.Type.RECORD_CONTACT_RESULT,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),body,new CommandEnvelope.TaskPrecondition(current.selector().id(),R1ResourceTags.task(seed.request().actor(),current.selector(),current.state())));});
            assertEquals(CommandOutcome.Status.SUCCEEDED,execute(command).status());
        }
        assertEquals("1",scalar("select count(*) from opportunity.opportunity where tenant_id=?",seed.tenant()));
        assertEquals("0",scalar("select count(*) from execution.domain_event_outbox where tenant_id=? and queue_owner='R2_PROJECTION'",seed.tenant()));
    }

    @Test void failed_response_projection_rolls_back_receipt_progress_successor_and_confirmation_and_allows_retry()throws Exception {
        setupOpportunity();execute(save());var command=confirmation();
        try(var c=database.apiConnection()){
            assertThrows(java.sql.SQLException.class,()->runtime.executeProjected(c,command,(tx,result)->{throw new java.sql.SQLException("Fixture response failure","XX000");}));
        }
        assertEquals("0",scalar("select count(*) from opportunity.opportunity_progress where tenant_id=?",seed.tenant()));
        try(var c=database.apiConnection()){assertNull(inTransaction(c,Capability.QUERY,x->CommandReceiptReader.databaseBacked().read(x,seed.tenant(),command.commandId())));}
        assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and predecessor_task_occurrence_id=?",seed.tenant(),current.selector().id()));
        assertEquals("DRAFT",scalar("select state from responsibility.action_draft where tenant_id=? and task_occurrence_id=?",seed.tenant(),current.selector().id()));
        assertEquals(CommandOutcome.Status.SUCCEEDED,execute(command).status());
        assertEquals("1",scalar("select count(*) from opportunity.opportunity_progress where tenant_id=?",seed.tenant()));
    }

    private io.github.windyzhu3.ontologylaw.api.CommandReceiptRecoveryService.Response recover(CommandEnvelope command)throws Exception {
        try(var c=database.apiConnection()){return new io.github.windyzhu3.ontologylaw.api.CommandReceiptRecoveryService(policies,protection,null,"R2_RECOVERY_IT").read(c,command.actor(),command.commandId(),UUID.randomUUID());}
    }
    @Test void public_receipt_recovery_returns_only_opaque_progress_reference_and_rechecks_revocation()throws Exception {
        setupOpportunity();var draft=save();execute(draft);var command=confirmation();var result=execute(command);
        var response=recover(command);assertEquals(200,response.status());assertEquals("no-store",response.cacheControl());
        var fact=(Map<?,?>)response.body().get("resultFact");assertEquals("OPPORTUNITY_PROGRESS",fact.get("factType"));assertEquals(result.resultFact().hash(),fact.get("digest"));
        String json=CanonicalJson.encode(response.body());assertFalse(json.contains(result.resultFact().id().toString()));assertFalse(json.contains((String)values.get("progressSummary")));
        assertEquals(200,recover(draft).status());
        mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and grantee_appointment_id=? and authority_code='SALES_OPPORTUNITY_OWNER'",seed.tenant(),seed.appointment());
        var denied=recover(command);assertEquals(403,denied.status());assertNull(denied.body());assertEquals(403,recover(draft).status());
    }
    @Test void rejected_progress_receipt_is_recoverable_without_advancing_business()throws Exception {
        setupOpportunity();values=new TreeMap<>(values);values.put("occurredAt",businessAt.minusSeconds(3600).toString());execute(save());
        var command=confirmation();assertEquals("VALIDATION_FAILED",execute(command).rejectionCode());
        var response=recover(command);assertEquals(200,response.status());assertEquals("REJECTED",response.body().get("outcome"));assertEquals("VALIDATION_FAILED",response.body().get("rejectionCode"));
        assertEquals("0",scalar("select count(*) from opportunity.opportunity_progress where tenant_id=?",seed.tenant()));
    }
}
