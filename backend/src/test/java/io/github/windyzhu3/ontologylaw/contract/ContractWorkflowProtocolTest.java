package io.github.windyzhu3.ontologylaw.contract;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.contract.ContractWorkflowProtocol.*;

class ContractWorkflowProtocolTest {
    @Test void review_and_all_approvals_are_required_before_signature_boundary() {
        assertThrows(IllegalArgumentException.class,()->requireAllowed(Stage.FORMED,Action.REQUEST_CONTRACT_APPROVAL));
        assertThrows(IllegalArgumentException.class,()->requireAllowed(Stage.PREPARE,Action.RECORD_CONTRACT_DECISION));
        var stage=after(Stage.FORMED,Action.REQUEST_CONTRACT_REVIEW,null,null,false);
        assertEquals(Stage.AWAIT_REVIEW,stage);
        assertThrows(IllegalArgumentException.class,()->after(Stage.AWAIT_REVIEW,Action.RECORD_CONTRACT_REVIEW,null,null,false));
        stage=after(stage,Action.RECORD_CONTRACT_REVIEW,ReviewOutcome.CLEAR,null,false);
        assertEquals(Stage.REVIEW_PASSED,stage);
        stage=after(stage,Action.REQUEST_CONTRACT_APPROVAL,null,null,false);
        assertEquals(Stage.AWAIT_APPROVAL,after(stage,Action.RECORD_CONTRACT_DECISION,null,Decision.APPROVED,false));
        assertEquals(Stage.READY_FOR_SIGNATURE,after(stage,Action.RECORD_CONTRACT_DECISION,null,Decision.APPROVED,true));
        assertThrows(IllegalArgumentException.class,()->after(Stage.AWAIT_APPROVAL,Action.RECORD_CONTRACT_DECISION,null,Decision.RETURNED,true));
    }
    @Test void return_and_supplement_require_a_new_version_and_review() {
        for(var result:new ReviewOutcome[]{ReviewOutcome.NEED_INFO,ReviewOutcome.BLOCKED}) {
            var stage=after(Stage.AWAIT_REVIEW,Action.RECORD_CONTRACT_REVIEW,result,null,false);
            assertEquals(result==ReviewOutcome.NEED_INFO?Stage.NEED_INFO:Stage.BLOCKED,stage);
            assertThrows(IllegalArgumentException.class,()->requireAllowed(stage,Action.REQUEST_CONTRACT_APPROVAL));
            assertEquals(Stage.FORMED,after(stage,Action.FORM_CONTRACT,null,null,false));
        }
        var returned=after(Stage.AWAIT_APPROVAL,Action.RECORD_CONTRACT_DECISION,null,Decision.RETURNED,false);
        assertEquals(Stage.RETURNED,returned);
        assertThrows(IllegalArgumentException.class,()->requireAllowed(returned,Action.REQUEST_CONTRACT_APPROVAL));
        assertEquals(Stage.FORMED,after(returned,Action.FORM_CONTRACT,null,null,false));
    }
    @Test void explanation_only_supplement_requests_fresh_review_of_same_version() {
        assertEquals(Stage.AWAIT_REVIEW,after(Stage.NEED_INFO,Action.REQUEST_CONTRACT_REVIEW,null,null,false));
        assertThrows(IllegalArgumentException.class,()->requireAllowed(Stage.BLOCKED,Action.REQUEST_CONTRACT_REVIEW));
    }
    @Test void draft_save_does_not_advance_or_complete_responsibility() {
        for(var stage:Stage.values())
            assertEquals(stage,after(stage,Action.SAVE_CONTRACT_DRAFT,null,null,false));
        assertEquals(Stage.FORMED,after(Stage.READY_FOR_SIGNATURE,Action.FORM_CONTRACT,null,null,false));
    }
    @Test void two_sources_converge_only_after_authoritative_source_validation() {
        assertEquals(Stage.PREPARE,after(Stage.INITIAL,Action.START_CONTRACT_PREPARATION,null,null,false));
        var requested=after(Stage.INITIAL,Action.REQUEST_CONTRACT_PREPARATION,null,null,false);
        assertEquals(Stage.PREPARATION_REQUESTED,requested);
        assertThrows(IllegalArgumentException.class,()->requireAllowed(requested,Action.START_CONTRACT_PREPARATION));
        var approved=after(requested,Action.RECORD_CONTRACT_PREPARATION_DECISION,null,Decision.APPROVED,false);
        assertEquals(Stage.SOURCE_APPROVED,approved);
        assertEquals(Stage.PREPARE,after(approved,Action.START_CONTRACT_PREPARATION,null,null,false));
        assertEquals(Stage.PREPARATION_RETURNED,after(requested,Action.RECORD_CONTRACT_PREPARATION_DECISION,null,Decision.RETURNED,false));
    }
    @Test void decisions_require_separate_authorities_and_new_exact_result_types() {
        assertEquals("CONTRACT_PREPARATION_DECIDE",authority(Action.RECORD_CONTRACT_PREPARATION_DECISION));
        assertEquals("CONTRACT_REVIEW",authority(Action.RECORD_CONTRACT_REVIEW));
        assertEquals("CONTRACT_APPROVE",authority(Action.RECORD_CONTRACT_DECISION));
        assertEquals("contract.revision_approval_request",resultType(Action.REQUEST_CONTRACT_APPROVAL));
        assertEquals("contract.contract_revision",resultType(Action.FORM_CONTRACT));
        assertEquals("ContractDecisionRecordedV1",event(Action.RECORD_CONTRACT_DECISION));
        for(var action:Action.values()) {assertFalse(authority(action).isBlank());assertTrue(resultType(action).startsWith("contract."));assertTrue(event(action).endsWith("V1"));}
        assertThrows(IllegalArgumentException.class,()->after(Stage.PREPARE,Action.FORM_CONTRACT,ReviewOutcome.CLEAR,null,false));
        assertThrows(IllegalArgumentException.class,()->after(Stage.PREPARE,Action.FORM_CONTRACT,null,null,true));
    }
}
