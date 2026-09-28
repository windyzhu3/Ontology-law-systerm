package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.transfer.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import io.github.windyzhu3.ontologylaw.contract.*;
import java.sql.*;import java.time.*;import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
class R2TransferWorkflowIT extends R2TransferRequestIT {
 boolean ownerAvailable=true;UUID reviewer;
 TransferWorkflowService workflows(){return TransferWorkflowService.databaseBacked(new TransferWorkflowService.Ports(){
  public void lockAuthority(Connection c,Actor a){}
  public void authorize(Connection c,Actor a,Subject opportunity,String authority,List<Subject> facts){if(!authorized)throw new TransferWorkflowService.Blocked("NOT_AUTHORIZED");}
  public Instant now(Connection c)throws SQLException{return tasks.now(c);}
  public Instant due(Instant trigger){return new ContractWorkflowPorts(cipher,null).signatureDue(trigger,ZoneId.of("Asia/Shanghai"));}
  public UUID owner(Connection c,Actor a,Subject opportunity,UUID request,String stage,UUID submitter,UUID incumbent){return ownerAvailable?(Set.of("PREPARE","SUPPLEMENT").contains(stage)?seed.appointment():reviewer):null;}
  public TransferWorkflowService.Task task(Connection c,UUID t,UUID id)throws SQLException{var task=tasks.read(c,t,id);return new TransferWorkflowService.Task(task.selector(),task.owner(),task.state());}
  public TransferWorkflowService.Task create(Connection c,UUID t,Subject o,UUID owner,String stage,Instant now,Instant due)throws SQLException{var task=tasks.createTransferTask(c,t,TaskFactory.Type.valueOf(switch(stage){case "PREPARE"->"PREPARE_TRANSFER";case "SUPPLEMENT"->"SUPPLEMENT_TRANSFER";case "INTAKE"->"ACCEPT_TRANSFER";case "CLASSIFY"->"CLASSIFY_MATTER";default->"REVIEW_TRANSFER";}),owner,o,ZoneId.of("Asia/Shanghai"),now,due);return new TransferWorkflowService.Task(task.selector(),task.owner(),task.state());}
  public void complete(Connection c,UUID t,TransferWorkflowService.Task task,Subject result,Instant now)throws SQLException{tasks.complete(c,t,tasks.read(c,t,task.selector().id()),result,now);}
  public void cancel(Connection c,UUID t,TransferWorkflowService.Task task,Instant now)throws SQLException{tasks.cancelTransferTask(c,t,tasks.read(c,t,task.selector().id()),now);}
  public TransferWorkflowService.Basis validate(Connection c,Actor a,Subject o,UUID contract,TransferSubmissionInput input)throws SQLException{
   var source=ContractTransferSourceReader.databaseBacked().find(c,a.tenantId(),contract).orElseThrow();
   for(var m:List.of(input.clientIdentity(),input.signatureArchive()))if(!new ContractWorkflowPorts(cipher,null).documentUsable(c,a.tenantId(),m.versionId(),m.sha256()))throw new TransferWorkflowService.Blocked("STALE_EVIDENCE");
   try(var p=c.prepareStatement("select c.customer_requirement_confirmation_id,o.legal_need_digest from opportunity.customer_requirement_confirmation c join opportunity.opportunity o on o.tenant_id=c.tenant_id and o.opportunity_id=c.opportunity_id where c.tenant_id=? and c.opportunity_id=? and not exists(select 1 from opportunity.customer_requirement_confirmation n where n.tenant_id=c.tenant_id and n.previous_confirmation_id=c.customer_requirement_confirmation_id)")){p.setObject(1,a.tenantId());p.setObject(2,o.id());try(var r=p.executeQuery()){assertTrue(r.next());return new TransferWorkflowService.Basis(source.revisionId(),r.getObject(1,UUID.class),source.activationDigest(),HexFormat.of().formatHex(r.getBytes(2)));}}
  }
  public void validateCorrectionMaterial(Connection c,Actor a,Subject o,TransferSubmissionInput.Material m)throws SQLException{
   if(!new ContractWorkflowPorts(cipher,null).documentUsable(c,a.tenantId(),m.versionId(),m.sha256()))throw new TransferWorkflowService.Blocked("STALE_EVIDENCE");
   try(var p=c.prepareStatement("select opportunity_id from opportunity.material_version where tenant_id=? and material_version_id=?")){p.setObject(1,a.tenantId());p.setObject(2,m.versionId());try(var r=p.executeQuery()){if(!r.next()||!o.id().equals(r.getObject(1,UUID.class)))throw new TransferWorkflowService.Blocked("STALE_EVIDENCE");}}
  }
  public TransferWorkflowService.Draft confirmDraft(Connection c,Actor a,TransferWorkflowService.Task task,String digest,Instant now)throws SQLException{
   var owner=ActionDraftService.databaseBacked();var full=tasks.read(c,a.tenantId(),task.selector().id());var values=Map.<String,Object>of("submissionDigest",digest);var saved=owner.save(c,a.tenantId(),full,null,values,a.appointmentId(),now).draft();owner.confirm(c,a.tenantId(),full,new ActionDraftService.Confirmation(saved.selector().id(),saved.selector().revision(),saved.digest()),values,a.appointmentId(),now);return new TransferWorkflowService.Draft(saved.selector().id(),HexFormat.of().formatHex(Base64.getUrlDecoder().decode(saved.digest())));
  }
  public Subject review(Connection c,Actor a,Subject workflow,TransferConflictReviewInput input,TransferWorkflowService.Draft draft)throws SQLException{return TransferConflictReviewRepository.databaseBacked((x,t,o)->true,ContractCanonicalJson::encode,(t,o,id,body)->protectedBodies.seal(t,o,id,ContractProtection.Kind.TRANSFER,body),ContractConflictDecisions.databaseBackedForTransfer()::block).record(c,a.tenantId(),workflow.id(),a.appointmentId(),input,draft);}
  public void validateAcceptance(Connection c,Actor actor,Subject workflow)throws SQLException{
   try(var p=c.prepareStatement("select w.submission_id,r.scope_digest,r.corpus_digest from transfer.workflow w join transfer.review r on r.tenant_id=w.tenant_id and r.review_id=w.review_id where w.tenant_id=? and w.workflow_id=?")){p.setObject(1,actor.tenantId());p.setObject(2,workflow.id());try(var r=p.executeQuery()){if(!r.next())throw new TransferWorkflowService.Blocked("STALE_SUBJECT");var scan=TransferConflictReviewRepository.databaseBacked((x,t,o)->true,ContractCanonicalJson::encode).inspect(c,actor.tenantId(),r.getObject(1,UUID.class));if(!scan.permittedOutcomes().contains("CLEAR")||!scan.scopeDigest().equals(HexFormat.of().formatHex(r.getBytes(2)))||!scan.corpusDigest().equals(HexFormat.of().formatHex(r.getBytes(3))))throw new TransferWorkflowService.Blocked("STALE_REVIEW");}}
  }
  public Subject intakeDecision(Connection c,Actor a,TransferWorkflowService.Task task,Subject snapshot,TransferIntakeInput input,Instant now)throws SQLException{return TransferIntakeDecisions.databaseBacked().record(c,a.tenantId(),task.selector().id(),a.appointmentId(),snapshot,input.decision(),input.explanation());}
  public void validateRecipient(Connection c,Actor actor,Subject opportunity,UUID request,TransferClassificationInput input)throws SQLException{
   try(var p=c.prepareStatement("select 1 from identity.appointment a join transfer.transfer_request r on r.tenant_id=a.tenant_id and r.to_organization_unit_id=a.organization_unit_id where a.tenant_id=? and a.appointment_id=? and r.transfer_request_id=? and a.state='ACTIVE'")){p.setObject(1,actor.tenantId());p.setObject(2,input.recipient());p.setObject(3,request);try(var r=p.executeQuery()){if(!r.next())throw new TransferWorkflowService.Blocked("RECIPIENT_UNAVAILABLE");}}
  }
  public String encode(Map<String,Object> value){return ContractCanonicalJson.encode(value);}
  public byte[] seal(UUID t,UUID o,UUID id,String clear){return protectedBodies.seal(t,o,id,ContractProtection.Kind.TRANSFER,clear);}
 });}
 Subject beginTransfer()throws Exception{
  ownerAvailable=true;prepareExecutedSource();var request=prepare();UUID principal=UUID.randomUUID();reviewer=UUID.randomUUID();
  mutate("insert into identity.principal(tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) values(?,?,'HUMAN','FIXTURE',?,'Independent reviewer','ACTIVE',clock_timestamp())",seed.tenant(),principal,ContractCanonicalJson.digest(principal.toString()));
  mutate("insert into identity.appointment(tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values(?,?,?,?,'REVIEWER',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),reviewer,principal,destination);
  var worker=service("TRANSFER_TASK_RECOVER");try(var c=database.apiConnection()){return inTransaction(c,Capability.COMMAND,x->workflows().start(x,worker,request));}
 }
 TransferSubmissionInput input(){var proof=new TransferSubmissionInput.Material(material,bodySha);return new TransferSubmissionInput(proof,proof,"完整交接说明",true,List.of());}
 Subject submitTransfer(Subject workflow)throws Exception{try(var c=database.apiConnection()){return inTransaction(c,Capability.COMMAND,x->workflows().submit(x,actor(),workflow,input()));}}
 @Test void transfer_submission_completes_sales_and_starts_distinct_review_without_case_or_fake_clear()throws Exception{
  var workflow=beginTransfer();var result=submitTransfer(workflow);assertEquals("transfer.submission",result.type());
  assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='PREPARE_TRANSFER' and state='DONE'",seed.tenant()));
  assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='REVIEW_TRANSFER' and state='OPEN' and owner_appointment_id=?",seed.tenant(),reviewer));
  assertEquals("0",scalar("select count(*) from conflict.conflict_review where tenant_id=? and review_type_code='PRE_TRANSFER'",seed.tenant()));
  assertEquals("0",scalar("select count(*) from transfer.transfer_request where tenant_id=? and matter_id is not null",seed.tenant()));
  assertThrows(TransferWorkflowService.Blocked.class,()->submitTransfer(workflow));
 }
 @Test void transfer_submission_audit_failure_rolls_back_draft_fact_and_successor()throws Exception{
  var workflow=beginTransfer();try(var c=database.apiConnection()){assertThrows(IllegalStateException.class,()->inTransaction(c,Capability.COMMAND,x->{workflows().submit(x,actor(),workflow,input());throw new IllegalStateException("audit failure");}));}
  assertEquals("0",scalar("select count(*) from transfer.submission where tenant_id=?",seed.tenant()));
  assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='PREPARE_TRANSFER' and state='OPEN'",seed.tenant()));
  submitTransfer(workflow);
 }
 @Test void transfer_submission_self_review_candidate_becomes_exception_then_recovers_original_deadline()throws Exception{
  var workflow=beginTransfer();UUID independent=reviewer;reviewer=seed.appointment();String due=scalar("select due_at::text from transfer.workflow where tenant_id=?",seed.tenant());submitTransfer(workflow);
  var latest=new Subject("transfer.workflow",UUID.fromString(scalar("select workflow_id from transfer.workflow where tenant_id=? and stage_code='OWNER_EXCEPTION'",seed.tenant())),0L,null);
  assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='REVIEW_TRANSFER'",seed.tenant()));
  reviewer=independent;var worker=service("TRANSFER_TASK_RECOVER");try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->workflows().recover(x,worker,latest));}
  assertEquals(due,scalar("select w.due_at::text from transfer.workflow w where tenant_id=? and not exists(select 1 from transfer.workflow n where n.tenant_id=w.tenant_id and n.previous_workflow_id=w.workflow_id)",seed.tenant()));
  assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='REVIEW_TRANSFER' and state='OPEN' and owner_appointment_id=?",seed.tenant(),independent));
 }
 @Test void transfer_submission_database_rejects_reassigning_review_to_the_submitter()throws Exception{
  var initial=beginTransfer();submitTransfer(initial);
  UUID previous=UUID.fromString(scalar("select workflow_id from transfer.workflow where tenant_id=? and stage_code='REVIEW_TRANSFER'",seed.tenant()));
  UUID task=UUID.fromString(scalar("select task_id from transfer.workflow where tenant_id=? and workflow_id=?",seed.tenant(),previous));
  try(var c=database.apiConnection()){assertThrows(SQLException.class,()->inTransaction(c,Capability.COMMAND,x->{
   var original=tasks.read(x,seed.tenant(),task);var now=tasks.now(x);tasks.cancelTransferTask(x,seed.tenant(),original,now);
   Instant due;try(var p=x.prepareStatement("select due_at from transfer.workflow where tenant_id=? and workflow_id=?")){p.setObject(1,seed.tenant());p.setObject(2,previous);try(var r=p.executeQuery()){r.next();due=r.getTimestamp(1).toInstant();}}
   var wrong=tasks.createTransferTask(x,seed.tenant(),TaskFactory.Type.REVIEW_TRANSFER,seed.appointment(),opportunity,ZoneId.of("Asia/Shanghai"),now,due);
   try(var p=x.prepareStatement("insert into transfer.workflow(tenant_id,workflow_id,transfer_request_id,opportunity_id,previous_workflow_id,stage_code,target_stage_code,owner_appointment_id,task_id,submission_id,recorded_by,due_at,created_at) select tenant_id,uuidv7(),transfer_request_id,opportunity_id,workflow_id,'REVIEW_TRANSFER','REVIEW_TRANSFER',?,?,submission_id,?,due_at,clock_timestamp() from transfer.workflow where tenant_id=? and workflow_id=?")){p.setObject(1,seed.appointment());p.setObject(2,wrong.selector().id());p.setObject(3,seed.appointment());p.setObject(4,seed.tenant());p.setObject(5,previous);p.executeUpdate();}
   return null;
  }));}
 }
 @Test void transfer_submission_database_rejects_successor_while_previous_task_is_open()throws Exception{
  var initial=beginTransfer();submitTransfer(initial);
  UUID previous=UUID.fromString(scalar("select workflow_id from transfer.workflow where tenant_id=? and stage_code='REVIEW_TRANSFER'",seed.tenant()));
  UUID task=UUID.fromString(scalar("select task_id from transfer.workflow where tenant_id=? and workflow_id=?",seed.tenant(),previous));
  try(var c=database.apiConnection()){assertThrows(SQLException.class,()->inTransaction(c,Capability.COMMAND,x->{
   var original=tasks.read(x,seed.tenant(),task);var now=tasks.now(x);
   Instant due;try(var p=x.prepareStatement("select due_at from transfer.workflow where tenant_id=? and workflow_id=?")){p.setObject(1,seed.tenant());p.setObject(2,previous);try(var r=p.executeQuery()){r.next();due=r.getTimestamp(1).toInstant();}}
   var wrong=tasks.createTransferTask(x,seed.tenant(),TaskFactory.Type.REVIEW_TRANSFER,reviewer,opportunity,ZoneId.of("Asia/Shanghai"),now,due);
   try(var p=x.prepareStatement("insert into transfer.workflow(tenant_id,workflow_id,transfer_request_id,opportunity_id,previous_workflow_id,stage_code,target_stage_code,owner_appointment_id,task_id,submission_id,recorded_by,due_at,created_at) select tenant_id,uuidv7(),transfer_request_id,opportunity_id,workflow_id,'REVIEW_TRANSFER','REVIEW_TRANSFER',?,?,submission_id,?,due_at,clock_timestamp() from transfer.workflow where tenant_id=? and workflow_id=?")){p.setObject(1,reviewer);p.setObject(2,wrong.selector().id());p.setObject(3,seed.appointment());p.setObject(4,seed.tenant());p.setObject(5,previous);p.executeUpdate();}
   return null;
  }));}
 }
}
