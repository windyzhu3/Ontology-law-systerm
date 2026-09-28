package io.github.windyzhu3.ontologylaw.worker;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SignatureRecoveryReceiptTest {
    @Test void signature_readiness_discovery_accepts_null_workflow_and_terminal_cursor() {
        String body="""
            {"candidates":[{"kind":"CONTRACT_PREPARATION","idempotencyKey":"a9c6e02a-5080-559d-b274-432d67cb03b0","opportunityId":"01a0cd92-781c-7b50-85ae-1fde3e44f9ea","expectedOpportunityRevision":0,"responsibilityBasis":{"id":"01a0cd92-781c-7b50-85ae-1fde3e44f9ea","revision":0},"source":{"id":"01a0cd98-a184-7e72-990f-96ce7b616b6e","revision":0},"expectedWorkflow":null,"sourceKind":"SIGNATURE_READINESS"}],"nextCursor":null}
            """;
        var page=InternalApiClient.parseOpportunityCandidates(InternalApiClient.OpportunityKind.CONTRACT_PREPARATION,new InternalApiClient.Result(200,body));
        assertEquals(200,page.status());assertEquals(1,page.candidates().size());assertNull(page.nextCursor());
    }
    @Test void receipt_fact_must_match_exact_recovery_source_kind() {
        for(String kind:List.of("ACCEPTED_QUOTE","AUTHORITY_RETURN","SIGNATURE_READINESS","SIGNATURE_AUTHORITY_RETURN","TERMINATION_REVIEW","PAYMENT_HANDOFF","PAYMENT_RECOVERY","EXECUTION_HANDOFF","TRANSFER_HANDOFF","TRANSFER_RECOVERY")) {
            var head=UUID.randomUUID();
            var c=new InternalApiClient.ContractPreparationCandidate(UUID.fromString("6ba7b810-9dad-51d1-80b4-00c04fd430c8"),UUID.randomUUID(),0,UUID.randomUUID(),0,head,kind.endsWith("AUTHORITY_RETURN")||kind.equals("TRANSFER_RECOVERY")?head:kind.equals("TERMINATION_REVIEW")?UUID.randomUUID():null,kind);
            for(String fact:List.of("CONTRACT_PREPARATION_WORKFLOW","CONTRACT_SIGNATURE_WORKFLOW","CONTRACT_TERMINATION_REVIEW_ASSIGNMENT","CONTRACT_PAYMENT_WORKFLOW","PAYMENT_WORKFLOW","CONTRACT_EXECUTION_WORKFLOW","TRANSFER_WORKFLOW","TASK_OCCURRENCE")) {
                String body="{\"commandId\":\""+c.idempotencyKey()+"\",\"receiptId\":\""+UUID.randomUUID()+"\",\"outcome\":\"SUCCEEDED\",\"completedAt\":\"2026-09-23T01:00:00.000000Z\",\"resultFact\":{\"factType\":\""+fact+"\",\"factRef\":\"opaque-exact-fact-reference\",\"revision\":0}}";
                String expected=kind.startsWith("TRANSFER_")?"TRANSFER_WORKFLOW":kind.startsWith("PAYMENT_")?"CONTRACT_PAYMENT_WORKFLOW":kind.equals("EXECUTION_HANDOFF")?"CONTRACT_EXECUTION_WORKFLOW":kind.equals("TERMINATION_REVIEW")?"CONTRACT_TERMINATION_REVIEW_ASSIGNMENT":kind.startsWith("SIGNATURE_")?"CONTRACT_SIGNATURE_WORKFLOW":"CONTRACT_PREPARATION_WORKFLOW";
                assertEquals(expected.equals(fact)?200:503,InternalApiClient.validateOpportunityReceipt(c,new InternalApiClient.Result(200,body)).status(),kind+" / "+fact);
            }
        }
    }
}
