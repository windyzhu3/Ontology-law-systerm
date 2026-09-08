package io.github.windyzhu3.ontologylaw.lead;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;import org.junit.jupiter.api.Test;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
class LeadRoutingDispositionIT extends LeadBusinessFixture {
    @Test void empty_candidates_schedule_one_waiting_routing_successor_and_exact_wait_receipt()throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.AUTOMATIC,false);run(capture("empty",true));var original=task("RESOLVE_LEAD_ROUTING_GAP");var before=counts();
        var e=command(original,Map.of("decisionCode","SCHEDULE_ROUTING_REVIEW","rationaleSummary","Synthetic scheduling reason"));var receipt=run(e);completed(original,receipt);
        delta(before,List.of(0L,0L,1L,1L,1L,1L,1L,1L,1L,1L));var successor=task("RESOLVE_LEAD_ROUTING_GAP");assertEquals("WAITING",successor.state());assertEquals(1L,successor.selector().revision());assertEquals(original.owner(),successor.owner());assertEquals(receipt,run(e));
        emitted(e,receipt,"LeadRoutingDispositionRecordedV1");
        assertEquals(original.lead(),successor.lead());
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
            var wait=R1EventReaders.databaseBacked().latestWait(x,seed.tenant(),successor.selector().id());assertEquals(1L,wait.taskRevision());assertEquals("R1_ROUTING_REVIEW_WAIT_V1",wait.profile());assertEquals(R1BusinessTime.nextWindow(successor.createdAt(),java.time.ZoneId.of("Asia/Shanghai")),wait.resumeDue());
            return null;
        });}
    }
    @Test void retry_empty_does_not_recurse_or_invent_assignment()throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.AUTOMATIC,false);run(capture("retry",true));var original=task("RESOLVE_LEAD_ROUTING_GAP");var before=counts();
        var receipt=run(command(original,Map.of("decisionCode","RETRY_ASSIGNMENT_NOW","rationaleSummary","Synthetic retry reason")));completed(original,receipt);
        delta(before,List.of(0L,0L,1L,0L,1L,1L,1L,1L,1L,1L));assertEquals("OPEN",task("RESOLVE_LEAD_ROUTING_GAP").state());
    }
    @Test void retry_after_candidate_becomes_available_assigns_once_and_creates_contact()throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.AUTOMATIC,false);run(capture("retry-new",true));var original=task("RESOLVE_LEAD_ROUTING_GAP");try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"SALES_CONTACT_OWNER");return null;});}
        var e=command(original,Map.of("decisionCode","RETRY_ASSIGNMENT_NOW","rationaleSummary","Synthetic available owner"));var before=counts();var receipt=run(e);completed(original,receipt);delta(before,List.of(0L,1L,1L,0L,1L,1L,1L,1L,1L,1L));emitted(e,receipt,"LeadRoutingDispositionRecordedV1");assertEquals(1L,task("CONTACT_LEAD").lead().revision());
    }
    @Test void source_stop_request_and_ack_have_exact_causal_facts_and_no_source_mutation()throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.AUTOMATIC,false);run(capture("stop",true));var original=task("RESOLVE_LEAD_ROUTING_GAP");var stop=command(original,Map.of("decisionCode","REQUEST_SOURCE_INTAKE_STOP","rationaleSummary","Synthetic stop request"));var before=counts();
        var stopped=run(stop);completed(original,stopped);delta(before,List.of(0L,0L,1L,0L,1L,1L,1L,1L,1L,1L));emitted(stop,stopped,"SourceIntakeStopRequestedV1");
        var ack=task("ACK_SOURCE_INTAKE_STOP_REQUEST");assertEquals(original.lead(),ack.lead());assertEquals(seed.appointment(),ack.owner());var e=command(ack,Map.of("causalDecisionId",stopped.resultFact().id().toString(),"causalDecisionHash",stopped.resultFact().hash(),"rationaleSummary","Synthetic acknowledgement"));before=counts();var receipt=run(e);completed(ack,receipt);delta(before,List.of(0L,0L,1L,0L,0L,1L,1L,1L,1L,1L));emitted(e,receipt,"SourceIntakeStopRequestAcknowledgedV1");assertEquals(receipt,run(e));assertTrue(sources.contains("FIXTURE"));
    }
    @Test void missing_and_ambiguous_stop_owner_leave_only_terminal_receipt_and_audit()throws Exception {
        for(boolean multiple:List.of(false,true)){
            setup(R1SourcePolicyRegistry.AssignmentMode.AUTOMATIC,false);run(capture("stop-owner",true));var original=task("RESOLVE_LEAD_ROUTING_GAP");var e=command(original,Map.of("decisionCode","REQUEST_SOURCE_INTAKE_STOP","rationaleSummary","Synthetic stop reason"));
            if(!multiple)mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='TEST',revision=revision+1 where tenant_id=? and authority_code='SOURCE_INTAKE_REQUEST_ACK'",seed.tenant());
            else {UUID app=UUID.randomUUID();mutate("insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'INTAKE',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),app,seed.principal(),seed.org());mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,'SOURCE_INTAKE_REQUEST_ACK',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),app,seed.appointment(),seed.org());}
            var before=counts();var receipt=run(e);assertEquals("SOURCE_INTAKE_OWNER_UNRESOLVED",receipt.rejectionCode());delta(before,List.of(0L,0L,0L,0L,0L,1L,1L,0L,0L,1L));assertEquals(receipt,run(e));assertEquals("OPEN",task("RESOLVE_LEAD_ROUTING_GAP").state());
        }
    }
    @Test void ack_rejects_wrong_id_hash_and_foreign_causal_selector_without_completing_draft()throws Exception {
        for(String fault:List.of("ID","HASH","FOREIGN")){
            setup(R1SourcePolicyRegistry.AssignmentMode.AUTOMATIC,false);run(capture("stop",true));var stop=run(command(task("RESOLVE_LEAD_ROUTING_GAP"),Map.of("decisionCode","REQUEST_SOURCE_INTAKE_STOP","rationaleSummary","Synthetic")));var ack=task("ACK_SOURCE_INTAKE_STOP_REQUEST");
            String id=stop.resultFact().id().toString(),hash=stop.resultFact().hash();if(fault.equals("ID"))id=UUID.randomUUID().toString();if(fault.equals("HASH"))hash="A".repeat(43);
            if(fault.equals("FOREIGN")){var savedSeed=seed;var savedRuntime=runtime;setup(R1SourcePolicyRegistry.AssignmentMode.AUTOMATIC,false);run(capture("foreign",true));var foreign=run(command(task("RESOLVE_LEAD_ROUTING_GAP"),Map.of("decisionCode","REQUEST_SOURCE_INTAKE_STOP","rationaleSummary","Synthetic")));id=foreign.resultFact().id().toString();hash=foreign.resultFact().hash();seed=savedSeed;runtime=savedRuntime;}
            terminal(command(ack,Map.of("causalDecisionId",id,"causalDecisionHash",hash,"rationaleSummary","Synthetic")),"STALE_SUBJECT");assertEquals("OPEN",task("ACK_SOURCE_INTAKE_STOP_REQUEST").state());
        }
    }
    @Test void routing_and_ack_storage_failures_rollback_every_branch_atomically()throws Exception {
        for(String branch:List.of("SCHEDULE_ROUTING_REVIEW","RETRY_ASSIGNMENT_NOW","RETRY_WITH_OWNER","REQUEST_SOURCE_INTAKE_STOP","ACK")){
            setup(R1SourcePolicyRegistry.AssignmentMode.AUTOMATIC,false);run(capture("rollback",true));var original=task("RESOLVE_LEAD_ROUTING_GAP");CommandEnvelope e;
            if(branch.equals("ACK")){var stop=run(command(original,Map.of("decisionCode","REQUEST_SOURCE_INTAKE_STOP","rationaleSummary","Synthetic")));original=task("ACK_SOURCE_INTAKE_STOP_REQUEST");e=command(original,Map.of("causalDecisionId",stop.resultFact().id().toString(),"causalDecisionHash",stop.resultFact().hash(),"rationaleSummary","Synthetic"));}
            else {if(branch.equals("RETRY_WITH_OWNER"))try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"SALES_CONTACT_OWNER");return null;});}e=command(original,Map.of("decisionCode",branch.equals("RETRY_WITH_OWNER")?"RETRY_ASSIGNMENT_NOW":branch,"rationaleSummary","Synthetic"));}
            for(String table:List.of("responsibility.task_occurrence","execution.domain_event","execution.domain_event_outbox","execution.command_receipt","audit.audit_entry"))storageFailureRollsBack(e,table);
            completed(original,run(e));
        }
    }
}
