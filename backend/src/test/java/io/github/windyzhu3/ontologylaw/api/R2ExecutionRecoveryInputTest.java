package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.worker.InternalApiClient;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class R2ExecutionRecoveryInputTest {
 @Test void execution_scope_keeps_archive_source_and_recovery_head_separate(){
  UUID tenant=UUID.randomUUID(),opportunity=UUID.randomUUID(),source=UUID.randomUUID(),head=UUID.randomUUID();
  var actor=new Actor(tenant,UUID.randomUUID(),UUID.randomUUID(),null,null,PrincipalKind.SERVICE);
  var p=new LinkedHashMap<String,Object>();p.put("opportunityId",opportunity.toString());p.put("expectedOpportunityRevision",0L);p.put("responsibilityBasis",Map.of("id",opportunity.toString(),"revision",0L));p.put("sourceKind","EXECUTION_HANDOFF");p.put("source",Map.of("id",source.toString(),"revision",0L));p.put("expectedWorkflow",null);
  var b=R2ContractRecoveryCommand.input(new CommandEnvelope(CommandEnvelope.Type.RECONCILE_CONTRACT_PREPARATION,UUID.randomUUID(),UUID.randomUUID(),actor,p));
  assertEquals("EXECUTION_HANDOFF",b.sourceKind());assertEquals("contract.signature_handoff",b.source().type());assertEquals("contract.execution_workflow",b.resultType());
  var key=ContractPreparationDiscovery.commandId(tenant,b);
  assertDoesNotThrow(()->new InternalApiClient.ContractPreparationCandidate(key,opportunity,0,opportunity,0,source,null,"EXECUTION_HANDOFF"));
  p.put("expectedWorkflow",Map.of("id",head.toString(),"revision",0L));var recovery=R2ContractRecoveryCommand.input(new CommandEnvelope(CommandEnvelope.Type.RECONCILE_CONTRACT_PREPARATION,UUID.randomUUID(),UUID.randomUUID(),actor,p));
  assertEquals("contract.execution_workflow",recovery.workflow().type());assertNotEquals(key,ContractPreparationDiscovery.commandId(tenant,recovery));
  p.put("source",Map.of("id",source.toString(),"revision",1L));assertThrows(CommandHandler.Rejected.class,()->R2ContractRecoveryCommand.input(new CommandEnvelope(CommandEnvelope.Type.RECONCILE_CONTRACT_PREPARATION,UUID.randomUUID(),UUID.randomUUID(),actor,p)));
 }
}
