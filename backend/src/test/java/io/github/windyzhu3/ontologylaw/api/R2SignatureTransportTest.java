package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class R2SignatureTransportTest {
    @Test void signing_tasks_keep_subject_and_independent_verifier_ownership(){
        for(String name:List.of("ARRANGE_CONTRACT_SIGNATURE","COLLECT_CONTRACT_SIGNATURE","VERIFY_CONTRACT_SIGNATURE","ARCHIVE_CONTRACT_SIGNATURE")){
            var task=io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.Type.valueOf(name);
            assertTrue(task.isContract());assertEquals("opportunity.opportunity",task.subjectType());
            assertEquals(name.startsWith("VERIFY")||name.startsWith("ARCHIVE"),task.independentDecisionOwner());
            assertTrue(CommandEnvelope.Type.valueOf(task.command).contracts());
            assertEquals(R1CommandPolicy.contractResultType(CommandEnvelope.Type.valueOf(task.command)),task.completionType);
        }
    }
    private static final Map<String,String> ACTIONS=Map.of(
        "signature-verification-revision","RETURN_CONTRACT_SIGNATURE_FOR_REVISION",
        "signature-draft","SAVE_CONTRACT_SIGNATURE_DRAFT",
        "signature-arrangements","CONFIRM_CONTRACT_SIGNATURE_ARRANGEMENT",
        "signature-submissions","SUBMIT_CONTRACT_SIGNATURE",
        "signature-verifications","RECORD_CONTRACT_SIGNATURE_VERIFICATION",
        "signature-archive","ARCHIVE_CONTRACT_SIGNATURE",
        "signature-revision","RETURN_CONTRACT_FOR_REVISION");
    @Test void every_signing_route_is_a_registered_contract_command_with_an_exact_result_and_event(){
        for(var entry:ACTIONS.entrySet()){
            var type=CommandEnvelope.Type.valueOf(entry.getValue());
            assertTrue(type.contracts());
            assertEquals(type,R1HttpOperations.find("POST","/api/v1/opportunities/"+UUID.randomUUID()+"/contracts/"+entry.getKey()).command());
            assertEquals(Set.of("signature-verifications","signature-archive","signature-verification-revision").contains(entry.getKey())?"CONTRACT_SIGNATURE_VERIFY":"CONTRACT_PREPARE",R1CommandPolicy.contractAuthority(type));
            assertEquals(R1CommandPolicy.contractResultType(type),R1EventPolicy.contractEvent(type).sourceFactType());
        }
    }
}
