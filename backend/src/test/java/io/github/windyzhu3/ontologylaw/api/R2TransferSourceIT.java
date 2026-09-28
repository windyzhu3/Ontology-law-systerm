package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.contract.ContractTransferSourceReader;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;

class R2TransferSourceIT extends R2ContractExecutionHandoffIT {
 @Test void transfer_source_requires_actual_execution_and_keeps_exact_activation_without_creating_case() throws Exception {
  var trigger=executionSource();
  var reader=ContractTransferSourceReader.databaseBacked();
  UUID contract=UUID.fromString(scalar("select contract_id from contract.contract where tenant_id=?",seed.tenant()));
  try(var c=database.apiConnection()) {
   assertTrue(inTransaction(c,Capability.QUERY,x->reader.find(x,seed.tenant(),contract)).isEmpty());
  }
  recoverExecution(service("CONTRACT_TASK_RECOVER"),trigger);
  command("VERIFY_CONTRACT_EXECUTION_CONDITIONS",executionVerificationPayload());
  try(var c=database.apiConnection()) {
   var source=inTransaction(c,Capability.QUERY,x->reader.find(x,seed.tenant(),contract)).orElseThrow();
   assertEquals(contract,source.contractId());assertEquals(opportunity.id(),source.opportunityId());
   assertEquals(scalar("select contract_execution_id from contract.contract where tenant_id=?",seed.tenant()),source.executionId().toString());
   assertEquals(scalar("select encode(activation_source_hash,'hex') from contract.contract where tenant_id=?",seed.tenant()),source.activationDigest());
   assertEquals(scalar("select current_revision_id from contract.contract where tenant_id=?",seed.tenant()),source.revisionId().toString());
   assertNotNull(source.activatedAt());assertNotNull(source.archiveId());assertNotNull(source.verificationId());
   assertEquals(source,inTransaction(c,Capability.QUERY,x->reader.find(x,seed.tenant(),contract)).orElseThrow());
   assertTrue(inTransaction(c,Capability.QUERY,x->reader.find(x,UUID.randomUUID(),contract)).isEmpty());
   assertEquals(java.util.List.of(source),inTransaction(c,Capability.QUERY,x->reader.page(x,seed.tenant(),1,null)));
   assertTrue(inTransaction(c,Capability.QUERY,x->reader.page(x,seed.tenant(),1,contract)).isEmpty());
   assertThrows(IllegalArgumentException.class,()->reader.page(c,seed.tenant(),101,null));
  }
  assertEquals("0",scalar("select count(*) from transfer.transfer_request where tenant_id=?",seed.tenant()));
 }
}
