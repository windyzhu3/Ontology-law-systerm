package io.github.windyzhu3.ontologylaw.lead;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;import org.junit.jupiter.api.Test;
import io.github.windyzhu3.ontologylaw.execution.*;
class LeadIngressCompletionIT extends LeadBusinessFixture {
    @Test void missing_contact_completion_confirms_actual_draft_and_combines_automatic_assignment_in_one_cas()throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.AUTOMATIC,true);run(capture("missing",false));var original=task("COMPLETE_LEAD_INGRESS");var before=counts();
        var e=command(original,Map.of("phone","+12025550124","sourceCode","OWNER_CONFIRMED","sourceSummary","Synthetic owner confirmation"));var receipt=run(e);completed(original,receipt);
        delta(before,List.of(0L,1L,0L,0L,1L,1L,1L,1L,1L,1L));assertEquals(1L,receipt.resultFact().revision());assertEquals(receipt.resultFact(),task("CONTACT_LEAD").lead());assertEquals(receipt,run(e));
        emitted(e,receipt,"LeadIngressCompletedV1");
    }
    @Test void completion_manual_and_empty_policy_preserve_capture_bytes_and_fill_one_exact_slot()throws Exception {
        for(boolean manual:List.of(false,true)){
            setup(manual?R1SourcePolicyRegistry.AssignmentMode.MANUAL:R1SourcePolicyRegistry.AssignmentMode.AUTOMATIC,false);run(capture("missing",false));var original=task("COMPLETE_LEAD_INGRESS");
            String immutable=scalar("select jsonb_build_array(captured_name_ciphertext,legal_need_summary_ciphertext,captured_content_digest,source_record_key_digest,captured_at,captured_phone_ciphertext,captured_email_ciphertext)::text from lead.lead where tenant_id=? and lead_id=?",seed.tenant(),original.lead().id());
            var e=command(original,Map.of("email","synthetic@example.com","sourceCode","CUSTOMER_PROVIDED","sourceSummary","Synthetic customer confirmation"));var before=counts();var receipt=run(e);completed(original,receipt);delta(before,List.of(0L,0L,0L,0L,1L,1L,1L,1L,1L,1L));emitted(e,receipt,"LeadIngressCompletedV1");assertEquals(receipt.resultFact(),task(manual?"ASSIGN_LEAD":"RESOLVE_LEAD_ROUTING_GAP").lead());
            assertEquals(immutable,scalar("select jsonb_build_array(captured_name_ciphertext,legal_need_summary_ciphertext,captured_content_digest,source_record_key_digest,captured_at,captured_phone_ciphertext,captured_email_ciphertext)::text from lead.lead where tenant_id=? and lead_id=?",seed.tenant(),original.lead().id()));
            assertEquals("true",scalar("select (ingress_completion_phone_ciphertext is null and ingress_completion_phone_hmac is null and ingress_completion_email_ciphertext is not null and ingress_completion_email_hmac is not null and ingress_completion_digest is not null)::text from lead.lead where tenant_id=? and lead_id=?",seed.tenant(),original.lead().id()));
            String completedAt=scalar("select to_char(ingress_completed_at at time zone 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') from lead.lead where tenant_id=? and lead_id=?",seed.tenant(),original.lead().id());var digest=new TreeMap<String,Object>();digest.put("phone",null);digest.put("email","synthetic@example.com");digest.put("sourceCode","CUSTOMER_PROVIDED");digest.put("sourceSummary","Synthetic customer confirmation");digest.put("completedByAppointmentId",seed.appointment().toString());digest.put("completedAt",completedAt);
            assertEquals(HexFormat.of().formatHex(CanonicalJson.digest(CanonicalJson.encode(digest))),scalar("select encode(ingress_completion_digest,'hex') from lead.lead where tenant_id=? and lead_id=?",seed.tenant(),original.lead().id()));
            byte[] protectedEmail=HexFormat.of().parseHex(scalar("select encode(ingress_completion_email_ciphertext,'hex') from lead.lead where tenant_id=? and lead_id=?",seed.tenant(),original.lead().id()));assertEquals("synthetic@example.com",LeadProtectionTest.protection().decrypt(seed.tenant(),LeadProtection.Field.INGRESS_EMAIL,protectedEmail));
            var completedSnapshot=businessSnapshot();var newKey=new CommandEnvelope(e.type(),UUID.randomUUID(),UUID.randomUUID(),e.actor(),e.payload(),e.taskPrecondition());terminal(newKey,"TASK_ALREADY_COMPLETED");assertEquals(completedSnapshot,businessSnapshot());
        }
    }
    @Test void exact_draft_and_opaque_task_preconditions_have_correct_slot_boundaries()throws Exception {
        for(String fault:List.of("NO_TAG","BAD_TAG","MISSING_DRAFT","BAD_VALUES","STALE_TAG","STALE_DRAFT","WRONG_DIGEST","WRONG_PAYLOAD","STALE_LEAD")){
            setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL,false);run(capture("preconditions",false));var task=task("COMPLETE_LEAD_INGRESS");var e=command(task,Map.of("phone","+12025550124","sourceCode","OWNER_CONFIRMED","sourceSummary","Synthetic"));var values=new TreeMap<String,Object>((Map<String,Object>)e.payload());
            switch(fault){
                case "NO_TAG" -> e=new CommandEnvelope(e.type(),e.commandId(),e.correlationId(),e.actor(),e.payload());
                case "BAD_TAG" -> e=new CommandEnvelope(e.type(),e.commandId(),e.correlationId(),e.actor(),e.payload(),new CommandEnvelope.TaskPrecondition(task.selector().id(),"W/\"weak\""));
                case "STALE_TAG" -> e=new CommandEnvelope(e.type(),e.commandId(),e.correlationId(),e.actor(),e.payload(),new CommandEnvelope.TaskPrecondition(task.selector().id(),"\"task."+"A".repeat(43)+"\""));
                case "MISSING_DRAFT" -> {values.put("draftId",UUID.randomUUID().toString());e=payload(e,values);}
                case "BAD_VALUES" -> {values.remove("phone");e=payload(e,values);}
                case "STALE_DRAFT" -> {values.put("expectedDraftRevision",1L);e=payload(e,values);}
                case "WRONG_DIGEST" -> {values.put("draftDigest","A".repeat(43));e=payload(e,values);}
                case "WRONG_PAYLOAD" -> {values.put("phone","+12025550999");e=payload(e,values);}
                case "STALE_LEAD" -> mutate("update lead.lead set disposition_code='KEEP_SEPARATE',revision=revision+1 where tenant_id=? and lead_id=?",seed.tenant(),task.lead().id());
            }
            if(Set.of("NO_TAG","BAD_TAG","MISSING_DRAFT","BAD_VALUES").contains(fault))rejectedBefore(e,fault.equals("NO_TAG")?"TASK_PRECONDITION_REQUIRED":fault.equals("MISSING_DRAFT")?"NOT_FOUND":"VALIDATION_FAILED");
            else terminal(e,fault.equals("STALE_TAG")?"STALE_TASK":fault.equals("STALE_LEAD")?"STALE_SUBJECT":fault.equals("STALE_DRAFT")?"STALE_DRAFT":"DRAFT_DIGEST_MISMATCH");
            assertEquals("DRAFT:0",scalar("select state||':'||revision from responsibility.action_draft where tenant_id=? and task_occurrence_id=?",seed.tenant(),task.selector().id()));
        }
    }
    @Test void fact_event_outbox_receipt_and_audit_failures_rollback_completion_and_draft()throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.AUTOMATIC,true);run(capture("rollback",false));var task=task("COMPLETE_LEAD_INGRESS");var e=command(task,Map.of("phone","+12025550124","sourceCode","OWNER_CONFIRMED","sourceSummary","Synthetic"));
        for(String table:List.of("lead.lead","responsibility.task_occurrence","execution.domain_event","execution.domain_event_outbox","execution.command_receipt","audit.audit_entry"))storageFailureRollsBack(e,table);
        completed(task,run(e));
    }
}
