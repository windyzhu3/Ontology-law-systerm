package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.transfer.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.util.*;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
class R2TransferRecoveryIT extends R2TransferAuthorityIT {
 @Test void transfer_recovery_discovery_is_read_only_and_preserves_the_original_deadline()throws Exception{
  var initial=beginTransfer();try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"TRANSFER_SUBMIT");grant(x,"CONTRACT_READ");return null;});}
  var worker=service("CONTRACT_TASK_RECOVER");var service=TransferWorkflowService.databaseBacked(realPorts());
  String due=scalar("select due_at::text from transfer.workflow where tenant_id=? and workflow_id=?",seed.tenant(),initial.id());
  try(var c=database.apiConnection()){assertEquals(Boolean.FALSE,inTransaction(c,Capability.QUERY,x->service.recoveryNeeded(x,worker,initial)));}
  mutate("update identity.authority_grant set state='REVOKED',revision=revision+1,revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE' where tenant_id=? and grantee_appointment_id=? and authority_code='TRANSFER_SUBMIT'",seed.tenant(),seed.appointment());
  try(var c=database.apiConnection()){assertEquals(Boolean.TRUE,inTransaction(c,Capability.QUERY,x->service.recoveryNeeded(x,worker,initial)));}
  assertEquals("1",scalar("select count(*) from transfer.workflow where tenant_id=?",seed.tenant()));
  Subject waiting;try(var c=database.apiConnection()){waiting=inTransaction(c,Capability.COMMAND,x->service.recover(x,worker,initial));}
  try(var c=database.apiConnection()){assertEquals(Boolean.FALSE,inTransaction(c,Capability.QUERY,x->service.recoveryNeeded(x,worker,waiting)));}
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"TRANSFER_SUBMIT");return null;});}
  try(var c=database.apiConnection()){assertEquals(Boolean.TRUE,inTransaction(c,Capability.QUERY,x->service.recoveryNeeded(x,worker,waiting)));}
  Subject resumed;try(var c=database.apiConnection()){resumed=inTransaction(c,Capability.COMMAND,x->service.recover(x,worker,waiting));}
  assertEquals(due,scalar("select due_at::text from transfer.workflow where tenant_id=? and workflow_id=?",seed.tenant(),resumed.id()));
  assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='PREPARE_TRANSFER' and state='OPEN'",seed.tenant()));
 }
 @Test void transfer_recovery_prepares_once_from_real_execution_without_creating_a_case()throws Exception{
  prepareExecutedSource();try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"TRANSFER_SUBMIT");grant(x,"CONTRACT_READ");return null;});}
  var worker=service("CONTRACT_TASK_RECOVER");var coordinator=new R2TransferRecoveryService(realPorts(),cipher);
  io.github.windyzhu3.ontologylaw.contract.ContractWorkflowService.RecoveryCandidate candidate;
  try(var c=database.apiConnection()){var page=inTransaction(c,Capability.QUERY,x->coordinator.page(x,worker,20,null));assertEquals(1,page.candidates().size());candidate=page.candidates().getFirst();}
  assertEquals("0",scalar("select count(*) from transfer.transfer_request where tenant_id=?",seed.tenant()));
  var binding=new io.github.windyzhu3.ontologylaw.execution.CommandAuthorizationBinding.ContractRecovery(candidate.opportunity(),candidate.basis(),candidate.source(),candidate.workflow());
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->coordinator.reconcile(x,worker,binding));}
  assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='PREPARE_TRANSFER' and state='OPEN'",seed.tenant()));
  assertEquals("0",scalar("select count(*) from transfer.transfer_request where tenant_id=? and matter_id is not null",seed.tenant()));
  try(var c=database.apiConnection()){assertTrue(inTransaction(c,Capability.QUERY,x->coordinator.page(x,worker,20,null)).candidates().isEmpty());}
  try(var c=database.apiConnection()){assertThrows(io.github.windyzhu3.ontologylaw.transfer.TransferWorkflowService.Blocked.class,()->inTransaction(c,Capability.COMMAND,x->coordinator.reconcile(x,worker,binding)));}
 }
 @Test void transfer_recovery_command_discovers_commits_and_replays_the_original_receipt()throws Exception{
  prepareExecutedSource();try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"TRANSFER_SUBMIT");grant(x,"CONTRACT_READ");return null;});}
  var worker=service("CONTRACT_TASK_RECOVER");
  var db=new io.github.windyzhu3.ontologylaw.execution.RuntimeDatabase(){public java.sql.Connection open()throws java.sql.SQLException{return database.apiConnection();}public boolean healthy(){return true;}};
  io.github.windyzhu3.ontologylaw.lead.R1ServiceSourceBinding bindings;try(var c=database.apiConnection()){bindings=inTransaction(c,Capability.QUERY,x->io.github.windyzhu3.ontologylaw.lead.R1ServiceSourceBinding.validate(x,List.of(),policies));}
  var services=new R1ApiServices(db,policies,protection,bindings,"F11_AUTO",new byte[32],List.of(),cipher,null,protectedBodies,null,t->destination);
  Map<String,Object> candidate=null;String cursor=null;
  for(int page=0;page<20;page++){var result=services.contractCandidates(worker,20,cursor);for(var raw:(List<?>)result.get("candidates")){var row=(Map<String,Object>)raw;if("TRANSFER_HANDOFF".equals(row.get("sourceKind")))candidate=row;}cursor=(String)result.get("nextCursor");if(cursor==null)break;}
  assertNotNull(candidate);assertEquals("0",scalar("select count(*) from transfer.transfer_request where tenant_id=?",seed.tenant()));
  var payload=new LinkedHashMap<String,Object>(candidate);var key=UUID.fromString((String)payload.remove("idempotencyKey"));payload.remove("kind");
  var command=new io.github.windyzhu3.ontologylaw.execution.CommandEnvelope(io.github.windyzhu3.ontologylaw.execution.CommandEnvelope.Type.RECONCILE_CONTRACT_PREPARATION,key,UUID.randomUUID(),worker,payload);
  var unavailableRoute=new R1ApiServices(db,policies,protection,bindings,"F11_NO_ROUTE",new byte[32],List.of(),cipher,null,protectedBodies,null,t->null);
  var blocked=unavailableRoute.contractCommand(command);assertEquals("STALE_SUBJECT",blocked.errorCode());
  try(var c=database.apiConnection()){assertNull(inTransaction(c,Capability.QUERY,x->io.github.windyzhu3.ontologylaw.execution.CommandReceiptReader.databaseBacked().read(x,seed.tenant(),key)));}
  var first=services.contractCommand(command);assertEquals(200,first.status(),first.errorCode());assertEquals("TRANSFER_WORKFLOW",((Map<?,?>)first.body().get("resultFact")).get("factType"));
  assertEquals(first.body(),services.contractCommand(command).body());
  // SERVICE recovery uses exact command replay; the human receipt GET is deliberately not its transport.
  assertNotNull(R1WireModels.model(first.body(),io.github.windyzhu3.ontologylaw.api.adapter.generated.model.ContractPreparationReconcileReceiptV1.class));
  assertEquals("1",scalar("select count(*) from execution.domain_event where tenant_id=? and event_type='TransferWorkflowReconciledV1'",seed.tenant()));
  assertEquals("1",scalar("select count(*) from transfer.transfer_request where tenant_id=?",seed.tenant()));
  assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='PREPARE_TRANSFER' and state='OPEN'",seed.tenant()));
  assertEquals("0",scalar("select count(*) from transfer.transfer_request where tenant_id=? and matter_id is not null",seed.tenant()));
 }

 @Test void transfer_recovery_audit_failure_rolls_back_request_and_successor_task()throws Exception{
  prepareExecutedSource();try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"TRANSFER_SUBMIT");grant(x,"CONTRACT_READ");return null;});}
  var worker=service("CONTRACT_TASK_RECOVER");var coordinator=new R2TransferRecoveryService(realPorts(),cipher);
  io.github.windyzhu3.ontologylaw.contract.ContractWorkflowService.RecoveryCandidate source;try(var c=database.apiConnection()){source=inTransaction(c,Capability.QUERY,x->coordinator.page(x,worker,20,null)).candidates().getFirst();}
  var payload=new LinkedHashMap<String,Object>();payload.put("opportunityId",source.opportunity().id().toString());payload.put("expectedOpportunityRevision",source.opportunity().revision());payload.put("responsibilityBasis",Map.of("id",source.basis().id().toString(),"revision",source.basis().revision()));payload.put("sourceKind","TRANSFER_HANDOFF");payload.put("source",Map.of("id",source.source().id().toString(),"revision",0L));payload.put("expectedWorkflow",null);
  var command=new io.github.windyzhu3.ontologylaw.execution.CommandEnvelope(io.github.windyzhu3.ontologylaw.execution.CommandEnvelope.Type.RECONCILE_CONTRACT_PREPARATION,UUID.randomUUID(),UUID.randomUUID(),worker,payload);
  var runtime=new io.github.windyzhu3.ontologylaw.execution.CommandRuntime(List.of(new R2ContractRecoveryCommand(cipher,R2ContractServices.create(protectedBodies,cipher,null),coordinator)),io.github.windyzhu3.ontologylaw.identity.AuthorizationService.databaseBacked(),new io.github.windyzhu3.ontologylaw.audit.AuditAppender(){public void append(java.sql.Connection c,Entry entry)throws java.sql.SQLException{throw new java.sql.SQLException("injected audit failure");}},R2OpportunityCommandRuntime.authorization(io.github.windyzhu3.ontologylaw.lead.R1AuthorizationReaders.databaseBacked(policies)),R2OpportunityCommandRuntime.events(io.github.windyzhu3.ontologylaw.lead.R1EventReaders.databaseBacked(),cipher));
  try(var c=database.apiConnection()){assertThrows(java.sql.SQLException.class,()->runtime.execute(c,command));}
  assertEquals("0",scalar("select count(*) from transfer.transfer_request where tenant_id=?",seed.tenant()));assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='PREPARE_TRANSFER'",seed.tenant()));assertEquals("0",scalar("select count(*) from execution.domain_event where tenant_id=? and event_type='TransferWorkflowReconciledV1'",seed.tenant()));
 }

}
