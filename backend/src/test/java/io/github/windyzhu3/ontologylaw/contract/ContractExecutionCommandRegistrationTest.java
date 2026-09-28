package io.github.windyzhu3.ontologylaw.contract;
import io.github.windyzhu3.ontologylaw.execution.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ContractExecutionCommandRegistrationTest {
 @Test void human_verification_has_a_named_authority_fact_and_event(){
  var type=CommandEnvelope.Type.valueOf("VERIFY_CONTRACT_EXECUTION_CONDITIONS");
  assertTrue(type.contracts());
  assertEquals("CONTRACT_EXECUTION_VERIFY",R1CommandPolicy.contractAuthority(type));
  assertEquals("contract.execution_verification",R1CommandPolicy.contractResultType(type));
  assertEquals("ContractExecutionConditionsVerifiedV1",R1EventPolicy.contractEvent(type).name());
 }
}
