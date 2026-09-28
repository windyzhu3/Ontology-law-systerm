package io.github.windyzhu3.ontologylaw.responsibility;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContractTaskRegistrationTest {
    @Test void contract_responsibilities_have_explicit_opportunity_subject_and_exact_completion() {
        var expected=new TreeMap<>(Map.of(
            "REQUEST_CONTRACT_PREPARATION","CONTRACT_PREPARATION_REQUEST",
            "DECIDE_CONTRACT_PREPARATION","CONTRACT_PREPARATION_DECISION",
            "PREPARE_CONTRACT","CONTRACT_REVISION",
            "SUBMIT_CONTRACT_REVIEW","CONTRACT_REVIEW_REQUEST",
            "REVIEW_CONTRACT","CONTRACT_REVIEW_DECISION",
            "SUBMIT_CONTRACT_APPROVAL","CONTRACT_APPROVAL_REQUEST",
            "APPROVE_CONTRACT","CONTRACT_APPROVAL_DECISION",
            "SUPPLEMENT_CONTRACT_REVIEW","CONTRACT_REVIEW_REQUEST"));
        expected.putAll(Map.of("ARRANGE_CONTRACT_SIGNATURE","CONTRACT_SIGNATURE_ARRANGEMENT","COLLECT_CONTRACT_SIGNATURE","CONTRACT_SIGNATURE_SUBMISSION","VERIFY_CONTRACT_SIGNATURE","CONTRACT_SIGNATURE_VERIFICATION","ARCHIVE_CONTRACT_SIGNATURE","CONTRACT_SIGNATURE_ARCHIVE"));
        var actual=new TreeMap<String,String>();
        expected.put("REVIEW_CONTRACT_TERMINATION","CONTRACT_NEGOTIATION_DISPOSITION");
        expected.put("CHECK_CONTRACT_EXECUTION","CONTRACT_EXECUTION_VERIFICATION");
        expected.put("CHECK_CONTRACT_RECEIPT","CONTRACT_PAYMENT_REVIEW");
        expected.put("SUPPLEMENT_CONTRACT_RECEIPT","CONTRACT_PAYMENT_REVIEW");
        for(var type:TaskFactory.Type.values())if(type.isContract()) {
            actual.put(type.name(),type.publicCompletionType());
            assertEquals("opportunity.opportunity",type.subjectType());
            assertEquals("R2_BUSINESS_4H_V1",type.slaCode());
            assertEquals(14400,type.slaSeconds());
            assertTrue(type.completionType.startsWith("contract."));

        }
        assertEquals(expected,actual);
        assertEquals("FORM_CONTRACT",TaskFactory.Type.PREPARE_CONTRACT.command);
        assertEquals("contract.contract_revision",TaskFactory.Type.PREPARE_CONTRACT.completionType);
    }
    @Test void only_actual_decision_responsibilities_allow_a_different_owner_than_sales() {
        var actual=Arrays.stream(TaskFactory.Type.values()).filter(TaskFactory.Type::isContract).filter(TaskFactory.Type::independentDecisionOwner).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of(TaskFactory.Type.CHECK_CONTRACT_RECEIPT,TaskFactory.Type.REVIEW_CONTRACT_TERMINATION,TaskFactory.Type.DECIDE_CONTRACT_PREPARATION,TaskFactory.Type.REVIEW_CONTRACT,TaskFactory.Type.APPROVE_CONTRACT,TaskFactory.Type.VERIFY_CONTRACT_SIGNATURE,TaskFactory.Type.ARCHIVE_CONTRACT_SIGNATURE),actual);
        assertEquals("CONTRACT_PREPARATION_DECIDE",TaskFactory.Type.DECIDE_CONTRACT_PREPARATION.authority);
        assertEquals("CONTRACT_REVIEW",TaskFactory.Type.REVIEW_CONTRACT.authority);
        assertEquals("CONTRACT_APPROVE",TaskFactory.Type.APPROVE_CONTRACT.authority);
        assertEquals("PAYMENT_CONFIRM",TaskFactory.Type.CHECK_CONTRACT_RECEIPT.authority);
    }
    @Test void cancellation_refuses_unregistered_reasons_and_unrelated_responsibilities_before_sql() {
        var repo=TaskFactory.databaseBacked();var tenant=UUID.randomUUID();var now=Instant.now();
        var lead=new TaskFactory.Task(new Subject("responsibility.task_occurrence",UUID.randomUUID(),0L,null),UUID.randomUUID(),TaskFactory.Type.CONTACT_LEAD,new Subject("lead.lead",UUID.randomUUID(),0L,null),"OPEN",now,null);
        assertThrows(IllegalArgumentException.class,()->repo.cancelForContract(null,tenant,lead,"CONTRACT_WORKFLOW_TAKEOVER",now));
        var sales=new TaskFactory.Task(lead.selector(),lead.owner(),TaskFactory.Type.PROGRESS_OPPORTUNITY,new Subject("opportunity.opportunity",UUID.randomUUID(),0L,null),"OPEN",now,null);
        assertThrows(IllegalArgumentException.class,()->repo.cancelForContract(null,tenant,sales,"CONTRACT_RETURNED",now));
        assertThrows(IllegalArgumentException.class,()->repo.cancelForContract(null,tenant,sales,"UNREGISTERED",now));
    }
}
