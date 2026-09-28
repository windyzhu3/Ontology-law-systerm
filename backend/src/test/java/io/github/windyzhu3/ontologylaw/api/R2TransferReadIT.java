package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.transfer.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
class R2TransferReadIT extends R2TransferIntakeIT {
 @Test void transfer_read_follows_current_workflow_and_does_not_invent_case_before_acceptance()throws Exception{
  var initial=beginTransfer();var reader=TransferWorkflowReader.databaseBacked();
  TransferWorkflowReader.State before;try(var c=database.apiConnection()){before=inTransaction(c,Capability.COMMAND,x->reader.workflow(x,seed.tenant(),initial.id()));}
  assertEquals("PREPARE",before.stage());assertNull(before.matterId());assertNull(before.category());assertNotNull(before.taskId());
  submitTransfer(initial);
  try(var c=database.apiConnection()){var current=inTransaction(c,Capability.COMMAND,x->reader.current(x,seed.tenant(),before.request().id()));assertEquals("REVIEW_TRANSFER",current.stage());assertNotEquals(before.workflow(),current.workflow());assertEquals(before.dueAt(),current.dueAt());assertNotNull(current.submission());assertNull(current.review());assertNull(current.matterId());assertEquals(current,inTransaction(c,Capability.COMMAND,x->reader.forTask(x,seed.tenant(),current.taskId())));assertNull(inTransaction(c,Capability.COMMAND,x->reader.forTask(x,seed.tenant(),before.taskId())));assertNull(inTransaction(c,Capability.COMMAND,x->reader.current(x,UUID.randomUUID(),before.request().id())));}
 }
 @Test void transfer_read_after_classification_uses_latest_classification_and_same_case()throws Exception{
  var intake=readyForIntake();var a=reviewerActor();try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->workflows().intake(x,a,intake,new TransferIntakeInput("ACCEPT",null,"确认接收",true)));}
  var reader=TransferWorkflowReader.databaseBacked();UUID request=UUID.fromString(scalar("select transfer_request_id from transfer.transfer_request where tenant_id=?",seed.tenant()));
  TransferWorkflowReader.State pending;try(var c=database.apiConnection()){pending=inTransaction(c,Capability.COMMAND,x->reader.current(x,seed.tenant(),request));}
  assertNotNull(pending.matterId());assertNull(pending.category());assertEquals("CLASSIFY",pending.stage());
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->workflows().classify(x,a,pending.workflow(),new TransferClassificationInput("GENERAL",reviewer,"确认分类")));}
  TransferWorkflowReader.State completed;try(var c=database.apiConnection()){completed=inTransaction(c,Capability.COMMAND,x->reader.current(x,seed.tenant(),request));}
  assertEquals("GENERAL",completed.category());assertEquals(pending.matterId(),completed.matterId());assertEquals(reviewer,completed.recipient());assertNull(completed.taskId());
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->workflows().classify(x,a,completed.workflow(),new TransferClassificationInput("ENFORCEMENT",reviewer,"更正分类")));}
  try(var c=database.apiConnection()){var corrected=inTransaction(c,Capability.COMMAND,x->reader.current(x,seed.tenant(),request));assertEquals("ENFORCEMENT",corrected.category());assertEquals(pending.matterId(),corrected.matterId());assertEquals(pending.dueAt(),corrected.dueAt());assertNotEquals(completed.classification(),corrected.classification());}
 }
}
