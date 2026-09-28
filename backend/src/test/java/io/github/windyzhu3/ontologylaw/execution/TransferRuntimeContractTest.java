package io.github.windyzhu3.ontologylaw.execution;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.audit.ReceiptRecoveryMetadata;
import java.util.*;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class TransferRuntimeContractTest {
 @Test void transfer_events_and_public_receipts_use_the_r2_contract(){
  var actor=new Actor(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,null,PrincipalKind.HUMAN);
  for(var type:CommandEnvelope.Type.values())if(type.transfers()){
   assertEquals(Set.of(CommandHandler.QueueOwner.R2_PROJECTION),R1EventPolicy.transferEvent(type).queueOwners());
   assertTrue(R1EventPolicy.branches().stream().noneMatch(b->b.command()==type));assertEquals(1,R1EventPolicy.r2Branches().stream().filter(b->b.command()==type).count());
   var fact=new Subject(R1CommandPolicy.transferResultType(type),UUID.randomUUID(),0L,null);var outcome=new CommandOutcome(UUID.randomUUID(),CommandOutcome.Status.SUCCEEDED,fact,null);var receipt=new CommandReceiptReader.Receipt(UUID.randomUUID(),type.name(),"INTERNAL_ADMIN",new byte[32],outcome,java.time.Instant.now(),new Subject("execution.command_receipt",outcome.receiptId(),0L,null));
   var projected=(Map<?,?>)receipt.projection(actor).get("resultFact");assertEquals(type==CommandEnvelope.Type.CLASSIFY_MATTER?"MATTER_CLASSIFICATION":type==CommandEnvelope.Type.RECORD_TRANSFER_CONFLICT_REVIEW?"TRANSFER_CONFLICT_REVIEW":type==CommandEnvelope.Type.RECORD_TRANSFER_INTAKE?"TRANSFER_INTAKE":"TRANSFER_SUBMISSION",projected.get("factType"));
  }
 }
 @Test void human_transfer_commands_bind_exact_workflow_and_recoverable_receipts(){
  Actor actor=new Actor(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,null,PrincipalKind.HUMAN);
  var opportunity=new Subject("opportunity.opportunity",UUID.randomUUID(),2L,null);var workflow=new Subject("transfer.workflow",UUID.randomUUID(),0L,null);var binding=new CommandAuthorizationBinding.Transfers(opportunity,workflow);
  for(String name:List.of("SUBMIT_TRANSFER","RESUBMIT_TRANSFER","RECORD_TRANSFER_CONFLICT_REVIEW","RECORD_TRANSFER_INTAKE","CLASSIFY_MATTER")){
   var type=CommandEnvelope.Type.valueOf(name);assertTrue(type.transfers());var e=new CommandEnvelope(type,UUID.randomUUID(),UUID.randomUUID(),actor,Map.of("opportunity",CommandScope.selector(opportunity),"workflow",CommandScope.selector(workflow),"input",Map.of()));
   assertEquals(CommandEnvelope.Envelope.INTERNAL_ADMIN,e.envelope());var scope=CommandScope.transfers(e,binding);
   var request=new Request(actor,opportunity,UUID.randomUUID(),new Requirement(R1CommandPolicy.transferAuthority(type),"OPPORTUNITY_OWNER",Path.DIRECT,UUID.randomUUID()));
   var metadata=CommandRecoveryMetadata.freeze(e,new CommandHandler.Context(scope,request,binding));var recovery=new ReceiptRecoveryMetadata(name,metadata);assertEquals(scope.fields(),recovery.transfersScope());assertNotNull(R1EventPolicy.transferEvent(type));assertTrue(R1CommandPolicy.transferResultType(type).startsWith("transfer."));
   assertThrows(IllegalArgumentException.class,()->new CommandEnvelope(type,UUID.randomUUID(),UUID.randomUUID(),new Actor(actor.tenantId(),actor.principalId(),actor.appointmentId(),null,null,PrincipalKind.SERVICE),e.payload()));
  }
 }
}
