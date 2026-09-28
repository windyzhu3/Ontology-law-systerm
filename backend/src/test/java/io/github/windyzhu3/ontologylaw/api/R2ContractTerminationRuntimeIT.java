package io.github.windyzhu3.ontologylaw.api;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import java.util.*;import java.sql.*;
import org.junit.jupiter.api.Test;
/** Actual authority, runtime, receipt and both ledger projections, with synthetic signed facts. */
class R2ContractTerminationRuntimeIT extends R2SalesTerminationIT {
 void authorize(Actor who,String code)throws Exception{mutate("insert into identity.authority_grant(tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values(?,?,?,?,?,?,clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),who.appointmentId(),seed.appointment(),seed.org(),code);}
 CommandOutcome run(CommandEnvelope envelope)throws Exception{var actual=R2ContractServices.create(protectedBodies,cipher,null);try(var c=database.apiConnection()){return assertInstanceOf(CommandOutcome.class,R2OpportunityCommandRuntime.fromSourcePolicy(policies,protection,cipher,null,"F06_CONTRACT_RUNTIME_IT",actual).execute(c,envelope));}}

 @Test void termination_runtime_separates_supervisor_and_sales_recovers_receipts_and_updates_ledgers()throws Exception {
  start();arrange();submit();var reviewer=supervisor();for(var who:List.of(actor(),reviewer))authorize(who,"CONTRACT_READ");authorize(actor(),"OPPORTUNITY_CLOSE");authorize(reviewer,"CONTRACT_TERMINATION_REVIEW");
  var request=new CommandEnvelope(CommandEnvelope.Type.REQUEST_CONTRACT_TERMINATION_REVIEW,UUID.randomUUID(),UUID.randomUUID(),actor(),dispositionPayload(Map.of()));var requested=run(request);assertEquals(CommandOutcome.Status.SUCCEEDED,requested.status(),requested.rejectionCode());assertEquals(requested,run(request));
  var current=(Map<?,?>)context().get("termination");assertEquals(reviewer.appointmentId().toString(),current.get("ownerAppointmentId"));
  var audit=AuditAppender.databaseBacked("F06_CONTRACT_READ_IT");var reader=new R2ContractReadService(R2ContractServices.create(protectedBodies,cipher,null),audit);
  try(var c=database.apiConnection()){var task=reader.task(c,reviewer,UUID.fromString((String)((Map<?,?>)current.get("task")).get("id")));var ctx=(Map<?,?>)task.get("context");assertNull(((Map<?,?>)ctx.get("signature")).get("allowedActions"));assertEquals(List.of("RECORD_CONTRACT_TERMINATION_REVIEW"),ctx.get("allowedActions"));var rows=(List<?>)reader.ledger(c,reviewer).get("items");assertEquals("终止签约待主管核对",((Map<?,?>)rows.getFirst()).get("stateLabel"));assertEquals(true,((Map<?,?>)rows.getFirst()).get("canHandle"));}
  var decision=new CommandEnvelope(CommandEnvelope.Type.RECORD_CONTRACT_TERMINATION_REVIEW,UUID.randomUUID(),UUID.randomUUID(),reviewer,dispositionPayload(Map.of("decision","STOP")));var done=run(decision);assertEquals(CommandOutcome.Status.SUCCEEDED,done.status(),done.rejectionCode());assertEquals(done,run(decision));
  try(var c=database.apiConnection()){assertEquals(200,new CommandReceiptRecoveryService(policies,protection,null,"F06_REVIEW_RECEIPT_IT").read(c,reviewer,decision.commandId(),UUID.randomUUID()).status());var rows=(List<?>)reader.ledger(c,reviewer).get("items");assertEquals("本次销售办理已停止",((Map<?,?>)rows.getFirst()).get("stateLabel"));assertEquals(false,((Map<?,?>)rows.getFirst()).get("canHandle"));}
  var opportunityReader=new R2OpportunityLedgerReadService(new byte[32],protection,cipher,audit);try(var c=database.apiConnection()){var row=opportunityReader.read(c,actor(),"detail",opportunity.id(),30,null,null,null);assertEquals("本次销售办理已停止",row.get("nextActionLabel"));assertEquals(false,row.get("canHandle"));assertFalse(row.containsKey("task"));}
  var contractSelector=(Map<?,?>)((Map<?,?>)context().get("contract")).get("selector");
  deny(new io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject("contract.contract",UUID.fromString((String)contractSelector.get("id")),((Number)contractSelector.get("revision")).longValue(),null),"CONTRACT_READ");
  try(var c=database.apiConnection()){var row=opportunityReader.read(c,actor(),"detail",opportunity.id(),30,null,null,null);assertFalse(row.containsKey("nextActionLabel"),"Contract result must require its own authority");assertEquals(false,row.get("canHandle"));assertFalse(row.containsKey("task"));}
  mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and grantee_appointment_id=? and authority_code='CONTRACT_TERMINATION_REVIEW'",seed.tenant(),reviewer.appointmentId());try(var c=database.apiConnection()){assertEquals(403,new CommandReceiptRecoveryService(policies,protection,null,"F06_REVOKED_RECEIPT_IT").read(c,reviewer,decision.commandId(),UUID.randomUUID()).status());}
 }
 @Test void termination_runtime_continues_exact_original_responsibility_and_supports_another_review()throws Exception {
  start();arrange();submit();var reviewer=supervisor();for(var who:List.of(actor(),reviewer))authorize(who,"CONTRACT_READ");authorize(actor(),"OPPORTUNITY_CLOSE");authorize(actor(),"CONTRACT_SIGNATURE_VERIFY");authorize(reviewer,"CONTRACT_TERMINATION_REVIEW");
  var requested=run(new CommandEnvelope(CommandEnvelope.Type.REQUEST_CONTRACT_TERMINATION_REVIEW,UUID.randomUUID(),UUID.randomUUID(),actor(),dispositionPayload(Map.of())));assertEquals(CommandOutcome.Status.SUCCEEDED,requested.status(),requested.rejectionCode());
  var decision=new CommandEnvelope(CommandEnvelope.Type.RECORD_CONTRACT_TERMINATION_REVIEW,UUID.randomUUID(),UUID.randomUUID(),reviewer,dispositionPayload(Map.of("decision","CONTINUE")));var continued=run(decision);assertEquals(CommandOutcome.Status.SUCCEEDED,continued.status(),continued.rejectionCode());assertEquals(continued,run(decision));
  assertEquals("1",scalar("select count(*) from responsibility.contract_task_resumption where tenant_id=?",seed.tenant()));assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='VERIFY_CONTRACT_SIGNATURE' and state='OPEN'",seed.tenant()));
  var again=run(new CommandEnvelope(CommandEnvelope.Type.REQUEST_CONTRACT_TERMINATION_REVIEW,UUID.randomUUID(),UUID.randomUUID(),actor(),dispositionPayload(Map.of())));assertEquals(CommandOutcome.Status.SUCCEEDED,again.status(),again.rejectionCode());assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='VERIFY_CONTRACT_SIGNATURE' and state='OPEN'",seed.tenant()));
 }
 @Test void termination_runtime_worker_recovers_missing_supervisor_with_stable_command_replay()throws Exception {
  start();arrange();submit();authorize(actor(),"CONTRACT_READ");authorize(actor(),"OPPORTUNITY_CLOSE");var requested=run(new CommandEnvelope(CommandEnvelope.Type.REQUEST_CONTRACT_TERMINATION_REVIEW,UUID.randomUUID(),UUID.randomUUID(),actor(),dispositionPayload(Map.of())));assertEquals(CommandOutcome.Status.SUCCEEDED,requested.status(),requested.rejectionCode());var due=((Map<?,?>)context().get("termination")).get("dueAt");
  var reviewer=supervisor();authorize(reviewer,"CONTRACT_READ");authorize(reviewer,"CONTRACT_TERMINATION_REVIEW");var worker=service("CONTRACT_TASK_RECOVER");var actual=R2ContractServices.create(protectedBodies,cipher,null);Map<String,Object> body;
  try(var c=database.apiConnection()){body=inTransaction(c,Capability.QUERY,x->{var page=actual.recoveryPage(x,worker,100,null);assertEquals(1,page.candidates().size());var candidate=page.candidates().getFirst();return Map.of("opportunityId",opportunity.id().toString(),"expectedOpportunityRevision",opportunity.revision(),"responsibilityBasis",Map.of("id",candidate.basis().id().toString(),"revision",candidate.basis().revision()),"sourceKind","TERMINATION_REVIEW","source",Map.of("id",candidate.source().id().toString(),"revision",0L),"expectedWorkflow",Map.of("id",candidate.workflow().id().toString(),"revision",0L));});}
  var envelope=new CommandEnvelope(CommandEnvelope.Type.RECONCILE_CONTRACT_PREPARATION,UUID.randomUUID(),UUID.randomUUID(),worker,body);var recovered=run(envelope);assertEquals(CommandOutcome.Status.SUCCEEDED,recovered.status(),recovered.rejectionCode());assertEquals(recovered,run(envelope));assertEquals(due,((Map<?,?>)context().get("termination")).get("dueAt"));
  // Internal maintenance intentionally has no public recovery metadata: worker replays the original command key.
  assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='REVIEW_CONTRACT_TERMINATION' and state='OPEN'",seed.tenant()));
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"STOP","CONTINUE"})
 void termination_read_revocation_rejects_a_supervisor_old_page(String decision)throws Exception {
  start();arrange();submit();var reviewer=supervisor();for(var who:List.of(actor(),reviewer))authorize(who,"CONTRACT_READ");authorize(actor(),"OPPORTUNITY_CLOSE");authorize(actor(),"CONTRACT_SIGNATURE_VERIFY");authorize(reviewer,"CONTRACT_TERMINATION_REVIEW");
  run(new CommandEnvelope(CommandEnvelope.Type.REQUEST_CONTRACT_TERMINATION_REVIEW,UUID.randomUUID(),UUID.randomUUID(),actor(),dispositionPayload(Map.of())));
  var envelope=new CommandEnvelope(CommandEnvelope.Type.RECORD_CONTRACT_TERMINATION_REVIEW,UUID.randomUUID(),UUID.randomUUID(),reviewer,dispositionPayload(Map.of("decision",decision)));
  mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and grantee_appointment_id=? and authority_code='CONTRACT_READ'",seed.tenant(),reviewer.appointmentId());
  assertEquals("NOT_AUTHORIZED",assertThrows(CommandHandler.Rejected.class,()->run(envelope)).code());assertEquals("REVIEW_REQUIRED",((Map<?,?>)context().get("termination")).get("state"));assertEquals("1",scalar("select count(*) from contract.negotiation_disposition where tenant_id=?",seed.tenant()));
 }
 @Test void termination_read_revocation_blocks_replay_and_original_receipt()throws Exception {
  start();arrange();submit();var reviewer=supervisor();for(var who:List.of(actor(),reviewer))authorize(who,"CONTRACT_READ");authorize(actor(),"OPPORTUNITY_CLOSE");authorize(reviewer,"CONTRACT_TERMINATION_REVIEW");
  run(new CommandEnvelope(CommandEnvelope.Type.REQUEST_CONTRACT_TERMINATION_REVIEW,UUID.randomUUID(),UUID.randomUUID(),actor(),dispositionPayload(Map.of())));
  var envelope=new CommandEnvelope(CommandEnvelope.Type.RECORD_CONTRACT_TERMINATION_REVIEW,UUID.randomUUID(),UUID.randomUUID(),reviewer,dispositionPayload(Map.of("decision","STOP")));assertEquals(CommandOutcome.Status.SUCCEEDED,run(envelope).status());
  mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and grantee_appointment_id=? and authority_code='CONTRACT_READ'",seed.tenant(),reviewer.appointmentId());
  assertEquals("NOT_AUTHORIZED",assertThrows(CommandHandler.Rejected.class,()->run(envelope)).code());try(var c=database.apiConnection()){assertEquals(403,new CommandReceiptRecoveryService(policies,protection,null,"F06_READ_REVOKED_IT").read(c,reviewer,envelope.commandId(),UUID.randomUUID()).status());}
 }
}
