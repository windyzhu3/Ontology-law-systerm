package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.transfer.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
class R2TransferIntakeIT extends R2TransferConflictReviewIT {
 @Test void concurrent_intake_accept_has_one_case_snapshot_and_classification_successor()throws Exception{
  var w=readyForIntake();var a=reviewerActor();var input=new TransferIntakeInput("ACCEPT",null,"准确资料核对完成",true);
  var start=new java.util.concurrent.CountDownLatch(1);
  try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
   var call=(java.util.concurrent.Callable<Boolean>)()->{start.await();try(var c=database.apiConnection()){
    try{inTransaction(c,Capability.COMMAND,x->workflows().intake(x,a,w,input));return true;}catch(TransferWorkflowService.Blocked stale){return false;}
   }};
   var first=pool.submit(call);var second=pool.submit(call);start.countDown();
   assertNotEquals(first.get(30,java.util.concurrent.TimeUnit.SECONDS),second.get(30,java.util.concurrent.TimeUnit.SECONDS));
  }
  assertEquals("1",scalar("select count(*) from transfer.transfer_request where tenant_id=? and matter_id is not null",seed.tenant()));
  assertEquals("1",scalar("select count(*) from transfer.intake where tenant_id=?",seed.tenant()));
  assertEquals("1",scalar("select count(*) from transfer.transfer_snapshot where tenant_id=?",seed.tenant()));
  assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='CLASSIFY_MATTER' and state='OPEN'",seed.tenant()));
 }
 Subject readyForIntake()throws Exception{
  var initial=beginTransfer();submitTransfer(initial);var review=currentReview();var a=reviewerActor();
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->workflows().review(x,a,review,new TransferConflictReviewInput("CLEAR","准确主体及范围已核对",true)));}
  return new Subject("transfer.workflow",UUID.fromString(scalar("select workflow_id from transfer.workflow where tenant_id=? and stage_code='INTAKE'",seed.tenant())),0L,null);
 }
 @Test void intake_accept_creates_one_unclassified_matter_and_classification_duty()throws Exception{
  var w=readyForIntake();var a=reviewerActor();var input=new TransferIntakeInput("ACCEPT",null,"本次准确材料完整，确认接收",true);
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->workflows().intake(x,a,w,input));}
  assertEquals("1",scalar("select count(*) from transfer.transfer_request where tenant_id=? and matter_id is not null and matter_type_code='UNCLASSIFIED'",seed.tenant()));
  assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='ACCEPT_TRANSFER' and state='DONE'",seed.tenant()));
  assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='CLASSIFY_MATTER' and state='OPEN'",seed.tenant()));
  try(var c=database.apiConnection()){assertThrows(TransferWorkflowService.Blocked.class,()->inTransaction(c,Capability.COMMAND,x->workflows().intake(x,a,w,input)));}
 }

 @Test void intake_return_keeps_case_empty_and_creates_exact_sales_correction()throws Exception{
  var w=readyForIntake();var a=reviewerActor();
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->workflows().intake(x,a,w,new TransferIntakeInput("RETURN","CLIENT_IDENTITY","主体证明缺少完整页",false)));}
  assertEquals("0",scalar("select count(*) from transfer.transfer_request where tenant_id=? and matter_id is not null",seed.tenant()));
  assertEquals("1",scalar("select count(*) from transfer.transfer_return_item where tenant_id=? and requirement_code='CLIENT_IDENTITY'",seed.tenant()));
  assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='SUPPLEMENT_TRANSFER' and state='OPEN'",seed.tenant()));
 }
 @Test void intake_accept_audit_failure_rolls_back_case_decision_snapshot_and_successor()throws Exception{
  var w=readyForIntake();var a=reviewerActor();
  try(var c=database.apiConnection()){assertThrows(IllegalStateException.class,()->inTransaction(c,Capability.COMMAND,x->{workflows().intake(x,a,w,new TransferIntakeInput("ACCEPT",null,"已核对",true));throw new IllegalStateException("audit failure");}));}
  assertEquals("0",scalar("select count(*) from transfer.transfer_request where tenant_id=? and matter_id is not null",seed.tenant()));
  assertEquals("0",scalar("select count(*) from transfer.transfer_snapshot where tenant_id=?",seed.tenant()));
  assertEquals("0",scalar("select count(*) from transfer.intake where tenant_id=?",seed.tenant()));
  assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='ACCEPT_TRANSFER' and state='OPEN'",seed.tenant()));
 }
 @Test void intake_accept_classification_owner_recovery_preserves_case_and_deadline()throws Exception{
  var w=readyForIntake();var a=reviewerActor();
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->workflows().intake(x,a,w,new TransferIntakeInput("ACCEPT",null,"已核对",true)));}
  String matter=scalar("select matter_id from transfer.transfer_request where tenant_id=?",seed.tenant());String due=scalar("select due_at::text from transfer.workflow where tenant_id=? and stage_code='CLASSIFY'",seed.tenant());
  var classification=new Subject("transfer.workflow",UUID.fromString(scalar("select workflow_id from transfer.workflow where tenant_id=? and stage_code='CLASSIFY'",seed.tenant())),0L,null);ownerAvailable=false;var worker=service("TRANSFER_TASK_RECOVER");
  Subject exception;try(var c=database.apiConnection()){exception=inTransaction(c,Capability.COMMAND,x->workflows().recover(x,worker,classification));}
  assertEquals("OWNER_EXCEPTION",scalar("select stage_code from transfer.workflow where tenant_id=? and workflow_id=?",seed.tenant(),exception.id()));
  ownerAvailable=true;try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->workflows().recover(x,worker,exception));}
  assertEquals(matter,scalar("select matter_id from transfer.transfer_request where tenant_id=?",seed.tenant()));
  assertEquals(due,scalar("select w.due_at::text from transfer.workflow w where tenant_id=? and not exists(select 1 from transfer.workflow n where n.tenant_id=w.tenant_id and n.previous_workflow_id=w.workflow_id)",seed.tenant()));
 }

 @Test void intake_return_correct_resubmit_review_then_accept_keeps_the_original_snapshot_chain()throws Exception{
  var w=readyForIntake();var a=reviewerActor();
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->workflows().intake(x,a,w,new TransferIntakeInput("RETURN","CLIENT_IDENTITY","请补齐主体依据",false)));}
  var correction=new Subject("transfer.workflow",UUID.fromString(scalar("select workflow_id from transfer.workflow where tenant_id=? and stage_code='SUPPLEMENT'",seed.tenant())),0L,null);
  UUID item=UUID.fromString(scalar("select transfer_return_item_id from transfer.transfer_return_item where tenant_id=?",seed.tenant()));var proof=new TransferSubmissionInput.Material(material,bodySha);
  var input=new TransferSubmissionInput(proof,proof,"逐项补齐",true,List.of(new TransferSubmissionInput.Correction(item,"已补齐",proof)));
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->workflows().submit(x,actor(),correction,input));}
  var review=new Subject("transfer.workflow",UUID.fromString(scalar("select w.workflow_id from transfer.workflow w where tenant_id=? and stage_code='REVIEW_TRANSFER' and not exists(select 1 from transfer.workflow n where n.tenant_id=w.tenant_id and n.previous_workflow_id=w.workflow_id)",seed.tenant())),0L,null);
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->workflows().review(x,a,review,new TransferConflictReviewInput("CLEAR","补正后重新核对通过",true)));}
  var intake=new Subject("transfer.workflow",UUID.fromString(scalar("select w.workflow_id from transfer.workflow w where tenant_id=? and stage_code='INTAKE' and not exists(select 1 from transfer.workflow n where n.tenant_id=w.tenant_id and n.previous_workflow_id=w.workflow_id)",seed.tenant())),0L,null);
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->workflows().intake(x,a,intake,new TransferIntakeInput("ACCEPT",null,"补正材料完整，确认接收",true)));}
  assertEquals("2",scalar("select count(*) from transfer.transfer_snapshot where tenant_id=?",seed.tenant()));
  assertEquals("1",scalar("select count(*) from transfer.transfer_snapshot where tenant_id=? and snapshot_no=2 and predecessor_snapshot_id is not null and previous_return_decision_record_id is not null and previous_return_items_digest is not null",seed.tenant()));
  assertEquals("1",scalar("select count(*) from transfer.transfer_request where tenant_id=? and matter_id is not null",seed.tenant()));
 }

 @Test void intake_return_text_correction_does_not_require_an_unrelated_attachment()throws Exception{
  var w=readyForIntake();var a=reviewerActor();
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->workflows().intake(x,a,w,new TransferIntakeInput("RETURN","HANDOVER_EXPLANATION","请说明具体交接范围",false)));}
  var correction=new Subject("transfer.workflow",UUID.fromString(scalar("select workflow_id from transfer.workflow where tenant_id=? and stage_code='SUPPLEMENT'",seed.tenant())),0L,null);
  UUID item=UUID.fromString(scalar("select transfer_return_item_id from transfer.transfer_return_item where tenant_id=?",seed.tenant()));var proof=new TransferSubmissionInput.Material(material,bodySha);
  var input=new TransferSubmissionInput(proof,proof,"补齐交接范围",true,List.of(new TransferSubmissionInput.Correction(item,"已补齐批准合同约定范围")));
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->workflows().submit(x,actor(),correction,input));}
  assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='REVIEW_TRANSFER' and state='OPEN'",seed.tenant()));
 }
 @Test void intake_accept_does_not_allow_a_later_snapshot_to_reuse_the_acceptance_exception()throws Exception{
  var w=readyForIntake();var reviewer=reviewerActor();try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->workflows().intake(x,reviewer,w,new TransferIntakeInput("ACCEPT",null,"confirmed",true)));}
  var before=scalar("select matter_id from transfer.transfer_request where tenant_id=?",seed.tenant());
  // Exercise the deployed chain guard independently of earlier shape/FK guards.
  // Even the exact accepted snapshot cannot reuse the same-transaction ACCEPT exception later.
  var failure=assertThrows(java.sql.SQLException.class,()->{try(var c=database.adminConnection()){c.setAutoCommit(false);try(var sql=c.createStatement()){sql.execute("create temporary table later_snapshot_probe (like transfer.transfer_snapshot) on commit drop");sql.execute("create trigger later_snapshot_chain before insert on later_snapshot_probe for each row execute function platform_meta.fn_assert_transfer_snapshot_chain()");}try(var p=c.prepareStatement("insert into later_snapshot_probe select * from transfer.transfer_snapshot where tenant_id=?")){p.setObject(1,seed.tenant());p.executeUpdate();}finally{c.rollback();}}});
  assertEquals("55000",failure.getSQLState());assertTrue(failure.getMessage().contains("accepted transfer rejects new snapshots"),failure.getMessage());
  assertEquals("1",scalar("select count(*) from transfer.transfer_snapshot where tenant_id=?",seed.tenant()));
  assertEquals(before,scalar("select matter_id from transfer.transfer_request where tenant_id=?",seed.tenant()));
 }

}
