package io.github.windyzhu3.ontologylaw.lead;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Disposable legacy ACK fixture. Owner-port tests, not command/audit acceptance. */
class SourceRequestHistoricalRepairIT extends LeadBusinessFixture {
    private final TaskFactory tasks=TaskFactory.databaseBacked();
    private TaskFactory.Task legacyAck() throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.AUTOMATIC,false);
        run(capture("historical-source",true));
        var request=run(command(task("RESOLVE_LEAD_ROUTING_GAP"),Map.of("decisionCode","REQUEST_SOURCE_INTAKE_STOP","rationaleSummary","Synthetic legacy request")));
        var ack=task("ACK_SOURCE_INTAKE_STOP_REQUEST");
        // Seed the pre-F07 completed ACK through its real Owner fact validators, without a successor.
        try(var c=database.apiConnection()){return inTransaction(c,Capability.COMMAND,x->{
            var now=tasks.now(x);var v=new TreeMap<String,Object>();
            v.put("tenantId",seed.tenant().toString());v.put("subject",Map.of("type",ack.subject().type(),"id",ack.subject().id().toString(),"revision",ack.subject().revision()));
            v.put("authoritySlot",ack.type().slot);v.put("decisionCode","SOURCE_INTAKE_STOP_REQUEST_ACKNOWLEDGED");v.put("rationaleSummary","Synthetic legacy ACK");
            v.put("causalDecisionId",request.resultFact().id().toString());v.put("causalDecisionHash",request.resultFact().hash());
            var fact=tasks.decision(x,seed.tenant(),ack,seed.appointment(),"SOURCE_INTAKE_STOP_REQUEST_ACKNOWLEDGED","SOURCE_INTAKE_STOP_REQUEST_ACKNOWLEDGED","Synthetic legacy ACK",v,now);
            tasks.complete(x,seed.tenant(),ack,fact,now);return tasks.read(x,seed.tenant(),ack.selector().id());
        });}
    }
    private TaskFactory.Task restore(Connection c,TaskFactory.Task ack) throws Exception {
        return tasks.restoreHistoricalSourceRequest(c,seed.tenant(),seed.appointment(),ack,ZoneId.of("Asia/Shanghai"),tasks.now(c));
    }
    @Test void restore_preserves_ack_and_uses_original_completion_deadline() throws Exception {
        var ack=legacyAck();
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            TaskFactory.Task next;try{next=restore(x,ack);}catch(Exception ex){throw new RuntimeException(ex);}
            assertEquals(ack,tasks.read(x,seed.tenant(),ack.selector().id()));assertEquals("OPEN",next.state());assertEquals(ack.subject(),next.subject());
            assertEquals(ack.completion(),tasks.causalSourceRequest(x,seed.tenant(),next));
            try(var p=x.prepareStatement("select a.completed_at,n.original_sla_due_at from responsibility.task_occurrence a join responsibility.task_occurrence n on n.tenant_id=a.tenant_id where a.tenant_id=? and a.task_occurrence_id=? and n.task_occurrence_id=?")){
                p.setObject(1,seed.tenant());p.setObject(2,ack.selector().id());p.setObject(3,next.selector().id());try(var r=p.executeQuery()){assertTrue(r.next());assertEquals(R1BusinessTime.due(r.getObject(1,OffsetDateTime.class).toInstant(),14400,ZoneId.of("Asia/Shanghai")),r.getObject(2,OffsetDateTime.class).toInstant());}}
            return null;
        });}
    }
    @Test void second_restore_cannot_duplicate_an_existing_continuation() throws Exception {
        var ack=legacyAck();try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            try{restore(x,ack);}catch(Exception ex){throw new RuntimeException(ex);}
            assertEquals("STALE_TASK",assertThrows(CommandHandler.Rejected.class,()->restore(x,ack)).code());return null;
        });}
    }
    @Test void completed_terminal_continuation_must_not_be_restored() throws Exception {
        var ack=legacyAck();try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{try{restore(x,ack);}catch(Exception ex){throw new RuntimeException(ex);}return null;});}
        run(command(task("RESOLVE_SOURCE_REQUEST"),Map.of("decisionCode","END_LEAD","rationaleSummary","Synthetic terminal decision")));
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{assertEquals("STALE_TASK",assertThrows(CommandHandler.Rejected.class,()->restore(x,ack)).code());return null;});}
    }
    @Test void changed_ack_selector_is_rejected() throws Exception {
        var ack=legacyAck();var stale=new TaskFactory.Task(new io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject(ack.selector().type(),ack.selector().id(),0L,null),ack.owner(),ack.type(),ack.subject(),ack.state(),ack.createdAt(),ack.completion());
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{assertEquals("STALE_TASK",assertThrows(CommandHandler.Rejected.class,()->restore(x,stale)).code());return null;});}
    }
    private CommandEnvelope repairCommand(TaskFactory.Task ack) throws Exception {
        var worker=actor("SERVICE","ROUTING_REVIEW_TASK_RECOVER");
        return new CommandEnvelope(CommandEnvelope.Type.valueOf("RESTORE_SOURCE_REQUEST_TASK"),UUID.randomUUID(),UUID.randomUUID(),worker,Map.of(
            "ackTaskId",ack.selector().id().toString(),"expectedAckTaskRevision",ack.selector().revision(),"leadId",ack.subject().id().toString(),
            "expectedLeadRevision",ack.subject().revision(),"expectedAckOwnerAppointmentId",ack.owner().toString(),"ackDecisionId",ack.completion().id().toString(),
            "ackDecisionHash",ack.completion().hash(),"supervisorAppointmentId",seed.appointment().toString()));
    }
    @Test void named_repair_runtime_records_receipt_audit_and_replays_without_duplicate() throws Exception {
        var ack=legacyAck();var command=repairCommand(ack);var receipt=run(command);
        assertEquals(CommandOutcome.Status.SUCCEEDED,receipt.status());assertEquals(task("RESOLVE_SOURCE_REQUEST").selector(),receipt.resultFact());
        var before=counts();assertEquals(receipt,run(command));assertEquals(before,counts());
        assertEquals("1",scalar("select count(*) from execution.command_execution_slot where tenant_id=? and command_type='RESTORE_SOURCE_REQUEST_TASK'",seed.tenant()));
        assertEquals("1",scalar("select count(*) from execution.domain_event where tenant_id=? and event_type='SourceRequestTaskRestoredV1'",seed.tenant()));
    }
    @Test void repair_retains_causal_owner_when_other_supervisors_are_authorized() throws Exception {
        var ack=legacyAck();var command=repairCommand(ack);
        actor("HUMAN","LEAD_ROUTING_DECIDE");
        assertEquals(CommandOutcome.Status.SUCCEEDED,run(command).status());
        assertEquals(seed.appointment(),task("RESOLVE_SOURCE_REQUEST").owner());
    }
    @Test void repair_caller_cannot_replace_a_valid_causal_owner_with_another_candidate() throws Exception {
        var ack=legacyAck();var command=repairCommand(ack);var other=actor("HUMAN","LEAD_ROUTING_DECIDE");
        var values=new HashMap<String,Object>((Map<String,Object>)command.payload());values.put("supervisorAppointmentId",other.appointmentId().toString());
        rejectedBefore(payload(command,values),"SUPERVISOR_UNRESOLVED");
    }
    @Test void revoked_origin_can_use_only_a_unique_current_replacement() throws Exception {
        var ack=legacyAck();var command=repairCommand(ack);
        mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_code='LEAD_ROUTING_DECIDE'",seed.tenant());
        var replacement=actor("HUMAN","LEAD_ROUTING_DECIDE");
        var values=new HashMap<String,Object>((Map<String,Object>)command.payload());values.put("supervisorAppointmentId",replacement.appointmentId().toString());
        assertEquals(CommandOutcome.Status.SUCCEEDED,run(payload(command,values)).status());
        assertEquals(replacement.appointmentId(),task("RESOLVE_SOURCE_REQUEST").owner());
    }
    @Test void revoked_origin_with_multiple_replacements_remains_unresolved() throws Exception {
        var ack=legacyAck();var command=repairCommand(ack);
        mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_code='LEAD_ROUTING_DECIDE'",seed.tenant());
        var replacement=actor("HUMAN","LEAD_ROUTING_DECIDE");actor("HUMAN","LEAD_ROUTING_DECIDE");
        var values=new HashMap<String,Object>((Map<String,Object>)command.payload());values.put("supervisorAppointmentId",replacement.appointmentId().toString());
        rejectedBefore(payload(command,values),"SUPERVISOR_UNRESOLVED");
    }
    @Test void named_repair_rollback_and_revoked_supervisor_preserve_the_orphan() throws Exception {
        var ack=legacyAck();var command=repairCommand(ack);
        for(String table:List.of("responsibility.task_occurrence","execution.domain_event","execution.command_receipt","audit.audit_entry"))storageFailureRollsBack(command,table);
        mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_code='LEAD_ROUTING_DECIDE'",seed.tenant());
        try(var c=database.apiConnection()){assertEquals("SUPERVISOR_UNRESOLVED",assertThrows(CommandHandler.Rejected.class,()->runtime.execute(c,command)).code());}assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='RESOLVE_SOURCE_REQUEST'",seed.tenant()));
    }
    @Test void repaired_deadline_is_not_reset_to_the_later_repair_time()throws Exception {
        var ack=legacyAck();try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            var later=tasks.now(x).plusSeconds(86400*14);var next=tasks.restoreHistoricalSourceRequest(x,seed.tenant(),seed.appointment(),ack,ZoneId.of("Asia/Shanghai"),later);
            var metadata=CurrentTaskReader.databaseBacked().read(x,seed.tenant(),next.selector().id());assertTrue(metadata.slaDueAt().isBefore(later));assertEquals(ack,tasks.read(x,seed.tenant(),ack.selector().id()));return null;
        });}
    }
    @Test void named_repair_after_explicit_end_cannot_resurrect_source_work()throws Exception {
        var ack=legacyAck();var command=repairCommand(ack);assertEquals(CommandOutcome.Status.SUCCEEDED,run(command).status());
        run(command(task("RESOLVE_SOURCE_REQUEST"),Map.of("decisionCode","END_LEAD","rationaleSummary","Explicit terminal decision")));
        var next=new CommandEnvelope(command.type(),UUID.randomUUID(),UUID.randomUUID(),command.actor(),command.payload());var rejected=run(next);assertEquals("STALE_TASK",rejected.rejectionCode());
        assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and state in ('OPEN','WAITING')",seed.tenant(),ack.subject().id()));
    }
}
