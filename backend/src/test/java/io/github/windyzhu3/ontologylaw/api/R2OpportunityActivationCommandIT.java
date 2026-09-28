package io.github.windyzhu3.ontologylaw.api;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.lead.ContactFlowFixture;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.time.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
class R2OpportunityActivationCommandIT extends ContactFlowFixture {
 private final OpportunityProgressProtection cipher=OpportunityProgressProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES"));
 private Subject opportunity; private Actor actor; private CommandRuntime runtime; private CommandEnvelope command;
 private void setupActivation()throws Exception {
  setupContact();var result=execute(prepare(contact("CONNECTED_VALID")));assertEquals(CommandOutcome.Status.SUCCEEDED,result.status());
  try(var c=database.apiConnection()){opportunity=inTransaction(c,Capability.COMMAND,x->{grant(x,"SALES_OPPORTUNITY_OWNER");return EventOpportunityReader.databaseBacked().forContact(x,seed.tenant(),result.resultFact().id()).selector();});}
  actor=service("OPPORTUNITY_TASK_ACTIVATE");runtime=R2OpportunityCommandRuntime.fromSourcePolicy(policies,protection,cipher,null,"R2_ACTIVATION_IT");
  command=new CommandEnvelope(CommandEnvelope.Type.valueOf("ACTIVATE_INITIAL_OPPORTUNITY_TASK"),UUID.randomUUID(),UUID.randomUUID(),actor,Map.of("opportunityId",opportunity.id().toString(),"expectedOpportunityRevision",opportunity.revision()));
 }
 private CommandResult run(CommandEnvelope e)throws Exception{try(var c=database.apiConnection()){return runtime.execute(c,e);}}
 @Test void initial_activation_creates_exactly_one_task_receipt_audit_and_r2_event_and_replays()throws Exception {
  setupActivation();var first=assertInstanceOf(CommandOutcome.class,run(command));assertEquals(CommandOutcome.Status.SUCCEEDED,first.status());
  var before=counts();assertEquals(first,run(command));assertEquals(before,counts());
  assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and predecessor_task_occurrence_id is null",seed.tenant(),opportunity.id()));
  assertEquals("1",scalar("select count(*) from execution.domain_event d join execution.domain_event_outbox o using (tenant_id,domain_event_id) where d.tenant_id=? and d.command_id=? and d.event_type='OpportunityInitialTaskActivatedV1' and o.queue_owner='R2_PROJECTION'",seed.tenant(),command.commandId()));
  assertEquals("1",scalar("select count(*) from audit.audit_entry_classified_v where tenant_id=? and command_id=?",seed.tenant(),command.commandId()));
 }
 @Test void existing_cancelled_initial_responsibility_is_not_recreated_by_a_new_key()throws Exception {
  setupActivation();var first=assertInstanceOf(CommandOutcome.class,run(command));
  var originalDue=scalar("select original_sla_due_at from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),first.resultFact().id());
  mutate("update responsibility.task_occurrence set state='CANCELLED',cancelled_at=clock_timestamp(),cancellation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and task_occurrence_id=?",seed.tenant(),first.resultFact().id());
  var next=assertInstanceOf(CommandOutcome.class,run(new CommandEnvelope(command.type(),UUID.randomUUID(),UUID.randomUUID(),actor,command.payload())));
  assertEquals(CommandOutcome.Status.NO_CHANGE,next.status());assertEquals(first.resultFact().id(),next.resultFact().id());assertEquals(1L,next.resultFact().revision());
  assertEquals(originalDue,scalar("select original_sla_due_at from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),first.resultFact().id()));
  assertEquals("1",scalar("select count(*) from execution.domain_event where tenant_id=? and event_type='OpportunityInitialTaskActivatedV1'",seed.tenant()));
 }
 @Test void projection_failure_rolls_back_task_and_receipt_and_same_key_can_retry()throws Exception {
  setupActivation();var before=counts();try(var c=database.apiConnection()){assertThrows(IllegalStateException.class,()->runtime.executeProjected(c,command,(x,result)->{throw new IllegalStateException("fixture failure");}));}
  assertEquals(before,counts());assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=?",seed.tenant(),opportunity.id()));
  assertEquals(CommandOutcome.Status.SUCCEEDED,assertInstanceOf(CommandOutcome.class,run(command)).status());
 }
 @Test void revoked_service_or_human_owner_cannot_replay()throws Exception {
  for(boolean service:List.of(true,false)){setupActivation();run(command);
   mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and grantee_appointment_id=? and authority_code=?",seed.tenant(),service?actor.appointmentId():seed.appointment(),service?"OPPORTUNITY_TASK_ACTIVATE":"SALES_OPPORTUNITY_OWNER");
   assertEquals("NOT_AUTHORIZED",assertThrows(CommandHandler.Rejected.class,()->run(command)).code());
  }
 }
 @Test void closed_opportunity_and_unregistered_fields_cannot_occupy_a_command()throws Exception {
  setupActivation();var changed=new TreeMap<>((Map<String,Object>)command.payload());changed.put("businessCategory","EXECUTION");var before=counts();
  assertEquals("VALIDATION_FAILED",assertThrows(CommandHandler.Rejected.class,()->run(new CommandEnvelope(command.type(),command.commandId(),UUID.randomUUID(),actor,changed))).code());assertEquals(before,counts());
  mutate("update opportunity.opportunity set closed_at=clock_timestamp(),close_outcome_code='LOST',revision=revision+1 where tenant_id=? and opportunity_id=?",seed.tenant(),opportunity.id());
  assertThrows(CommandHandler.Rejected.class,()->run(command));assertEquals(before,counts());
 }

 @Test void initial_discovery_adapter_drives_command_and_candidate_disappears()throws Exception {
  setupActivation();var discovery=new R2OpportunityDiscoveryService(new byte[32]);R2OpportunityDiscoveryService.Candidate candidate;
  try(var c=database.apiConnection()){var page=discovery.list(c,actor,R2OpportunityDiscoveryService.Kind.INITIAL,20,null);assertEquals(200,page.status());candidate=page.page().candidates().getFirst();}
  var attempt=R2OpportunityCommandRuntime.activation(actor,candidate,UUID.randomUUID());assertEquals(candidate.commandId(),attempt.commandId());
  var result=run(attempt);assertEquals(result,run(attempt));
  try(var c=database.apiConnection()){assertTrue(discovery.list(c,actor,R2OpportunityDiscoveryService.Kind.INITIAL,20,null).page().candidates().isEmpty());}
  try(var c=database.apiConnection()){var card=new CurrentWorkCardDisclosureService(protection,policies,"R2_ACTIVATED_CARD_IT",cipher).read(c,seed.request().actor(),UUID.randomUUID(),null);assertEquals(200,card.status());assertEquals("PROGRESS_OPPORTUNITY",((Map<?,?>)card.body().get("currentCard")).get("taskType"));}
 }
 @Test void concurrent_different_keys_create_one_task_and_one_created_event()throws Exception {
  setupActivation();var other=new CommandEnvelope(command.type(),UUID.randomUUID(),UUID.randomUUID(),actor,command.payload());
  try(var workers=java.util.concurrent.Executors.newFixedThreadPool(2)){
   var results=workers.invokeAll(List.<java.util.concurrent.Callable<CommandResult>>of(()->run(command),()->run(other)));
   var first=assertInstanceOf(CommandOutcome.class,results.get(0).get());var second=assertInstanceOf(CommandOutcome.class,results.get(1).get());
   assertEquals(Set.of(CommandOutcome.Status.SUCCEEDED,CommandOutcome.Status.NO_CHANGE),Set.of(first.status(),second.status()));assertEquals(first.resultFact(),second.resultFact());
  }
  assertEquals("1",scalar("select count(*) from execution.domain_event where tenant_id=? and event_type='OpportunityInitialTaskActivatedV1'",seed.tenant()));
 }
 @Test void old_key_replays_after_initial_completion_and_opportunity_revision_advance()throws Exception {
  setupActivation();var original=assertInstanceOf(CommandOutcome.class,run(command));
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
   var now=TaskFactory.databaseBacked().now(x);R2OpportunityProgressServices.create(cipher).record(x,seed.request().actor(),opportunity,original.resultFact(),new OpportunityProgressInput("MEETING","确认后续沟通",now,now.plusSeconds(86400)),ZoneId.of("Asia/Shanghai"),now);return null;
  });}
  mutate("update opportunity.opportunity set closed_at=clock_timestamp(),close_outcome_code='LOST',revision=revision+1 where tenant_id=? and opportunity_id=?",seed.tenant(),opportunity.id());
  var before=counts();assertEquals(original,run(command));assertEquals(before,counts());
 }
 @Test void same_key_different_exact_revision_conflicts()throws Exception {
  setupActivation();run(command);var changed=new TreeMap<>((Map<String,Object>)command.payload());changed.put("expectedOpportunityRevision",1L);
  assertInstanceOf(CommandResult.Conflict.class,run(new CommandEnvelope(command.type(),command.commandId(),UUID.randomUUID(),actor,changed)));
 }
 @Test void wrong_service_authority_human_and_source_denies_are_rejected()throws Exception {
  setupActivation();assertThrows(IllegalArgumentException.class,()->new CommandEnvelope(command.type(),UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),command.payload()));
  var wrong=service("OPPORTUNITY_TASK_RECOVER");assertThrows(CommandHandler.Rejected.class,()->run(new CommandEnvelope(command.type(),UUID.randomUUID(),UUID.randomUUID(),wrong,command.payload())));
  Subject source;try(var c=database.apiConnection()){source=inTransaction(c,Capability.QUERY,x->io.github.windyzhu3.ontologylaw.lead.R1EventReaders.databaseBacked().lead(x,seed.tenant(),current.subject().id()));}
  mutate("insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values (?,?,?,?,'OPPORTUNITY_TASK_ACTIVATE','DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),'lead.lead',?,?)",seed.tenant(),UUID.randomUUID(),actor.principalId(),seed.appointment(),source.id(),source.revision());
  var before=counts();assertThrows(CommandHandler.Rejected.class,()->run(command));assertEquals(before,counts());
 }
 @Test void final_authorization_rejects_a_new_task_object_deny_and_rolls_creation_back()throws Exception {
  setupActivation();var delegate=new R2OpportunityActivationCommand(policies,protection,cipher);
  var wrapper=new CommandHandler(){
   public CommandEnvelope.Type type(){return delegate.type();}
   public Context resolve(java.sql.Connection c,CommandEnvelope e)throws java.sql.SQLException{return delegate.resolve(c,e);}
   public void lockRoots(java.sql.Connection c,CommandEnvelope e,Context ctx)throws java.sql.SQLException{delegate.lockRoots(c,e,ctx);}
   public void recoveryEligibility(java.sql.Connection c,CommandEnvelope e,Context ctx)throws java.sql.SQLException{delegate.recoveryEligibility(c,e,ctx);}
   public void validateBeforeWork(java.sql.Connection c,CommandEnvelope e,Context ctx)throws java.sql.SQLException{delegate.validateBeforeWork(c,e,ctx);}
   public Result execute(java.sql.Connection c,CommandEnvelope e,Context ctx)throws java.sql.SQLException{
    var result=delegate.execute(c,e,ctx);
    io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql(c,"insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values (?,?,?,?,'OPPORTUNITY_TASK_ACTIVATE','DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),'responsibility.task_occurrence',?,?)",seed.tenant(),UUID.randomUUID(),actor.principalId(),seed.appointment(),result.fact().id(),result.fact().revision());return result;
   }
   public void validateBeforeCommit(java.sql.Connection c,CommandEnvelope e,Context ctx,Result result)throws java.sql.SQLException{delegate.validateBeforeCommit(c,e,ctx,result);}
  };
  runtime=new CommandRuntime(List.of(wrapper),io.github.windyzhu3.ontologylaw.identity.AuthorizationService.databaseBacked(),io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked("R2_FINAL_DENY_IT"),R2OpportunityCommandRuntime.authorization(io.github.windyzhu3.ontologylaw.lead.R1AuthorizationReaders.databaseBacked(policies)),R2OpportunityCommandRuntime.events(io.github.windyzhu3.ontologylaw.lead.R1EventReaders.databaseBacked(),cipher));
  var result=assertInstanceOf(CommandOutcome.class,run(command));assertEquals(CommandOutcome.Status.REJECTED,result.status());assertEquals("NOT_AUTHORIZED",result.rejectionCode());
  assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=?",seed.tenant(),opportunity.id()));assertEquals("0",scalar("select count(*) from execution.domain_event where tenant_id=? and command_id=?",seed.tenant(),command.commandId()));
 }

 @Test void replay_denies_the_exact_historical_result_revision_for_success_and_no_change()throws Exception {
  for(boolean noChange:List.of(false,true))for(boolean ownerDeny:List.of(false,true)){
   setupActivation();var first=assertInstanceOf(CommandOutcome.class,run(command));CommandEnvelope replay=command;long historic=0;
   if(noChange){
    // Legacy all-state fixture: initial responsibility already WAITING before this command; no production wait entrypoint is bypassed by the command under test.
    mutate("update responsibility.task_occurrence set state='WAITING',revision=revision+1 where tenant_id=? and task_occurrence_id=? and state='OPEN'",seed.tenant(),first.resultFact().id());
    replay=new CommandEnvelope(command.type(),UUID.randomUUID(),UUID.randomUUID(),actor,command.payload());assertEquals(CommandOutcome.Status.NO_CHANGE,assertInstanceOf(CommandOutcome.class,run(replay)).status());historic=1;
   }
   mutate("update responsibility.task_occurrence set state='CANCELLED',cancelled_at=clock_timestamp(),cancellation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and task_occurrence_id=?",seed.tenant(),first.resultFact().id());
   mutate("insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values (?,?,?,?,?,'DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),'responsibility.task_occurrence',?,?)",seed.tenant(),UUID.randomUUID(),ownerDeny?seed.principal():actor.principalId(),seed.appointment(),ownerDeny?"SALES_OPPORTUNITY_OWNER":"OPPORTUNITY_TASK_ACTIVATE",first.resultFact().id(),historic);
   var attempt=replay;assertEquals("NOT_AUTHORIZED",assertThrows(CommandHandler.Rejected.class,()->run(attempt)).code());
  }
 }
}

