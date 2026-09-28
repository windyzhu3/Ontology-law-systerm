package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.transfer.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
class R2MatterClassificationIT extends R2TransferIntakeIT {
 @Test void classification_after_acceptance_completes_the_chain_without_recreating_the_case()throws Exception{
  var intake=readyForIntake();var a=reviewerActor();
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->workflows().intake(x,a,intake,new TransferIntakeInput("ACCEPT",null,"材料完整，确认接收",true)));}
  String matter=scalar("select matter_id from transfer.transfer_request where tenant_id=?",seed.tenant());
  var workflow=new Subject("transfer.workflow",UUID.fromString(scalar("select workflow_id from transfer.workflow where tenant_id=? and stage_code='CLASSIFY'",seed.tenant())),0L,null);
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->workflows().classify(x,a,workflow,new TransferClassificationInput("GENERAL",reviewer,"接收后确认综法业务承接")));}
  assertEquals(matter,scalar("select matter_id from transfer.classification where tenant_id=?",seed.tenant()));
  assertEquals("GENERAL",scalar("select category_code from transfer.classification where tenant_id=?",seed.tenant()));
  assertEquals("1",scalar("select count(*) from transfer.workflow where tenant_id=? and stage_code='COMPLETE' and task_id is null",seed.tenant()));
  assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code in ('PREPARE_TRANSFER','REVIEW_TRANSFER','ACCEPT_TRANSFER','SUPPLEMENT_TRANSFER','CLASSIFY_MATTER') and state in ('OPEN','WAITING')",seed.tenant()));
 }

 @Test void classification_correction_preserves_the_case_identity_and_prior_classification()throws Exception{
  var intake=readyForIntake();var a=reviewerActor();
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->workflows().intake(x,a,intake,new TransferIntakeInput("ACCEPT",null,"确认接收",true)));}
  String matter=scalar("select matter_id from transfer.transfer_request where tenant_id=?",seed.tenant());
  var w=new Subject("transfer.workflow",UUID.fromString(scalar("select workflow_id from transfer.workflow where tenant_id=? and stage_code='CLASSIFY'",seed.tenant())),0L,null);
  Subject first;try(var c=database.apiConnection()){first=inTransaction(c,Capability.COMMAND,x->workflows().classify(x,a,w,new TransferClassificationInput("GENERAL",reviewer,"初次分类")));}
  var completed=new Subject("transfer.workflow",UUID.fromString(scalar("select workflow_id from transfer.workflow where tenant_id=? and stage_code='COMPLETE'",seed.tenant())),0L,null);
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->workflows().classify(x,a,completed,new TransferClassificationInput("ENFORCEMENT",reviewer,"核对后更正分类")));}
  assertEquals("2",scalar("select count(*) from transfer.classification where tenant_id=? and matter_id=?",seed.tenant(),UUID.fromString(matter)));
  assertEquals("1",scalar("select count(*) from transfer.classification where tenant_id=? and previous_classification_id=? and category_code='ENFORCEMENT'",seed.tenant(),first.id()));
  assertEquals("1",scalar("select count(*) from transfer.transfer_request where tenant_id=? and matter_id=?",seed.tenant(),UUID.fromString(matter)));
 }
 @Test void classification_rejects_premature_or_unqualified_assignment()throws Exception{
  var intake=readyForIntake();var a=reviewerActor();
  try(var c=database.apiConnection()){assertThrows(TransferWorkflowService.Blocked.class,()->inTransaction(c,Capability.COMMAND,x->workflows().classify(x,a,intake,new TransferClassificationInput("GENERAL",reviewer,"尚未接收"))));}
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->workflows().intake(x,a,intake,new TransferIntakeInput("ACCEPT",null,"确认接收",true)));}
  var w=new Subject("transfer.workflow",UUID.fromString(scalar("select workflow_id from transfer.workflow where tenant_id=? and stage_code='CLASSIFY'",seed.tenant())),0L,null);
  try(var c=database.apiConnection()){assertThrows(TransferWorkflowService.Blocked.class,()->inTransaction(c,Capability.COMMAND,x->workflows().classify(x,a,w,new TransferClassificationInput("GENERAL",seed.appointment(),"销售任职无承接资格"))));}
  assertEquals("0",scalar("select count(*) from transfer.classification where tenant_id=?",seed.tenant()));
 }
}
