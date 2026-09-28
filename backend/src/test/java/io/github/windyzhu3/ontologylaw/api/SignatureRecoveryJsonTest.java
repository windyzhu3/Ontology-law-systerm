package io.github.windyzhu3.ontologylaw.api;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.api.adapter.generated.model.*;
class SignatureRecoveryJsonTest {
    @Test void recovery_endpoint_serializes_both_exact_durable_receipt_variants() {
        for(String fact:List.of("CONTRACT_PREPARATION_WORKFLOW","CONTRACT_SIGNATURE_WORKFLOW")) {
            var body=Map.of("commandId",UUID.randomUUID().toString(),"receiptId",UUID.randomUUID().toString(),"outcome","SUCCEEDED","completedAt","2026-09-23T01:00:00Z","resultFact",Map.of("factType",fact,"factRef","opaque-exact-workflow-fact","revision",0));
            var receipt=assertDoesNotThrow(()->R1WireModels.model(body,ContractPreparationReconcileReceiptV1.class));
            assertEquals(fact.equals("CONTRACT_SIGNATURE_WORKFLOW")?R2ContractSignatureWorkflowFactRefV1.class:ContractPreparationWorkflowFactRefV1.class,receipt.getResultFact().getClass());
        }
    }
}
