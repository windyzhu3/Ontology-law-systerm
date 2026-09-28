package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.contract.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
class R2ContractExecutionHandoffIT extends R2ManualSignatureWorkflowIT {
 @Override Map<String,Object> doc(){var value=super.doc();value.put("unknownOpponent",false);return value;}
 boolean receiptRequired;
 @org.junit.jupiter.api.BeforeEach void resetReceiptGate(){receiptRequired=false;}
 Map<String,Object> executionVerificationPayload()throws Exception {
  var ctx=context();var w=(Map<?,?>)((Map<?,?>)ctx.get("execution")).get("workflow");
  return payload(ctx,Map.of("expectedExecutionWorkflow",w.get("selector"),"approvedConditionsChecked",true,"archiveAndConditionsComplete",true));
 }
 @Test void execution_conditions_nonprepay_creates_execution_once_without_receipt()throws Exception {
  var p=executionSource();recoverExecution(service("CONTRACT_TASK_RECOVER"),p);var input=executionVerificationPayload();
  var verified=command("VERIFY_CONTRACT_EXECUTION_CONDITIONS",input);
  assertEquals("contract.execution_verification",verified.type());
  assertEquals("1",scalar("select count(*) from contract.contract_execution where tenant_id=?",seed.tenant()));
  assertEquals("1",scalar("select count(*) from contract.contract where tenant_id=? and contract_execution_id is not null and deal_activated_at is not null",seed.tenant()));
  assertEquals("1",scalar("select count(*) from contract.execution_workflow where tenant_id=? and stage_code='READY_TRANSFER'",seed.tenant()));
  assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='CHECK_CONTRACT_EXECUTION' and state='DONE'",seed.tenant()));
  assertEquals("0",scalar("select count(*) from contract.payment_confirmation where tenant_id=?",seed.tenant()));
  assertThrows(ContractWorkflowService.Blocked.class,()->command("VERIFY_CONTRACT_EXECUTION_CONDITIONS",input));
 }
 @Test void execution_conditions_waiting_receipt_cannot_be_overridden_by_human_checkboxes()throws Exception {
  receiptRequired=true;var p=executionSource();recoverExecution(service("CONTRACT_TASK_RECOVER"),p);
  assertThrows(ContractWorkflowService.Blocked.class,()->command("VERIFY_CONTRACT_EXECUTION_CONDITIONS",executionVerificationPayload()));
  assertEquals("0",scalar("select count(*) from contract.execution_verification where tenant_id=?",seed.tenant()));
  assertEquals("0",scalar("select count(*) from contract.contract_execution where tenant_id=?",seed.tenant()));
 }
 @Test void execution_conditions_requires_human_confirmation_and_rolls_back_all_on_audit_failure()throws Exception {
  var p=executionSource();recoverExecution(service("CONTRACT_TASK_RECOVER"),p);var input=executionVerificationPayload();
  var invalid=new LinkedHashMap<>(input);var values=new LinkedHashMap<>((Map<String,Object>)input.get("values"));values.put("approvedConditionsChecked",false);invalid.put("values",values);
  assertEquals("VALIDATION_FAILED",assertThrows(ContractWorkflowService.Blocked.class,()->command("VERIFY_CONTRACT_EXECUTION_CONDITIONS",invalid)).code());
  try(var c=database.apiConnection()){assertThrows(IllegalStateException.class,()->inTransaction(c,Capability.COMMAND,x->{service().execute(x,"VERIFY_CONTRACT_EXECUTION_CONDITIONS",actor(),input);throw new IllegalStateException("audit append failed");}));}
  assertEquals("0",scalar("select count(*) from contract.execution_verification where tenant_id=?",seed.tenant()));
  assertEquals("0",scalar("select count(*) from contract.contract_execution where tenant_id=?",seed.tenant()));
  assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='CHECK_CONTRACT_EXECUTION' and state='OPEN'",seed.tenant()));
  command("VERIFY_CONTRACT_EXECUTION_CONDITIONS",input);
 }
 @Test void execution_conditions_runtime_replays_one_execution_and_one_verified_event()throws Exception {
  var p=executionSource();recoverExecution(service("CONTRACT_TASK_RECOVER"),p);var input=executionVerificationPayload();
  for(String authority:List.of("CONTRACT_EXECUTION_VERIFY","CONTRACT_READ","CONTRACT_APPROVE","CONTRACT_REVIEW","CONTRACT_SIGNATURE_VERIFY"))mutate("insert into identity.authority_grant(tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values(?,?,?,?,?,?,clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),seed.appointment(),seed.appointment(),seed.org(),authority);
  try(var c=database.apiConnection()){
   UUID currentTask=UUID.fromString(scalar("select task_id from contract.execution_workflow where tenant_id=?",seed.tenant()));
   var authority=io.github.windyzhu3.ontologylaw.identity.AuthorizationService.databaseBacked();
   var sources=new CurrentWorkCardSources(authority,protection,policies,cipher);
   var read=new io.github.windyzhu3.ontologylaw.execution.SensitiveReadRuntime(authority,io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked("F10_CURRENT_CARD_IT"));
   var card=read.read(c,actor(),UUID.randomUUID(),null,(connection,now)->sources.read(connection,actor(),now,currentTask));
   assertEquals("CHECK_CONTRACT_EXECUTION",((Map<?,?>)card.body().get("currentCard")).get("taskType"));
  }
  var actual=R2ContractServices.create(protectedBodies,cipher,null);
  var runtime=R2OpportunityCommandRuntime.fromSourcePolicy(policies,protection,cipher,null,"F10_HUMAN_EXECUTION_IT",actual);
  var envelope=new io.github.windyzhu3.ontologylaw.execution.CommandEnvelope(io.github.windyzhu3.ontologylaw.execution.CommandEnvelope.Type.valueOf("VERIFY_CONTRACT_EXECUTION_CONDITIONS"),UUID.randomUUID(),UUID.randomUUID(),actor(),input);
  try(var c=database.apiConnection()){
   var result=assertInstanceOf(io.github.windyzhu3.ontologylaw.execution.CommandOutcome.class,runtime.execute(c,envelope));
   assertEquals(io.github.windyzhu3.ontologylaw.execution.CommandOutcome.Status.SUCCEEDED,result.status(),result.rejectionCode());
   assertEquals(result,runtime.execute(c,envelope));
  }
  assertEquals("1",scalar("select count(*) from contract.contract_execution where tenant_id=?",seed.tenant()));
  assertEquals("1",scalar("select count(*) from execution.domain_event where tenant_id=? and command_id=? and event_type='ContractExecutionConditionsVerifiedV1'",seed.tenant(),envelope.commandId()));
 }
 @Test void execution_conditions_full_receipt_still_requires_human_then_activates_once()throws Exception {
  receiptRequired=true;var p=executionSource();var first=recoverExecution(service("CONTRACT_TASK_RECOVER"),p);
  confirmedReceipt(1,4000);confirmedReceipt(2,6000);p.put("expectedWorkflow",Map.of("id",first.id().toString(),"revision",0L));recoverExecution(service("CONTRACT_TASK_RECOVER"),p);
  assertEquals("0",scalar("select count(*) from contract.contract_execution where tenant_id=?",seed.tenant()));
  var input=executionVerificationPayload();command("VERIFY_CONTRACT_EXECUTION_CONDITIONS",input);
  assertEquals("1",scalar("select count(*) from contract.contract_execution where tenant_id=?",seed.tenant()));
  assertEquals("2",scalar("select count(*) from contract.payment_confirmation where tenant_id=?",seed.tenant()));
  assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='CHECK_CONTRACT_EXECUTION' and state='DONE'",seed.tenant()));
  assertThrows(ContractWorkflowService.Blocked.class,()->command("VERIFY_CONTRACT_EXECUTION_CONDITIONS",input));
 }
 @Test void execution_conditions_denied_authority_cannot_activate_or_complete_current_task()throws Exception {
  var p=executionSource();recoverExecution(service("CONTRACT_TASK_RECOVER"),p);var input=executionVerificationPayload();permitted=false;
  assertEquals("NOT_AUTHORIZED",assertThrows(ContractWorkflowService.Blocked.class,()->command("VERIFY_CONTRACT_EXECUTION_CONDITIONS",input)).code());
  assertEquals("0",scalar("select count(*) from contract.contract_execution where tenant_id=?",seed.tenant()));
  assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='CHECK_CONTRACT_EXECUTION' and state='OPEN'",seed.tenant()));
 }
 @Test void execution_conditions_nonprepay_does_not_turn_independent_receipt_attribution_into_a_transfer_gate()throws Exception {
  var p=executionSource();confirmedReceipt(1,100,"USD");
  recoverExecution(service("CONTRACT_TASK_RECOVER"),p);command("VERIFY_CONTRACT_EXECUTION_CONDITIONS",executionVerificationPayload());
  assertEquals("1",scalar("select count(*) from contract.contract_execution where tenant_id=?",seed.tenant()));
  assertEquals("1",scalar("select count(*) from contract.payment_confirmation where tenant_id=? and currency_code='USD'",seed.tenant()));
  assertEquals("1",scalar("select count(*) from contract.execution_workflow where tenant_id=? and stage_code='READY_TRANSFER'",seed.tenant()));
 }
 // Persisted-receipt fixture only: verifies the Contract Owner consumer, not finance command acceptance.
 void confirmedReceipt(int sequence,long amount)throws Exception {confirmedReceipt(sequence,amount,"CNY");}
 void confirmedReceipt(int sequence,long amount,String currency)throws Exception {
  UUID revision=UUID.fromString(scalar("select current_revision_id from contract.contract where tenant_id=?",seed.tenant()));
  mutate("insert into contract.payment_confirmation(tenant_id,payment_confirmation_id,contract_id,contract_revision_id,confirmation_no,confirmation_type,amount_minor,currency_code,provider_account_code,provider_transaction_key_hmac,evidence_submission_id,attribution_digest,effective_at,confirmed_at,recorded_by_appointment_id) select tenant_id,uuidv7(),?,?,?,'RECEIPT',?,?,'F10_CONSUMER_FIXTURE',sha256(convert_to(cast(? as text),'UTF8')),evidence_submission_id,decode(repeat('32',32),'hex'),clock_timestamp(),clock_timestamp(),? from opportunity.material_version where tenant_id=? and material_version_id=?",anchor.id(),revision,sequence,amount,currency,UUID.randomUUID().toString(),seed.appointment(),seed.tenant(),material);
 }
 @Test void execution_handoff_partial_keeps_wait_and_full_receipt_reopens_same_task_once()throws Exception {
  receiptRequired=true;var p=executionSource();var worker=service("CONTRACT_TASK_RECOVER");var first=recoverExecution(worker,p);
  String task=scalar("select task_id from contract.execution_workflow where tenant_id=?",seed.tenant());
  String due=scalar("select due_at::text from contract.execution_workflow where tenant_id=?",seed.tenant());
  p.put("expectedWorkflow",Map.of("id",first.id().toString(),"revision",0L));confirmedReceipt(1,4000);
  assertThrows(ContractWorkflowService.Blocked.class,()->recoverExecution(worker,p));
  assertEquals("WAITING",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),UUID.fromString(task)));
  confirmedReceipt(2,6000);var next=recoverExecution(worker,p);
  assertEquals(task,scalar("select task_id from contract.execution_workflow where tenant_id=? and execution_workflow_id=?",seed.tenant(),next.id()));
  assertEquals(due,scalar("select due_at::text from contract.execution_workflow where tenant_id=? and execution_workflow_id=?",seed.tenant(),next.id()));
  assertEquals("OPEN",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),UUID.fromString(task)));
  assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='CHECK_CONTRACT_EXECUTION'",seed.tenant()));
  assertThrows(ContractWorkflowService.Blocked.class,()->recoverExecution(worker,p));
  assertEquals("0",scalar("select count(*) from contract.execution_verification where tenant_id=?",seed.tenant()));
 }
 @Override ContractVersionInput input(long revision,UUID previous){var v=super.input(revision,previous);return new ContractVersionInput(v.contractId(),v.revision(),v.predecessorId(),v.source(),v.commercial(),v.document(),v.signing(),new ContractVersionInput.PaymentGate(receiptRequired,receiptRequired?10000L:null));}
 @Test void execution_handoff_prepay_waits_without_fabricating_human_verification()throws Exception {
  receiptRequired=true;var p=executionSource();recoverExecution(service("CONTRACT_TASK_RECOVER"),p);
  assertEquals("WAIT_RECEIPT",scalar("select stage_code from contract.execution_workflow where tenant_id=?",seed.tenant()));
  assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='CHECK_CONTRACT_EXECUTION' and state='WAITING'",seed.tenant()));
  assertEquals("1",scalar("select count(*) from responsibility.wait_receipt where tenant_id=? and wait_contract_code='R2_CONTRACT_RECEIPT_WAIT_V1' and resume_due_at is null",seed.tenant()));
  assertEquals("0",scalar("select count(*) from contract.execution_verification where tenant_id=?",seed.tenant()));
  assertEquals("0",scalar("select count(*) from contract.contract_execution where tenant_id=?",seed.tenant()));
 }
 Map<String,Object> executionSource()throws Exception {
  start();arrange();submit();verifyAll();command("ARCHIVE_CONTRACT_SIGNATURE",signaturePayload(Map.of("materialVersionId",material.toString(),"materialSha256",bodySha,"reason","执行交接测试","archiveComplete",true)));
  var p=new LinkedHashMap<String,Object>();p.put("opportunityId",opportunity.id().toString());p.put("expectedOpportunityRevision",opportunity.revision());p.put("responsibilityBasis",Map.of("id",opportunity.id().toString(),"revision",opportunity.revision()));p.put("sourceKind","EXECUTION_HANDOFF");p.put("source",Map.of("id",scalar("select signature_handoff_id from contract.signature_handoff where tenant_id=?",seed.tenant()),"revision",0L));p.put("expectedWorkflow",null);return p;
 }
 Subject recoverExecution(Actor actor,Map<String,Object> p)throws Exception {try(var c=database.apiConnection()){return inTransaction(c,Capability.COMMAND,x->service().reconcile(x,actor,p));}}
 @Test void execution_handoff_creates_one_actual_sales_responsibility()throws Exception {
  var p=executionSource();var worker=service("CONTRACT_TASK_RECOVER");var result=recoverExecution(worker,p);
  assertEquals("contract.execution_workflow",result.type());
  assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='CHECK_CONTRACT_EXECUTION' and state='OPEN'",seed.tenant()));
  assertThrows(ContractWorkflowService.Blocked.class,()->recoverExecution(worker,p));
  assertEquals("1",scalar("select count(*) from contract.execution_workflow where tenant_id=?",seed.tenant()));
  assertEquals("0",scalar("select count(*) from contract.contract_execution where tenant_id=?",seed.tenant()));
  var execution=(Map<?,?>)context().get("execution");assertNotNull(execution);
  var workflow=(Map<?,?>)execution.get("workflow");assertEquals("CHECK_CONDITIONS",workflow.get("stage"));
  assertNotNull(workflow.get("task"));
  assertEquals(result.id().toString(),((Map<?,?>)workflow.get("selector")).get("id"));
 }
 @Test void execution_handoff_without_authority_recovers_same_deadline()throws Exception {
  var p=executionSource();var worker=service("CONTRACT_TASK_RECOVER");unavailableAuthorities.add("CONTRACT_EXECUTION_VERIFY");var prior=recoverExecution(worker,p);
  assertEquals("OWNER_EXCEPTION",scalar("select stage_code from contract.execution_workflow where tenant_id=?",seed.tenant()));
  String due=scalar("select due_at::text from contract.execution_workflow where tenant_id=?",seed.tenant());
  assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='CHECK_CONTRACT_EXECUTION'",seed.tenant()));
  unavailableAuthorities.remove("CONTRACT_EXECUTION_VERIFY");p.put("expectedWorkflow",Map.of("id",prior.id().toString(),"revision",0L));recoverExecution(worker,p);
  assertEquals("2",scalar("select count(*) from contract.execution_workflow where tenant_id=?",seed.tenant()));
  assertEquals(due,scalar("select due_at::text from contract.execution_workflow where tenant_id=? and previous_workflow_id is not null",seed.tenant()));
 }

 @Test void execution_handoff_runtime_has_one_receipt_and_event_on_replay()throws Exception {
  var p=executionSource();var worker=service("CONTRACT_TASK_RECOVER");
  for(String authority:List.of("CONTRACT_EXECUTION_VERIFY","CONTRACT_READ"))mutate("insert into identity.authority_grant(tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values(?,?,?,?,?,?,clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),seed.appointment(),seed.appointment(),seed.org(),authority);
  var actual=R2ContractServices.create(protectedBodies,cipher,null);
  var runtime=R2OpportunityCommandRuntime.fromSourcePolicy(policies,protection,cipher,null,"F10_EXECUTION_IT",actual);
  var envelope=new io.github.windyzhu3.ontologylaw.execution.CommandEnvelope(io.github.windyzhu3.ontologylaw.execution.CommandEnvelope.Type.RECONCILE_CONTRACT_PREPARATION,UUID.randomUUID(),UUID.randomUUID(),worker,p);
  try(var c=database.apiConnection()){
   var result=assertInstanceOf(io.github.windyzhu3.ontologylaw.execution.CommandOutcome.class,runtime.execute(c,envelope));
   assertEquals(io.github.windyzhu3.ontologylaw.execution.CommandOutcome.Status.SUCCEEDED,result.status(),result.rejectionCode());
   assertEquals(result,runtime.execute(c,envelope));
  }
  assertEquals("1",scalar("select count(*) from contract.execution_workflow where tenant_id=?",seed.tenant()));
  assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='CHECK_CONTRACT_EXECUTION'",seed.tenant()));
  assertEquals("1",scalar("select count(*) from execution.domain_event where tenant_id=? and command_id=? and event_type='ContractExecutionReconciledV1'",seed.tenant(),envelope.commandId()));
  assertEquals("1",scalar("select count(*) from execution.command_receipt r join execution.command_execution_slot s on s.tenant_id=r.tenant_id and s.command_execution_slot_id=r.command_execution_slot_id where r.tenant_id=? and s.command_id=?",seed.tenant(),envelope.commandId()));
 }

 @Test void execution_handoff_read_revocation_cancels_then_restores_original_deadline()throws Exception {
  var p=executionSource();var worker=service("CONTRACT_TASK_RECOVER");var first=recoverExecution(worker,p);
  String due=scalar("select due_at::text from contract.execution_workflow where tenant_id=?",seed.tenant());
  unavailableAuthorities.add("CONTRACT_READ");p.put("expectedWorkflow",Map.of("id",first.id().toString(),"revision",0L));
  var exception=recoverExecution(worker,p);
  assertEquals("OWNER_EXCEPTION",scalar("select stage_code from contract.execution_workflow where tenant_id=? and execution_workflow_id=?",seed.tenant(),exception.id()));
  assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='CHECK_CONTRACT_EXECUTION' and state in ('OPEN','WAITING')",seed.tenant()));
  unavailableAuthorities.remove("CONTRACT_READ");p.put("expectedWorkflow",Map.of("id",exception.id().toString(),"revision",0L));var restored=recoverExecution(worker,p);
  assertEquals(due,scalar("select due_at::text from contract.execution_workflow where tenant_id=? and execution_workflow_id=?",seed.tenant(),restored.id()));
  assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='CHECK_CONTRACT_EXECUTION' and state='OPEN'",seed.tenant()));
 }

 @Test void execution_handoff_audit_failure_rolls_back_task_and_consumption()throws Exception {
  var p=executionSource();var worker=service("CONTRACT_TASK_RECOVER");
  try(var c=database.apiConnection()){assertThrows(IllegalStateException.class,()->inTransaction(c,Capability.COMMAND,x->{service().reconcile(x,worker,p);throw new IllegalStateException("audit append failed");}));}
  assertEquals("0",scalar("select count(*) from contract.execution_workflow where tenant_id=?",seed.tenant()));
  assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='CHECK_CONTRACT_EXECUTION'",seed.tenant()));
  recoverExecution(worker,p);
  assertEquals("1",scalar("select count(*) from contract.execution_workflow where tenant_id=?",seed.tenant()));
 }

 @Test void execution_handoff_concurrent_consumers_observe_root_lock_and_create_once()throws Exception {
  var p=executionSource();var worker=service("CONTRACT_TASK_RECOVER");
  var held=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var started=new java.util.concurrent.CountDownLatch(1);
  var pid=new java.util.concurrent.atomic.AtomicInteger();
  try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
   var first=pool.submit(()->{try(var c=database.apiConnection()){return inTransaction(c,Capability.COMMAND,x->{var fact=service().reconcile(x,worker,p);held.countDown();try{if(!release.await(20,java.util.concurrent.TimeUnit.SECONDS))throw new java.sql.SQLException("Handoff barrier timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.sql.SQLException(e);}return fact;});}});
   java.util.concurrent.Future<String> second;
   try{
    assertTrue(held.await(20,java.util.concurrent.TimeUnit.SECONDS));
    second=pool.submit(()->{try(var c=database.apiConnection()){try(var q=c.prepareStatement("select pg_backend_pid()");var r=q.executeQuery()){r.next();pid.set(r.getInt(1));}started.countDown();return assertThrows(ContractWorkflowService.Blocked.class,()->inTransaction(c,Capability.COMMAND,x->service().reconcile(x,worker,p))).code();}});
    assertTrue(started.await(20,java.util.concurrent.TimeUnit.SECONDS));
    long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);boolean blocked=false;
    while(System.nanoTime()<deadline){if(!"0".equals(scalar("select cardinality(pg_blocking_pids(?))",pid.get()))){blocked=true;break;}Thread.sleep(20);}
    assertTrue(blocked,"Second recovery must actually wait for the same opportunity root");
   }finally{release.countDown();}
   first.get(20,java.util.concurrent.TimeUnit.SECONDS);assertEquals("STALE_SUBJECT",second.get(20,java.util.concurrent.TimeUnit.SECONDS));
  }
  assertEquals("1",scalar("select count(*) from contract.execution_workflow where tenant_id=?",seed.tenant()));
  assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='CHECK_CONTRACT_EXECUTION'",seed.tenant()));
 }
}
