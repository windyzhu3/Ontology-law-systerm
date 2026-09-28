package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.contract.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;

/** Exact independent responsibility loss returns work without inventing a human decision. */
class R2ContractAuthorityRecoveryIT extends R2ContractWorkflowPersistenceIT {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"SUBMIT_REVIEW","SUBMIT_APPROVAL","REVIEW_SUPPLEMENT"})
    void sales_stage_recovery_retains_the_exact_purpose_and_original_deadline(String stage)throws Exception {
        versionFixture();formThroughWorkflow();
        if(!stage.equals("SUBMIT_REVIEW")){
            command("REQUEST_CONTRACT_REVIEW",payload(context(),Map.of()));
            command("RECORD_CONTRACT_REVIEW",payload(context(),Map.of("decision",stage.equals("REVIEW_SUPPLEMENT")?"NEED_INFO":"CLEAR","reason","合成审查结果")));
        }
        assertEquals(stage,((Map<?,?>)context().get("workflow")).get("stage"));
        UUID original=UUID.fromString((String)((Map<?,?>)((Map<?,?>)context().get("workflow")).get("task")).get("id"));
        String purpose=scalar("select business_purpose_code from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),original);
        String due=scalar("select original_sla_due_at::text from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),original);
        var worker=service("CONTRACT_TASK_RECOVER");unavailableAuthorities.add("CONTRACT_PREPARE");reconcile(worker,authorityRecovery(worker));
        assertEquals("OWNER_EXCEPTION",((Map<?,?>)context().get("workflow")).get("stage"));
        assertNull(((Map<?,?>)context().get("workflow")).get("task"));
        unavailableAuthorities.remove("CONTRACT_PREPARE");reconcile(worker,authorityRecovery(worker));
        assertEquals(stage,((Map<?,?>)context().get("workflow")).get("stage"));
        UUID resumed=UUID.fromString((String)((Map<?,?>)((Map<?,?>)context().get("workflow")).get("task")).get("id"));
        assertNotEquals(original,resumed);
        assertEquals(purpose,scalar("select business_purpose_code from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),resumed));
        assertEquals(due,scalar("select original_sla_due_at::text from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),resumed));
        try(var c=database.apiConnection()){assertTrue(inTransaction(c,Capability.QUERY,x->service().recoveryPage(x,worker,100,null)).candidates().isEmpty());}
    }
    Map<String,Object> authorityRecovery(Actor worker)throws Exception {
        try(var c=database.apiConnection()){return inTransaction(c,Capability.QUERY,x->{
            var page=service().recoveryPage(x,worker,100,null);assertEquals(1,page.candidates().size());var candidate=page.candidates().getFirst();
            assertEquals("contract.preparation_workflow",candidate.source().type());
            return Map.of("opportunityId",opportunity.id().toString(),"expectedOpportunityRevision",opportunity.revision(),"responsibilityBasis",Map.of("id",candidate.basis().id().toString(),"revision",candidate.basis().revision()),"sourceKind","AUTHORITY_RETURN","source",Map.of("id",candidate.source().id().toString(),"revision",0L),"expectedWorkflow",Map.of("id",candidate.workflow().id().toString(),"revision",0L));
        });}
    }
    void reconcile(Actor worker,Map<String,Object> payload)throws Exception {
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->service().reconcile(x,worker,payload));}
    }
    @Test void lost_direct_decider_returns_without_fabricating_decision_and_can_request_again()throws Exception {
        initialize();command("REQUEST_CONTRACT_PREPARATION",payload(context(),Map.of("commercial",terms.canonical(),"reason","申请准确范围")));
        var worker=service("CONTRACT_TASK_RECOVER");unavailableAuthorities.add("CONTRACT_PREPARATION_DECIDE");var recovery=authorityRecovery(worker);reconcile(worker,recovery);
        assertEquals("DIRECT_RETURNED",((Map<?,?>)context().get("workflow")).get("stage"));
        assertEquals("0",scalar("select count(*) from contract.preparation_decision where tenant_id=?",seed.tenant()));
        assertThrows(ContractWorkflowService.Blocked.class,()->reconcile(worker,recovery));unavailableAuthorities.clear();
        command("REQUEST_CONTRACT_PREPARATION",payload(context(),Map.of("commercial",terms.canonical(),"reason","重新申请准确范围")));
        assertEquals("2",scalar("select count(*) from contract.preparation_request where tenant_id=?",seed.tenant()));
    }
    @Test void lost_reviewer_and_sales_wait_for_real_owner_requalification()throws Exception {
        versionFixture();formThroughWorkflow();command("REQUEST_CONTRACT_REVIEW",payload(context(),Map.of()));
        var worker=service("CONTRACT_TASK_RECOVER");unavailableAuthorities.addAll(Set.of("CONTRACT_REVIEW","CONTRACT_PREPARE"));reconcile(worker,authorityRecovery(worker));
        assertEquals("OWNER_EXCEPTION",((Map<?,?>)context().get("workflow")).get("stage"));
        assertNull(((Map<?,?>)context().get("workflow")).get("task"));
        try(var c=database.apiConnection()){assertTrue(inTransaction(c,Capability.QUERY,x->service().recoveryPage(x,worker,100,null)).candidates().isEmpty());}
        unavailableAuthorities.remove("CONTRACT_PREPARE");reconcile(worker,authorityRecovery(worker));
        assertEquals("RETURNED",((Map<?,?>)context().get("workflow")).get("stage"));
        assertEquals("0",scalar("select count(*) from contract.revision_review_decision where tenant_id=?",seed.tenant()));
    }
    @Test void lost_approver_returns_version_for_revision_without_signature_readiness()throws Exception {
        versionFixture();formThroughWorkflow();command("REQUEST_CONTRACT_REVIEW",payload(context(),Map.of()));
        command("RECORD_CONTRACT_REVIEW",payload(context(),Map.of("decision","CLEAR","reason","已核对完整范围")));command("REQUEST_CONTRACT_APPROVAL",payload(context(),Map.of()));
        var worker=service("CONTRACT_TASK_RECOVER");unavailableAuthorities.add("CONTRACT_APPROVE");reconcile(worker,authorityRecovery(worker));
        assertEquals("RETURNED",((Map<?,?>)context().get("workflow")).get("stage"));
        assertEquals("0",scalar("select count(*) from contract.revision_approval_decision where tenant_id=?",seed.tenant()));
        assertEquals("0",scalar("select count(*) from contract.signature_readiness where tenant_id=?",seed.tenant()));
    }
}
