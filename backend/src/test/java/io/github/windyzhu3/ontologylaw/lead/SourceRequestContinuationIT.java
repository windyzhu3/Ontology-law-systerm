package io.github.windyzhu3.ontologylaw.lead;

import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Actual R1 ingress and ACK followed by the named R2 source disposition protocol. */
class SourceRequestContinuationIT extends LeadBusinessFixture {
    private CommandEnvelope acknowledgement() throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.AUTOMATIC,false);
        run(capture("source-continuation",true));
        var requested=run(command(task("RESOLVE_LEAD_ROUTING_GAP"),Map.of("decisionCode","REQUEST_SOURCE_INTAKE_STOP","rationaleSummary","合成来源请求")));
        return command(task("ACK_SOURCE_INTAKE_STOP_REQUEST"),Map.of("causalDecisionId",requested.resultFact().id().toString(),"causalDecisionHash",requested.resultFact().hash(),"rationaleSummary","已收到，线索交主管明确去向"));
    }
    @Test void ack_creates_one_supervisor_responsibility_and_replay_keeps_it() throws Exception {
        var ack=acknowledgement();var receipt=run(ack);assertEquals(CommandOutcome.Status.SUCCEEDED,receipt.status());
        var next=task("RESOLVE_SOURCE_REQUEST");assertEquals("OPEN",next.state());assertEquals(seed.appointment(),next.owner());
        assertEquals("RECORD_SOURCE_REQUEST_CONTINUATION",next.type().command);
        var before=counts();assertEquals(receipt,run(ack));assertEquals(before,counts());
        assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='RESOLVE_SOURCE_REQUEST' and state='OPEN'",seed.tenant()));
    }
    @Test void missing_supervisor_preserves_ack_instead_of_losing_the_lead() throws Exception {
        var ack=acknowledgement();mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_code='LEAD_ROUTING_DECIDE'",seed.tenant());
        terminal(ack,"SUPERVISOR_UNRESOLVED");assertEquals("OPEN",task("ACK_SOURCE_INTAKE_STOP_REQUEST").state());
    }
    @Test void acknowledgement_returns_to_causal_owner_despite_another_authorized_supervisor() throws Exception {
        var ack=acknowledgement();actor("HUMAN","LEAD_ROUTING_DECIDE");
        assertEquals(CommandOutcome.Status.SUCCEEDED,run(ack).status());
        assertEquals(seed.appointment(),task("RESOLVE_SOURCE_REQUEST").owner());
        var current=task("RESOLVE_SOURCE_REQUEST");
        completed(current,run(command(current,Map.of("decisionCode","END_LEAD","rationaleSummary","Existing responsible appointment closes its own chain"))));
    }
    @Test void acknowledgement_commit_rechecks_origin_instead_of_promoting_its_new_fallback_to_incumbent() throws Exception {
        var ack=acknowledgement();
        mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_code='LEAD_ROUTING_DECIDE'",seed.tenant());
        actor("HUMAN","LEAD_ROUTING_DECIDE");
        var handler=new LeadCommands(sources,LeadProtectionTest.protection()).handlers().stream().filter(h->h.type()==ack.type()).findFirst().orElseThrow();
        try(var c=database.apiConnection()){
            assertEquals("STALE_SUBJECT",assertThrows(CommandHandler.Rejected.class,()->io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.inTransaction(c,io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.Capability.COMMAND,x->{
                var context=handler.resolve(x,ack);handler.lockRoots(x,ack,context);handler.validateBeforeWork(x,ack,context);
                var result=handler.execute(x,ack,context);
                // Deterministically expose a changed final eligibility boundary without a timing race.
                grant(x,"LEAD_ROUTING_DECIDE");
                handler.validateBeforeCommit(x,ack,context,result);return null;
            })).code());
        }
        assertEquals("OPEN",task("ACK_SOURCE_INTAKE_STOP_REQUEST").state());
        assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='RESOLVE_SOURCE_REQUEST'",seed.tenant()));
    }
    @Test void end_is_an_explicit_terminal_decision_without_fabricating_contact_or_assignment() throws Exception {
        run(acknowledgement());var current=task("RESOLVE_SOURCE_REQUEST");
        var action=command(current,Map.of("decisionCode","END_LEAD","rationaleSummary","合成线索决定结束"));
        var receipt=run(action);completed(current,receipt);assertEquals(receipt,run(action));
        assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and state in ('OPEN','WAITING')",seed.tenant(),current.lead().id()));
        assertEquals("0",scalar("select count(*) from lead.lead_assignment where tenant_id=?",seed.tenant()));
        assertEquals("0",scalar("select count(*) from lead.lead_contact_result where tenant_id=?",seed.tenant()));
        assertEquals("SOURCE_REQUEST_CONTINUATION",scalar("select decision_contract_code from responsibility.decision_record where tenant_id=? and decision_record_id=?",seed.tenant(),receipt.resultFact().id()));
    }

    @Test void selected_sales_owner_is_used_instead_of_the_first_eligible_candidate() throws Exception {
        run(acknowledgement());var current=task("RESOLVE_SOURCE_REQUEST");
        actor("HUMAN","SALES_CONTACT_OWNER");actor("HUMAN","SALES_CONTACT_OWNER");
        java.util.UUID selected;
        try(var c=database.apiConnection()){selected=io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.inTransaction(c,io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.Capability.QUERY,x->new AssignmentPolicy().sales(x,seed.tenant(),current.lead(),sources.find("FIXTURE")).getLast().appointmentId());}
        var receipt=run(command(current,Map.of("decisionCode","ASSIGN_SELECTED","ownerAppointmentId",selected.toString(),"rationaleSummary","主管明确选定合格负责人")));
        completed(current,receipt);var contact=task("CONTACT_LEAD");assertEquals(selected,contact.owner());assertEquals(current.lead().revision()+1,contact.lead().revision());
        assertEquals("1",scalar("select count(*) from lead.lead_assignment where tenant_id=?",seed.tenant()));
    }
    @Test void unavailable_candidate_preserves_supervisor_task_without_fake_assignment() throws Exception {
        run(acknowledgement());var current=task("RESOLVE_SOURCE_REQUEST");
        terminal(command(current,Map.of("decisionCode","ASSIGN_SELECTED","ownerAppointmentId",UUID.randomUUID().toString(),"rationaleSummary","合成失效候选")),"STALE_SUBJECT");
        assertEquals("OPEN",task("RESOLVE_SOURCE_REQUEST").state());assertEquals("0",scalar("select count(*) from lead.lead_assignment where tenant_id=?",seed.tenant()));
    }
    @Test void review_wait_has_exact_time_and_preserves_original_deadline() throws Exception {
        run(acknowledgement());var current=task("RESOLVE_SOURCE_REQUEST");
        String originalDue=scalar("select original_sla_due_at from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),current.selector().id());
        var due=java.time.Instant.now().plusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        var action=command(current,Map.of("decisionCode","SCHEDULE_REVIEW","reviewAt",due.toString(),"rationaleSummary","候选尚未到位，约定复查"));
        var receipt=run(action);completed(current,receipt);assertEquals(receipt,run(action));var next=task("RESOLVE_SOURCE_REQUEST");assertEquals("WAITING",next.state());
        assertEquals(originalDue,scalar("select original_sla_due_at from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),next.selector().id()));
        assertEquals("R2_SOURCE_REQUEST_REVIEW_WAIT_V1",scalar("select wait_contract_code from responsibility.wait_receipt where tenant_id=? and task_occurrence_id=?",seed.tenant(),next.selector().id()));
        assertEquals("true",scalar("select (resume_due_at=cast(? as timestamptz))::text from responsibility.wait_receipt where tenant_id=? and task_occurrence_id=?",due.toString(),seed.tenant(),next.selector().id()));
    }
    @Test void scheduled_review_reopens_once_with_exact_wait_and_original_deadline() throws Exception {
        run(acknowledgement());var current=task("RESOLVE_SOURCE_REQUEST");
        var due=java.time.Instant.now().plusSeconds(3).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        run(command(current,Map.of("decisionCode","SCHEDULE_REVIEW","reviewAt",due.toString(),"rationaleSummary","合成到期复查")));
        var waiting=task("RESOLVE_SOURCE_REQUEST");
        var worker=actor("SERVICE","ROUTING_REVIEW_TASK_RECOVER");
        io.github.windyzhu3.ontologylaw.execution.R1EventFacts.Wait wait;
        try(var c=database.apiConnection()){wait=io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.inTransaction(c,io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.Capability.QUERY,x->R1EventReaders.databaseBacked().latestWait(x,seed.tenant(),waiting.selector().id()));}
        var waitId=wait.selector().id().toString();var waitHash=wait.selector().hash();
        var type=CommandEnvelope.Type.valueOf("REOPEN_DUE_SOURCE_REQUEST_TASKS");
        var early=new CommandEnvelope(type,UUID.randomUUID(),UUID.randomUUID(),worker,Map.of("taskId",waiting.selector().id().toString(),"expectedTaskRevision",waiting.selector().revision(),"waitReceiptId",waitId,"waitReceiptHash",waitHash,"dueCutoff",java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString()));
        try(var c=database.apiConnection()){assertEquals("VALIDATION_FAILED",assertThrows(CommandHandler.Rejected.class,()->runtime.execute(c,early)).code());}
        long remaining=java.time.Duration.between(java.time.Instant.now(),due.plusMillis(50)).toMillis();if(remaining>0)Thread.sleep(remaining);
        var recovery=new CommandEnvelope(type,UUID.randomUUID(),UUID.randomUUID(),worker,Map.of("taskId",waiting.selector().id().toString(),"expectedTaskRevision",waiting.selector().revision(),"waitReceiptId",waitId,"waitReceiptHash",waitHash,"dueCutoff",due.toString()));
        var receipt=run(recovery);assertEquals(CommandOutcome.Status.SUCCEEDED,receipt.status());assertEquals(receipt,run(recovery));
        var reopened=task("RESOLVE_SOURCE_REQUEST");assertEquals("OPEN",reopened.state());assertEquals(waiting.selector().id(),reopened.selector().id());assertEquals(waiting.selector().revision()+1,reopened.selector().revision());
    }
    @Test void invalid_review_dates_and_hidden_fields_are_rejected_without_business_writes() throws Exception {
        run(acknowledgement());var current=task("RESOLVE_SOURCE_REQUEST");var before=counts();
        for(var values:List.of(Map.<String,Object>of("decisionCode","SCHEDULE_REVIEW","reviewAt","not-a-date","rationaleSummary","复查"),Map.<String,Object>of("decisionCode","END_LEAD","reviewAt","2030-01-01T00:00:00Z","rationaleSummary","结束")))
            assertEquals("VALIDATION_FAILED",assertThrows(CommandHandler.Rejected.class,()->LeadCommands.candidate(CommandEnvelope.Type.RECORD_SOURCE_REQUEST_CONTINUATION,values)).code());
        assertEquals(before,counts());
        terminal(command(current,Map.of("decisionCode","SCHEDULE_REVIEW","reviewAt","2000-01-01T00:00:00Z","rationaleSummary","过期安排")),"VALIDATION_FAILED");assertEquals("OPEN",task("RESOLVE_SOURCE_REQUEST").state());
    }
    @Test void source_decision_storage_failure_keeps_original_responsibility() throws Exception {
        run(acknowledgement());var current=task("RESOLVE_SOURCE_REQUEST");var end=command(current,Map.of("decisionCode","END_LEAD","rationaleSummary","回滚验证"));
        for(String table:List.of("responsibility.task_occurrence","execution.domain_event","execution.command_receipt","audit.audit_entry"))storageFailureRollsBack(end,table);
        completed(current,run(end));
    }
    @Test void revoked_supervisor_is_not_reopened_by_an_authorized_worker() throws Exception {
        run(acknowledgement());var current=task("RESOLVE_SOURCE_REQUEST");var due=java.time.Instant.now().plusSeconds(2).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        run(command(current,Map.of("decisionCode","SCHEDULE_REVIEW","reviewAt",due.toString(),"rationaleSummary","复查")));var waiting=task("RESOLVE_SOURCE_REQUEST");var worker=actor("SERVICE","ROUTING_REVIEW_TASK_RECOVER");
        io.github.windyzhu3.ontologylaw.execution.R1EventFacts.Wait wait;
        try(var c=database.apiConnection()){wait=io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.inTransaction(c,io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.Capability.QUERY,x->R1EventReaders.databaseBacked().latestWait(x,seed.tenant(),waiting.selector().id()));}
        mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_code='LEAD_ROUTING_DECIDE'",seed.tenant());
        long remaining=java.time.Duration.between(java.time.Instant.now(),due.plusMillis(50)).toMillis();if(remaining>0)Thread.sleep(remaining);
        var recovery=new CommandEnvelope(CommandEnvelope.Type.REOPEN_DUE_SOURCE_REQUEST_TASKS,UUID.randomUUID(),UUID.randomUUID(),worker,Map.of("taskId",waiting.selector().id().toString(),"expectedTaskRevision",waiting.selector().revision(),"waitReceiptId",wait.selector().id().toString(),"waitReceiptHash",wait.selector().hash(),"dueCutoff",due.toString()));
        var before=counts();try(var c=database.apiConnection()){assertEquals("NOT_AUTHORIZED",assertThrows(CommandHandler.Rejected.class,()->runtime.execute(c,recovery)).code());}
        assertEquals(before,counts());assertEquals("WAITING",task("RESOLVE_SOURCE_REQUEST").state());
    }
}
