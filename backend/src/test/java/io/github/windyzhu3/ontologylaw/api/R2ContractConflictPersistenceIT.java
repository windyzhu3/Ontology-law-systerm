package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.contract.*;
import io.github.windyzhu3.ontologylaw.lead.*;
import io.github.windyzhu3.ontologylaw.responsibility.TaskFactory;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import io.github.windyzhu3.ontologylaw.execution.CommandOutcome;
import java.util.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;

/** Real cross-opportunity candidate and Responsibility-owned blocking decision, never a seeded conclusion. */
class R2ContractConflictPersistenceIT extends R2ContractWorkflowPersistenceIT {
    UUID otherOpportunityWithSameParty()throws Exception {
        UUID other=UUID.randomUUID(),contact=UUID.randomUUID();
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            var leads=LeadIngressService.databaseBacked(protection);var now=leads.now(x);
            var lead=leads.capture(x,seed.tenant(),input(true),ContractCanonicalJson.digest(UUID.randomUUID().toString()),now);
            var assignment=leads.assign(x,seed.tenant(),lead,seed.appointment(),"MANUAL_SELECTION",now);
            lead=leads.update(x,seed.tenant(),lead,null,null,null,seed.appointment(),assignment.selector().id(),now);
            var task=tasks.create(x,seed.tenant(),TaskFactory.Type.CONTACT_LEAD,seed.appointment(),lead.selector(),ZoneId.of("Asia/Shanghai"),now);
            sql(x,"insert into lead.lead_contact_result(tenant_id,lead_contact_result_id,lead_id,lead_assignment_id,contact_no,contact_task_id,contact_channel_code,result_code,resulted_at,created_at) values(?,?,?,?,1,?,'PHONE','CONNECTED_VALID',?,?)",seed.tenant(),contact,lead.selector().id(),assignment.selector().id(),task.selector().id(),now.atOffset(ZoneOffset.UTC),now.atOffset(ZoneOffset.UTC));
            sql(x,"insert into opportunity.opportunity(tenant_id,opportunity_id,source_lead_id,source_assignment_id,source_contact_result_id,owner_appointment_id,legal_need_ciphertext,legal_need_digest,created_at) values(?,?,?,?,?,?,decode('01','hex'),?,?)",seed.tenant(),other,lead.selector().id(),assignment.selector().id(),contact,seed.appointment(),ContractCanonicalJson.digest("Synthetic separate opportunity"),now.atOffset(ZoneOffset.UTC));
            tasks.complete(x,seed.tenant(),task,CurrentLeadReader.databaseBacked(protection).contactResult(x,seed.tenant(),contact).selector(),now);
            for(String code:List.of("QUOTE_PREPARE","QUOTE_READ"))grant(x,code);
            return null;
        });}
        var original=opportunity;var sameParty=canonical(confirmationFact);
        @SuppressWarnings("unchecked") var opponent=new LinkedHashMap<String,Object>((Map<String,Object>)((List<?>)sameParty.get("participants")).getFirst());opponent.put("role","OPPONENT");
        sameParty.put("participants",List.of(Map.of("role","CLIENT","newParty",Map.of("kind","ORGANIZATION","name","另一商机独立委托客户","distinctIdentityConfirmed",true)),opponent));sameParty.put("unknownOpponent",false);
        opportunity=new Subject("opportunity.opportunity",other,0L,null);
        try {
            var draft=save(sameParty);var confirmed=execute(command(true,draft.resultFact(),null,null));assertEquals(CommandOutcome.Status.SUCCEEDED,confirmed.status(),confirmed.rejectionCode());
            var quotes=R2QuoteServices.create(cipher);Map<String,Object> context;
            try(var c=database.apiConnection()){context=inTransaction(c,Capability.QUERY,x->quotes.context(x,actor(),other));}
            var payload=new LinkedHashMap<String,Object>();payload.put("opportunityId",other.toString());payload.put("expectedOpportunityRevision",0L);payload.put("responsibilityBasis",context.get("responsibilityBasis"));payload.put("customerConfirmation",context.get("customerConfirmation"));payload.put("expectedDraft",null);payload.put("expectedQuote",null);payload.put("expectedWorkflow",null);
            payload.put("values",Map.of("currency","CNY","scope","另一个商机的真实合成服务范围","lines",List.of(Map.of("description","服务费","amountMinor",10000L,"discount",false)),"paymentTerms","签约后支付","validUntil",Instant.now().plusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString()));
            try(var c=database.apiConnection()){assertEquals("opportunity.quote_revision",inTransaction(c,Capability.COMMAND,x->quotes.execute(x,"FORM_QUOTE",actor(),payload)).type());}
        } finally {opportunity=original;}
        return other;
    }
    @Test void exact_candidate_cannot_clear_and_block_uses_responsibility_decision()throws Exception {
        versionFixture();var version=formThroughWorkflow();otherOpportunityWithSameParty();
        command("REQUEST_CONTRACT_REVIEW",payload(context(),Map.of()));
        var clear=payload(context(),Map.of("decision","CLEAR","reason","应拒绝跳过实际主体命中"));
        assertThrows(ContractWorkflowService.Blocked.class,()->command("RECORD_CONTRACT_REVIEW",clear));
        assertEquals("0",scalar("select count(*) from conflict.conflict_review where tenant_id=?",seed.tenant()));
        command("RECORD_CONTRACT_REVIEW",payload(context(),Map.of("decision","BLOCKED","reason","当前委托人与另一商机登记主体相同，审查后明确阻断")));
        assertEquals("REVIEW_BLOCKED",((Map<?,?>)context().get("workflow")).get("stage"));
        assertEquals("1",scalar("select count(*) from conflict.conflict_finding where tenant_id=?",seed.tenant()));
        assertEquals("1",scalar("select count(*) from responsibility.decision_record where tenant_id=? and decision_contract_code='R2_CONTRACT_CONFLICT_BLOCK_V1' and decision_code='BLOCKED' and decision_subject_type='conflict.conflict_finding'",seed.tenant()));
        assertEquals("BLOCKED",scalar("select decision_code from contract.revision_review_decision where tenant_id=?",seed.tenant()));
        assertEquals("0",scalar("select count(*) from contract.revision_review_binding where tenant_id=? and contract_revision_id=?",seed.tenant(),version.id()));
        assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='PREPARE_CONTRACT' and state='OPEN'",seed.tenant()));
        assertThrows(ContractWorkflowService.Blocked.class,()->command("REQUEST_CONTRACT_APPROVAL",payload(context(),Map.of())));
    }
    @Test void approval_return_requires_new_version_and_never_reuses_prior_partial_approval()throws Exception {
        versionFixture();UUID p=UUID.randomUUID();
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            sql(x,"insert into contract.approval_policy(tenant_id,approval_policy_id,organization_unit_id,policy_code,policy_version,mode,policy_digest,created_at) values(?,?,?,'R2_CONTRACT_APPROVAL_V1',2,'REQUIRE_APPROVAL',decode(repeat('45',32),'hex'),clock_timestamp())",seed.tenant(),p,seed.org());
            for(String code:List.of("COMMERCIAL","LEGAL"))sql(x,"insert into contract.approval_policy_member(tenant_id,approval_policy_member_id,policy_id,requirement_code,appointment_id,created_at) values(?,?,?,?,?,clock_timestamp())",seed.tenant(),UUID.randomUUID(),p,code,seed.appointment());return null;
        });}
        var first=formThroughWorkflow();reviewAndRequestApproval();
        command("RECORD_CONTRACT_DECISION",payload(context(),Map.of("decision","APPROVED","reason","第一项要求通过")));
        var stale=payload(context(),Map.of("decision","APPROVED","reason","旧版第二项"));
        command("RECORD_CONTRACT_DECISION",payload(context(),Map.of("decision","RETURNED","reason","第二项退回，要求重新形成准确版本")));
        assertEquals("RETURNED",((Map<?,?>)context().get("workflow")).get("stage"));
        var second=formThroughWorkflow();assertNotEquals(first.id(),second.id());
        assertEquals("0",scalar("select count(*) from contract.revision_review_binding where tenant_id=? and contract_revision_id=?",seed.tenant(),second.id()));
        assertEquals("0",scalar("select count(*) from contract.revision_approval_decision d join contract.revision_approval_requirement q on q.tenant_id=d.tenant_id and q.revision_approval_requirement_id=d.requirement_id where d.tenant_id=? and q.contract_revision_id=?",seed.tenant(),second.id()));
        assertThrows(ContractWorkflowService.Blocked.class,()->command("RECORD_CONTRACT_DECISION",stale));
        reviewAndRequestApproval();
        command("RECORD_CONTRACT_DECISION",payload(context(),Map.of("decision","APPROVED","reason","新版第一项重新批准")));
        assertEquals("AWAIT_APPROVAL",((Map<?,?>)context().get("workflow")).get("stage"));
        assertEquals("0",scalar("select count(*) from contract.signature_readiness where tenant_id=?",seed.tenant()));
        command("RECORD_CONTRACT_DECISION",payload(context(),Map.of("decision","APPROVED","reason","新版第二项重新批准")));
        assertEquals(second.id().toString(),scalar("select approved_revision_id from contract.contract where tenant_id=? and contract_id=?",seed.tenant(),anchor.id()));
        assertEquals("1",scalar("select count(*) from contract.signature_readiness where tenant_id=? and contract_revision_id=?",seed.tenant(),second.id()));
        assertEquals("0",scalar("select count(*) from contract.signature_readiness where tenant_id=? and contract_revision_id=?",seed.tenant(),first.id()));
    }
    private void reviewAndRequestApproval()throws Exception {
        command("REQUEST_CONTRACT_REVIEW",payload(context(),Map.of()));
        command("RECORD_CONTRACT_REVIEW",payload(context(),Map.of("decision","CLEAR","reason","逐项核对准确主体及完整范围")));
        command("REQUEST_CONTRACT_APPROVAL",payload(context(),Map.of()));
    }
}
