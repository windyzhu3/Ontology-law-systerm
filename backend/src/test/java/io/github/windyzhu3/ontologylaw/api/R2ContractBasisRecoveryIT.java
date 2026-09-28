package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.contract.*;
import io.github.windyzhu3.ontologylaw.execution.CommandOutcome;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;

/** Recover changed basis through existing revise/return actions without inventing workflow history. */
class R2ContractBasisRecoveryIT extends R2ContractWorkflowPersistenceIT {
    @Test void unavailable_approver_restores_revision_without_rewriting_history()throws Exception {
        versionFixture();formThroughWorkflow();var before=context();approversEligible=false;
        var after=context();assertEquals("RETURNED",((Map<?,?>)after.get("workflow")).get("stage"));
        assertEquals(((Map<?,?>)before.get("workflow")).get("selector"),((Map<?,?>)after.get("workflow")).get("selector"));
        assertTrue(((List<?>)after.get("allowedActions")).contains("REQUEST_CONTRACT_PREPARATION"));
        assertFalse(((List<?>)after.get("allowedActions")).contains("REQUEST_CONTRACT_REVIEW"));
    }
    @Test void revoked_body_cannot_be_approved_and_can_be_returned_to_sales()throws Exception {
        versionFixture();formThroughWorkflow();
        command("REQUEST_CONTRACT_REVIEW",payload(context(),Map.of()));
        command("RECORD_CONTRACT_REVIEW",payload(context(),Map.of("decision","CLEAR","reason","完整范围已经人工核对")));
        command("REQUEST_CONTRACT_APPROVAL",payload(context(),Map.of()));
        mutate("update evidence.evidence_binding set revoked_at=clock_timestamp(),revoked_by_appointment_id=?,revocation_authorization_digest=decode(repeat('ab',32),'hex'),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and evidence_binding_id=(select evidence_binding_id from opportunity.material_version where tenant_id=? and material_version_id=?)",seed.appointment(),seed.tenant(),seed.tenant(),material);
        assertThrows(ContractWorkflowService.Blocked.class,()->command("RECORD_CONTRACT_DECISION",payload(context(),Map.of("decision","APPROVED","reason","不能批准撤回的正文"))));
        assertEquals("0",scalar("select count(*) from contract.signature_readiness where tenant_id=?",seed.tenant()));
        command("RECORD_CONTRACT_DECISION",payload(context(),Map.of("decision","RETURNED","reason","合同正文已撤回，请重新准备")));
        assertEquals("RETURNED",((Map<?,?>)context().get("workflow")).get("stage"));
        assertTrue(((List<?>)context().get("allowedActions")).contains("FORM_CONTRACT"));
    }
    void changedPolicy()throws Exception {
        UUID p=UUID.randomUUID();try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            sql(x,"insert into contract.approval_policy(tenant_id,approval_policy_id,organization_unit_id,policy_code,policy_version,mode,policy_digest,created_at) values(?,?,?,'R2_CONTRACT_APPROVAL_V1',2,'REQUIRE_APPROVAL',decode(repeat('67',32),'hex'),clock_timestamp())",seed.tenant(),p,seed.org());
            sql(x,"insert into contract.approval_policy_member(tenant_id,approval_policy_member_id,policy_id,requirement_code,appointment_id,created_at) values(?,?,?,'LEGAL',?,clock_timestamp())",seed.tenant(),UUID.randomUUID(),p,seed.appointment());return null;
        });}
    }
    void changedConfirmation()throws Exception {
        var values=canonical(confirmationFact);values.put("customerGoal","客户重新确认后的准确服务需求");
        var draft=execute(command(false,draftFact,confirmationFact,values));assertEquals(CommandOutcome.Status.SUCCEEDED,draft.status(),draft.rejectionCode());
        var confirmed=execute(command(true,draft.resultFact(),confirmationFact,null));assertEquals(CommandOutcome.Status.SUCCEEDED,confirmed.status(),confirmed.rejectionCode());
        confirmationFact=confirmed.resultFact();confirmation=confirmationFact.id();draftFact=draft.resultFact();
    }
    void policyRecovery(String originalStage)throws Exception {
        var before=context();Object exact=((Map<?,?>)before.get("workflow")).get("selector");String count=scalar("select count(*) from contract.preparation_workflow where tenant_id=?",seed.tenant());
        changedPolicy();var changed=context();var actions=(List<?>)changed.get("allowedActions");
        assertTrue(actions.contains("FORM_CONTRACT"),"Changed policy must expose revision at "+originalStage);
        assertTrue(actions.contains("REQUEST_CONTRACT_PREPARATION"));
        assertFalse(actions.contains(originalStage.equals("SUBMIT_REVIEW")?"REQUEST_CONTRACT_REVIEW":"REQUEST_CONTRACT_APPROVAL"));
        assertEquals(exact,((Map<?,?>)changed.get("workflow")).get("selector"));
        assertEquals(count,scalar("select count(*) from contract.preparation_workflow where tenant_id=?",seed.tenant()));
        assertEquals(originalStage,scalar("select stage_code from contract.preparation_workflow w where tenant_id=? and not exists(select 1 from contract.preparation_workflow n where n.tenant_id=w.tenant_id and n.previous_workflow_id=w.preparation_workflow_id)",seed.tenant()));
        formThroughWorkflow();assertEquals("2",scalar("select count(*) from contract.contract_revision where tenant_id=?",seed.tenant()));
    }
    @Test void changed_policy_before_review_restores_revision_action_without_fake_return()throws Exception {
        versionFixture();formThroughWorkflow();policyRecovery("SUBMIT_REVIEW");
    }
    @Test void changed_policy_before_approval_restores_revision_action_without_fake_return()throws Exception {
        versionFixture();formThroughWorkflow();command("REQUEST_CONTRACT_REVIEW",payload(context(),Map.of()));command("RECORD_CONTRACT_REVIEW",payload(context(),Map.of("decision","CLEAR","reason","已核对完整主体范围")));policyRecovery("SUBMIT_APPROVAL");
    }
    @Test void changed_confirmation_before_review_allows_fresh_authorization()throws Exception {
        versionFixture();formThroughWorkflow();changedConfirmation();var changed=context();
        assertTrue(((List<?>)changed.get("allowedActions")).contains("REQUEST_CONTRACT_PREPARATION"));
        assertFalse(((List<?>)changed.get("allowedActions")).contains("REQUEST_CONTRACT_REVIEW"));
        var result=command("REQUEST_CONTRACT_PREPARATION",payload(changed,Map.of("commercial",terms.canonical(),"reason","根据新的客户确认重新申请")));
        assertEquals("contract.preparation_request",result.type());
    }
    @Test void changed_confirmation_blocks_approval_but_latest_pending_request_can_be_returned()throws Exception {
        initialize();var request=command("REQUEST_CONTRACT_PREPARATION",payload(context(),Map.of("commercial",terms.canonical(),"reason","申请准确直接准备范围")));changedConfirmation();
        assertThrows(Exception.class,()->approve(request));
        command("RECORD_CONTRACT_PREPARATION_DECISION",payload(context(),Map.of("decision","RETURNED","reason","客户需求已变更，退回重新申请")));
        assertEquals("RETURNED",scalar("select decision_code from contract.preparation_decision where tenant_id=? and preparation_request_id=?",seed.tenant(),request.id()));
        assertEquals("DIRECT_RETURNED",((Map<?,?>)context().get("workflow")).get("stage"));
        var next=command("REQUEST_CONTRACT_PREPARATION",payload(context(),Map.of("commercial",terms.canonical(),"reason","根据新客户确认重新申请")));assertNotNull(approve(next));
        assertThrows(Exception.class,()->{try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->repository().decide(x,seed.tenant(),request.id(),seed.appointment(),false,null,"禁止重复决定旧申请"));}});
    }
}
