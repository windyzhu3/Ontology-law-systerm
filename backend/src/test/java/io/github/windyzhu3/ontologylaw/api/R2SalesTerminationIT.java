package io.github.windyzhu3.ontologylaw.api;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.contract.ContractWorkflowService;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Contract handling can stop without erasing a signature, decision or original responsibility. */
class R2SalesTerminationIT extends R2ManualSignatureWorkflowIT {
    @Override Map<String,Object> payload(Map<String,Object> context,Map<String,Object> values){var exact=new LinkedHashMap<String,Object>(values);if(context.get("termination") instanceof Map<?,?> t)exact.putIfAbsent("expectedTermination",t.get("selector"));return super.payload(context,exact);}
    Actor supervisor()throws Exception {
        UUID principal=UUID.randomUUID(),appointment=UUID.randomUUID();mutate("insert into identity.principal(tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) values(?,?,'HUMAN','FIXTURE',?,'合成主管','ACTIVE',clock_timestamp())",seed.tenant(),principal,io.github.windyzhu3.ontologylaw.contract.ContractCanonicalJson.digest(principal.toString()));
        mutate("insert into identity.appointment(tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values(?,?,?,?,'SUPERVISOR',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),appointment,principal,seed.org());terminationReviewers.add(appointment);return new Actor(seed.tenant(),principal,appointment,null,null);
    }
    void decide(Actor supervisor,String decision)throws Exception {var body=dispositionPayload(Map.of("decision",decision));try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->service().execute(x,"RECORD_CONTRACT_TERMINATION_REVIEW",supervisor,body));}}
    Map<String,Object> dispositionPayload(Map<String,Object> additions)throws Exception {
        var ctx=context();var values=new LinkedHashMap<String,Object>();values.put("summary","客户要求停止本次办理，保留全部历史依据。");values.put("humanConfirmed",true);
        var termination=(Map<?,?>)ctx.get("termination");values.put("expectedTermination",termination==null?null:termination.get("selector"));
        var signature=(Map<?,?>)ctx.get("signature");values.put("expectedSignatureWorkflow",signature==null?null:((Map<?,?>)signature.get("workflow")).get("selector"));values.putAll(additions);if(additions.containsKey("decision"))values.put("expectedIndependentBasis",termination.get("independentBasis"));return payload(ctx,values);
    }
    void unsignedStage(String target)throws Exception {
        if(Set.of("DIRECT_REVIEW","DIRECT_RETURNED","PREPARE").contains(target)){initialize();command("REQUEST_CONTRACT_PREPARATION",payload(context(),Map.of("commercial",terms.canonical(),"reason","准确直接准备申请")));if(target.equals("DIRECT_RETURNED"))command("RECORD_CONTRACT_PREPARATION_DECISION",payload(context(),Map.of("decision","RETURNED","reason","补充授权依据")));if(target.equals("PREPARE"))command("RECORD_CONTRACT_PREPARATION_DECISION",payload(context(),Map.of("decision","APPROVED","reason","准确授权后准备合同")));return;}
        if(Set.of("ARRANGE","COLLECT").contains(target)){start();if(target.equals("COLLECT"))arrange();return;}
        versionFixture();formThroughWorkflow();if(target.equals("SUBMIT_REVIEW"))return;
        command("REQUEST_CONTRACT_REVIEW",payload(context(),Map.of()));if(target.equals("AWAIT_REVIEW"))return;
        if(target.equals("REVIEW_SUPPLEMENT")){command("RECORD_CONTRACT_REVIEW",payload(context(),Map.of("decision","NEED_INFO","reason","补充审查资料")));return;}command("RECORD_CONTRACT_REVIEW",payload(context(),Map.of("decision","CLEAR","reason","主体及准确范围核对完成")));if(target.equals("SUBMIT_APPROVAL"))return;
        command("REQUEST_CONTRACT_APPROVAL",payload(context(),Map.of()));
        if(target.equals("RETURNED"))command("RECORD_CONTRACT_DECISION",payload(context(),Map.of("decision","RETURNED","reason","完善合同条款")));
    }
    @ParameterizedTest @ValueSource(strings={"DIRECT_REVIEW","DIRECT_RETURNED","PREPARE","SUBMIT_REVIEW","AWAIT_REVIEW","REVIEW_SUPPLEMENT","SUBMIT_APPROVAL","AWAIT_APPROVAL","RETURNED","ARRANGE","COLLECT"})
    void termination_unsigned_preserves_history_and_ends_related_tasks(String target)throws Exception {
        unsignedStage(target);var done=scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and state='DONE'",seed.tenant(),opportunity.id());var versions=scalar("select count(*) from contract.contract_revision where tenant_id=?",seed.tenant());
        assertTrue(((List<?>)context().get("allowedActions")).contains("END_CONTRACT_NEGOTIATION"));
        var result=command("END_CONTRACT_NEGOTIATION",dispositionPayload(Map.of()));assertEquals("contract.negotiation_disposition",result.type());
        assertEquals("STOPPED",((Map<?,?>)context().get("termination")).get("state"));assertEquals(List.of(),context().get("allowedActions"));
        assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and state in ('OPEN','WAITING')",seed.tenant(),opportunity.id()));
        assertEquals(done,scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and state='DONE'",seed.tenant(),opportunity.id()));assertEquals(versions,scalar("select count(*) from contract.contract_revision where tenant_id=?",seed.tenant()));
        var worker=service("CONTRACT_TASK_RECOVER");try(var c=database.apiConnection()){assertTrue(inTransaction(c,Capability.QUERY,x->service().recoveryPage(x,worker,100,null).candidates()).isEmpty());}
    }
    @ParameterizedTest @ValueSource(strings={"AWAIT_VERIFICATION","PARTIAL","ARCHIVE","SIGNATURE_COMPLETE"})
    void termination_signed_evidence_requires_supervisor_and_blocks_stale_signature(String target)throws Exception {
        start();arrange();submit();if(!target.equals("AWAIT_VERIFICATION"))command("RECORD_CONTRACT_SIGNATURE_VERIFICATION",signaturePayload(verification("VERIFIED")));
        if(Set.of("ARCHIVE","SIGNATURE_COMPLETE").contains(target)){command("SUBMIT_CONTRACT_SIGNATURE",signaturePayload(Map.of("slotNumber",2,"materialVersionId",material.toString(),"materialSha256",bodySha,"authorityMaterialVersionId",material.toString(),"authorityMaterialSha256",bodySha,"signerName","合成律所代表","signedAt","2026-01-01T00:00:00Z")));command("RECORD_CONTRACT_SIGNATURE_VERIFICATION",signaturePayload(verification("VERIFIED")));}
        if(target.equals("SIGNATURE_COMPLETE"))command("ARCHIVE_CONTRACT_SIGNATURE",signaturePayload(Map.of("materialVersionId",material.toString(),"materialSha256",bodySha,"reason","完整归档核对通过","archiveComplete",true)));
        assertEquals(target,stage());var signatures=scalar("select count(*) from contract.contract_signature where tenant_id=?",seed.tenant());
        assertFalse(((List<?>)context().get("allowedActions")).contains("END_CONTRACT_NEGOTIATION"));assertTrue(((List<?>)context().get("allowedActions")).contains("REQUEST_CONTRACT_TERMINATION_REVIEW"));
        var oldSignature=signaturePayload(verification("VERIFIED"));assertThrows(ContractWorkflowService.Blocked.class,()->command("END_CONTRACT_NEGOTIATION",dispositionPayload(Map.of())));
        command("REQUEST_CONTRACT_TERMINATION_REVIEW",dispositionPayload(Map.of()));assertEquals("REVIEW_REQUIRED",((Map<?,?>)context().get("termination")).get("state"));
        assertThrows(ContractWorkflowService.Blocked.class,()->command("RECORD_CONTRACT_SIGNATURE_VERIFICATION",oldSignature));assertEquals(signatures,scalar("select count(*) from contract.contract_signature where tenant_id=?",seed.tenant()));
    }
    @Test void termination_unsigned_rolls_back_with_later_failure()throws Exception {
        unsignedStage("AWAIT_APPROVAL");var body=dispositionPayload(Map.of());try(var c=database.apiConnection()){assertThrows(IllegalStateException.class,()->inTransaction(c,Capability.COMMAND,x->{service().execute(x,"END_CONTRACT_NEGOTIATION",actor(),body);throw new IllegalStateException("audit failed");}));}
        assertNull(context().get("termination"));assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and business_purpose_code='APPROVE_CONTRACT' and state='OPEN'",seed.tenant(),opportunity.id()));
    }
    @Test void termination_supervisor_continue_creates_a_new_task_with_original_deadline()throws Exception {
        start();arrange();submit();var supervisor=supervisor();var original=((Map<?,?>)signature().get("workflow")).get("task");String originalId=(String)((Map<?,?>)original).get("id");var due=((Map<?,?>)signature().get("workflow")).get("dueAt");var oldCommand=signaturePayload(verification("VERIFIED"));
        command("REQUEST_CONTRACT_TERMINATION_REVIEW",dispositionPayload(Map.of()));assertThrows(ContractWorkflowService.Blocked.class,()->decide(actor(),"CONTINUE"));decide(supervisor,"CONTINUE");
        assertEquals("CONTINUED",((Map<?,?>)context().get("termination")).get("state"));assertEquals(due,((Map<?,?>)signature().get("workflow")).get("dueAt"));var resumed=(Map<?,?>)((Map<?,?>)signature().get("workflow")).get("task");assertNotEquals(originalId,resumed.get("id"));assertEquals("CANCELLED",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),UUID.fromString(originalId)));
        assertThrows(ContractWorkflowService.Blocked.class,()->command("RECORD_CONTRACT_SIGNATURE_VERIFICATION",oldCommand));command("RECORD_CONTRACT_SIGNATURE_VERIFICATION",signaturePayload(verification("VERIFIED")));assertEquals("PARTIAL",stage());
    }
    @Test void termination_supervisor_stop_preserves_verified_signature_and_completes_review_only()throws Exception {
        start();arrange();submit();command("RECORD_CONTRACT_SIGNATURE_VERIFICATION",signaturePayload(verification("VERIFIED")));var supervisor=supervisor();String signatureCount=scalar("select count(*) from contract.contract_signature where tenant_id=?",seed.tenant());command("REQUEST_CONTRACT_TERMINATION_REVIEW",dispositionPayload(Map.of()));decide(supervisor,"STOP");
        assertEquals("STOPPED",((Map<?,?>)context().get("termination")).get("state"));assertEquals(List.of(),context().get("allowedActions"));assertEquals(signatureCount,scalar("select count(*) from contract.contract_signature where tenant_id=?",seed.tenant()));assertEquals("0",scalar("select count(*) from contract.contract_termination where tenant_id=?",seed.tenant()));assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and state in ('OPEN','WAITING')",seed.tenant(),opportunity.id()));assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='REVIEW_CONTRACT_TERMINATION' and state='DONE'",seed.tenant()));
    }
    @Test void termination_unassigned_review_recovers_once_after_qualified_supervisor_exists()throws Exception {
        start();arrange();submit();command("REQUEST_CONTRACT_TERMINATION_REVIEW",dispositionPayload(Map.of()));assertNull(((Map<?,?>)context().get("termination")).get("task"));var due=((Map<?,?>)context().get("termination")).get("dueAt");var worker=service("CONTRACT_TASK_RECOVER");
        try(var c=database.apiConnection()){assertTrue(inTransaction(c,Capability.QUERY,x->service().recoveryPage(x,worker,100,null).candidates()).isEmpty());}
        var supervisor=supervisor();Map<String,Object> body;
        try(var c=database.apiConnection()){body=inTransaction(c,Capability.QUERY,x->{var candidates=service().recoveryPage(x,worker,100,null).candidates();assertEquals(1,candidates.size());var candidate=candidates.getFirst();return Map.of("opportunityId",opportunity.id().toString(),"expectedOpportunityRevision",opportunity.revision(),"responsibilityBasis",Map.of("id",candidate.basis().id().toString(),"revision",candidate.basis().revision()),"sourceKind","TERMINATION_REVIEW","source",Map.of("id",candidate.source().id().toString(),"revision",0L),"expectedWorkflow",Map.of("id",candidate.workflow().id().toString(),"revision",0L));});}
        recover(worker,body);assertThrows(ContractWorkflowService.Blocked.class,()->recover(worker,body));var t=(Map<?,?>)context().get("termination");assertEquals(supervisor.appointmentId().toString(),t.get("ownerAppointmentId"));assertEquals(due,t.get("dueAt"));assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='REVIEW_CONTRACT_TERMINATION' and state='OPEN'",seed.tenant()));
    }
    @Test void termination_continue_waits_for_original_owner_authority_instead_of_assigning_invalid_work()throws Exception {
        start();arrange();submit();var supervisor=supervisor();command("REQUEST_CONTRACT_TERMINATION_REVIEW",dispositionPayload(Map.of()));unavailableAuthorities.add("CONTRACT_SIGNATURE_VERIFY");
        assertEquals(false,((Map<?,?>)context().get("termination")).get("resumeAvailable"));assertThrows(ContractWorkflowService.Blocked.class,()->decide(supervisor,"CONTINUE"));assertEquals("REVIEW_REQUIRED",((Map<?,?>)context().get("termination")).get("state"));
        unavailableAuthorities.remove("CONTRACT_SIGNATURE_VERIFY");decide(supervisor,"CONTINUE");assertEquals("CONTINUED",((Map<?,?>)context().get("termination")).get("state"));
    }
    @Test void termination_recovery_releases_withdrawn_supervisor_and_preserves_deadline()throws Exception {
        start();arrange();submit();var prior=supervisor();command("REQUEST_CONTRACT_TERMINATION_REVIEW",dispositionPayload(Map.of()));var before=(Map<?,?>)context().get("termination");terminationReviewers.clear();var worker=service("CONTRACT_TASK_RECOVER");
        Map<String,Object> body;try(var c=database.apiConnection()){body=inTransaction(c,Capability.QUERY,x->{var candidate=service().recoveryPage(x,worker,100,null).candidates().getFirst();return Map.of("opportunityId",opportunity.id().toString(),"expectedOpportunityRevision",opportunity.revision(),"responsibilityBasis",Map.of("id",candidate.basis().id().toString(),"revision",candidate.basis().revision()),"sourceKind","TERMINATION_REVIEW","source",Map.of("id",candidate.source().id().toString(),"revision",0L),"expectedWorkflow",Map.of("id",candidate.workflow().id().toString(),"revision",0L));});}recover(worker,body);
        assertNull(((Map<?,?>)context().get("termination")).get("task"));assertEquals(before.get("dueAt"),((Map<?,?>)context().get("termination")).get("dueAt"));assertThrows(ContractWorkflowService.Blocked.class,()->decide(prior,"STOP"));assertEquals("CANCELLED",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),UUID.fromString((String)((Map<?,?>)before.get("task")).get("id"))));
    }

    @Test void termination_database_rejects_recreated_sales_task_after_stop()throws Exception {
        unsignedStage("AWAIT_APPROVAL");command("END_CONTRACT_NEGOTIATION",dispositionPayload(Map.of()));
        try(var c=database.apiConnection()){var error=assertThrows(Exception.class,()->inTransaction(c,Capability.COMMAND,x->tasks.create(x,seed.tenant(),io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.Type.APPROVE_CONTRACT,seed.appointment(),opportunity,java.time.ZoneId.of("Asia/Shanghai"),tasks.now(x))));Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();assertInstanceOf(java.sql.SQLException.class,cause);assertEquals("23514",((java.sql.SQLException)cause).getSQLState());assertTrue(cause.getMessage().contains("negotiation is paused or stopped"));}
        assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and state in ('OPEN','WAITING')",seed.tenant(),opportunity.id()));
    }

    void paymentFact(int number)throws Exception {
        mutate("insert into contract.payment_confirmation(tenant_id,payment_confirmation_id,contract_id,contract_revision_id,confirmation_no,confirmation_type,amount_minor,currency_code,provider_account_code,provider_transaction_key_hmac,evidence_submission_id,attribution_digest,effective_at,confirmed_at,recorded_by_appointment_id) select tenant_id,uuidv7(),?,?,?,'RECEIPT',100,'CNY','F06_SYNTHETIC',sha256(convert_to(cast(? as text),'UTF8')),evidence_submission_id,decode(repeat('31',32),'hex'),clock_timestamp(),clock_timestamp(),? from opportunity.material_version where tenant_id=? and material_version_id=?",anchor.id(),UUID.fromString((String)((Map<?,?>)((Map<?,?>)context().get("contract")).get("currentRevision")).get("id")),number,UUID.randomUUID().toString(),seed.appointment(),seed.tenant(),material);
    }
    @Test void termination_keeps_independent_payment_facts_and_discloses_their_existence()throws Exception {
        start();paymentFact(1);assertTrue(((List<?>)context().get("allowedActions")).contains("REQUEST_CONTRACT_TERMINATION_REVIEW"));var reviewer=supervisor();
        command("REQUEST_CONTRACT_TERMINATION_REVIEW",dispositionPayload(Map.of()));var independent=(Map<?,?>)((Map<?,?>)context().get("termination")).get("independentState");assertNotNull(independent);assertEquals(true,independent.get("paymentRecorded"));assertEquals(false,independent.get("executionRecorded"));assertEquals(false,independent.get("transferRecorded"));
        decide(reviewer,"STOP");paymentFact(2);assertEquals("2",scalar("select count(*) from contract.payment_confirmation where tenant_id=?",seed.tenant()));assertEquals("0",scalar("select count(*) from contract.contract_termination where tenant_id=?",seed.tenant()));
    }
    @Test void termination_supervisor_must_reread_when_independent_facts_change()throws Exception {
        start();arrange();submit();var reviewer=supervisor();command("REQUEST_CONTRACT_TERMINATION_REVIEW",dispositionPayload(Map.of()));var stale=dispositionPayload(Map.of("decision","STOP"));paymentFact(1);
        try(var c=database.apiConnection()){assertEquals("STALE_SUBJECT",assertThrows(ContractWorkflowService.Blocked.class,()->inTransaction(c,Capability.COMMAND,x->service().execute(x,"RECORD_CONTRACT_TERMINATION_REVIEW",reviewer,stale))).code());}
        assertEquals("REVIEW_REQUIRED",((Map<?,?>)context().get("termination")).get("state"));decide(reviewer,"STOP");
    }

}
