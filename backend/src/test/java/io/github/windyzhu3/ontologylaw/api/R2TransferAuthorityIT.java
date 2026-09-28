package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.transfer.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
class R2TransferAuthorityIT extends R2TransferConflictReviewIT {
 @Test void revoked_intake_grant_blocks_then_restored_authority_completes_original_task()throws Exception{
  var initial=beginTransfer();var runtime=TransferWorkflowService.databaseBacked(realPorts());
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"TRANSFER_SUBMIT");grant(x,"CONTRACT_READ");return null;});}
  for(String code:List.of("TRANSFER_REVIEW","TRANSFER_ACCEPT","MATTER_CLASSIFY","MATTER_RECEIVE","CONTRACT_READ"))mutate("insert into identity.authority_grant(tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values(?,?,?,?,?,?,clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),reviewer,seed.appointment(),destination,code);
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->runtime.submit(x,actor(),initial,input()));}
  var review=currentTransferWorkflow();var caseManager=reviewerActor();try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->runtime.review(x,caseManager,review,new TransferConflictReviewInput("CLEAR","独立核对通过",true)));}
  var intake=currentTransferWorkflow();String task=scalar("select task_id from transfer.workflow where tenant_id=? and workflow_id=?",seed.tenant(),intake.id());String due=scalar("select due_at::text from transfer.workflow where tenant_id=? and workflow_id=?",seed.tenant(),intake.id());
  mutate("update identity.authority_grant set state='REVOKED',revision=revision+1,revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE' where tenant_id=? and grantee_appointment_id=? and authority_code='TRANSFER_ACCEPT'",seed.tenant(),reviewer);
  var input=new TransferIntakeInput("ACCEPT",null,"资料完整，确认接收",true);
  try(var c=database.apiConnection()){assertThrows(TransferWorkflowService.Blocked.class,()->inTransaction(c,Capability.COMMAND,x->runtime.intake(x,caseManager,intake,input)));}
  assertEquals(intake,currentTransferWorkflow());assertEquals("0",scalar("select count(*) from transfer.transfer_request where tenant_id=? and matter_id is not null",seed.tenant()));
  assertEquals(due,scalar("select due_at::text from transfer.workflow where tenant_id=? and workflow_id=?",seed.tenant(),intake.id()));
  assertEquals("OPEN",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),UUID.fromString(task)));
  mutate("insert into identity.authority_grant(tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values(?,?,?,?,?,'TRANSFER_ACCEPT',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),reviewer,seed.appointment(),destination);
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->runtime.intake(x,caseManager,intake,input));}
  assertEquals("DONE",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),UUID.fromString(task)));
  assertEquals("1",scalar("select count(*) from transfer.transfer_request where tenant_id=? and matter_id is not null",seed.tenant()));
 }
 TransferWorkflowPorts realPorts(){return new TransferWorkflowPorts(protectedBodies,cipher,null,t->destination);}
 @Test void real_transfer_authority_requires_explicit_sales_grant_and_rechecks_revocation()throws Exception{
  var workflow=beginTransfer();var ports=realPorts();
  try(var c=database.apiConnection()){assertThrows(TransferWorkflowService.Blocked.class,()->inTransaction(c,Capability.COMMAND,x->{ports.authorize(x,actor(),opportunity,"TRANSFER_SUBMIT",List.of(workflow));return null;}));}
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"TRANSFER_SUBMIT");return null;});}
  try(var c=database.apiConnection()){assertThrows(TransferWorkflowService.Blocked.class,()->inTransaction(c,Capability.COMMAND,x->{ports.authorize(x,actor(),opportunity,"TRANSFER_SUBMIT",List.of(workflow));return null;}));}
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"CONTRACT_READ");ports.authorize(x,actor(),opportunity,"TRANSFER_SUBMIT",List.of(workflow));return null;});}
  mutate("update identity.authority_grant set state='REVOKED',revision=revision+1,revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE' where tenant_id=? and grantee_appointment_id=? and authority_code='TRANSFER_SUBMIT'",seed.tenant(),seed.appointment());
  try(var c=database.apiConnection()){assertThrows(TransferWorkflowService.Blocked.class,()->inTransaction(c,Capability.COMMAND,x->{ports.authorize(x,actor(),opportunity,"TRANSFER_SUBMIT",List.of(workflow));return null;}));}
 }
 @Test void real_transfer_authority_selects_only_explicitly_qualified_destination_owner()throws Exception{
  beginTransfer();var ports=realPorts();UUID request=UUID.fromString(scalar("select transfer_request_id from transfer.transfer_request where tenant_id=?",seed.tenant()));
  try(var c=database.apiConnection()){assertNull(inTransaction(c,Capability.COMMAND,x->ports.owner(x,actor(),opportunity,request,"REVIEW_TRANSFER",seed.appointment(),null)));}
  for(String authority:List.of("TRANSFER_REVIEW","CONTRACT_READ","MATTER_RECEIVE"))mutate("insert into identity.authority_grant(tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values(?,?,?,?,?,?,clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),reviewer,seed.appointment(),destination,authority);
  try(var c=database.apiConnection()){assertEquals(reviewer,inTransaction(c,Capability.COMMAND,x->ports.owner(x,actor(),opportunity,request,"REVIEW_TRANSFER",seed.appointment(),null)));}
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{ports.validateRecipient(x,actor(),opportunity,request,new TransferClassificationInput("GENERAL",reviewer,"案管确认接收人"));return null;});}
  try(var c=database.apiConnection()){assertThrows(TransferWorkflowService.Blocked.class,()->inTransaction(c,Capability.COMMAND,x->{ports.validateRecipient(x,actor(),opportunity,request,new TransferClassificationInput("GENERAL",seed.appointment(),"不合格接收人"));return null;}));}
 }
 @Test void real_transfer_authority_closes_submit_review_accept_classify_chain()throws Exception{
  var initial=beginTransfer();var ports=realPorts();var service=TransferWorkflowService.databaseBacked(ports);
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"TRANSFER_SUBMIT");grant(x,"CONTRACT_READ");return null;});}
  for(String authority:List.of("TRANSFER_REVIEW","TRANSFER_ACCEPT","MATTER_CLASSIFY","MATTER_RECEIVE","CONTRACT_READ"))mutate("insert into identity.authority_grant(tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values(?,?,?,?,?,?,clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),reviewer,seed.appointment(),destination,authority);
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->service.submit(x,actor(),initial,input()));}
  var reviewerActor=reviewerActor();var review=currentTransferWorkflow();
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->service.review(x,reviewerActor,review,new TransferConflictReviewInput("CLEAR","独立审查完整",true)));}
  assertEquals("0",scalar("select count(*) from transfer.transfer_request where tenant_id=? and matter_id is not null",seed.tenant()));
  var intake=currentTransferWorkflow();try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->service.intake(x,reviewerActor,intake,new TransferIntakeInput("ACCEPT",null,"案管接收",true)));}
  var classification=currentTransferWorkflow();try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->service.classify(x,reviewerActor,classification,new TransferClassificationInput("GENERAL",reviewer,"确认承接")));}
  assertEquals("COMPLETE",scalar("select stage_code from transfer.workflow w where tenant_id=? and not exists(select 1 from transfer.workflow n where n.tenant_id=w.tenant_id and n.previous_workflow_id=w.workflow_id)",seed.tenant()));
 }
 Subject currentTransferWorkflow()throws Exception{return new Subject("transfer.workflow",UUID.fromString(scalar("select workflow_id from transfer.workflow w where tenant_id=? and not exists(select 1 from transfer.workflow n where n.tenant_id=w.tenant_id and n.previous_workflow_id=w.workflow_id)",seed.tenant())),0L,null);}
 @Test void real_transfer_authority_validates_current_submission_materials_and_customer()throws Exception{
  beginTransfer();var ports=realPorts();
  try(var c=database.apiConnection()){var basis=inTransaction(c,Capability.COMMAND,x->ports.validate(x,actor(),opportunity,anchor.id(),input()));assertEquals(64,basis.contractDigest().length());assertNotNull(basis.confirmationId());}
  var wrong=new TransferSubmissionInput(new TransferSubmissionInput.Material(material,"0".repeat(64)),input().signatureArchive(),"错误材料摘要",true,List.of());
  try(var c=database.apiConnection()){assertThrows(TransferWorkflowService.Blocked.class,()->inTransaction(c,Capability.COMMAND,x->ports.validate(x,actor(),opportunity,anchor.id(),wrong)));}
 }
}
