package io.github.windyzhu3.ontologylaw.api;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.lead.ContactFlowFixture;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationIdentityReader;
import java.util.*;
import java.time.*;
import javax.crypto.spec.SecretKeySpec;
/** Migrated PostgreSQL formal commands, durable audit, replay and direct current authorization. */
class R2OpportunityHandoffConcurrencyIT extends ContactFlowFixture {
 private Subject opportunity;private Actor observer;private CommandEnvelope observation;
 private void setup(boolean healthy)throws Exception{
  setupContact();var contact=execute(prepare(contact("CONNECTED_VALID")));try(var c=database.apiConnection()){opportunity=inTransaction(c,Capability.COMMAND,x->{grant(x,"OPPORTUNITY_OWNER_EXCEPTION_READ");grant(x,"OPPORTUNITY_OWNER_EXCEPTION_RESOLVE");if(healthy)grant(x,"SALES_OPPORTUNITY_OWNER");return EventOpportunityReader.databaseBacked().forContact(x,seed.tenant(),contact.resultFact().id()).selector();});}
  observer=service("OPPORTUNITY_OWNER_EXCEPTION_DISCOVER");runtime=R2OpportunityCommandRuntime.fromSourcePolicy(policies,protection,OpportunityProgressProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES")),null,"T01_COMMAND_IT");
  observation=new CommandEnvelope(CommandEnvelope.Type.OBSERVE_OPPORTUNITY_OWNER_EXCEPTION,UUID.randomUUID(),UUID.randomUUID(),observer,Map.of("opportunityId",opportunity.id().toString(),"expectedOpportunityRevision",opportunity.revision()));
 }
 private OpportunityOwnerExceptionService.Snapshot snapshot(Subject ref)throws Exception{try(var c=database.apiConnection()){return inTransaction(c,Capability.QUERY,x->R2OpportunityOwnerExceptionAssembly.service().read(x,seed.tenant(),ref));}}
 private CommandEnvelope decision(CommandEnvelope.Type type,OpportunityOwnerExceptionService.Snapshot s,UUID receiver){var p=new TreeMap<String,Object>();p.put("opportunityId",s.opportunity().id().toString());p.put("expectedOpportunityRevision",s.opportunity().revision());p.put("exceptionId",s.selector().id().toString());p.put("expectedExceptionRevision",s.selector().revision());p.put("expectedBasis",CommandScope.selector(s.responsibility().basis()));p.put("expectedTask",CommandScope.selector(s.task()));p.put("expectedWait",CommandScope.selector(s.waitReceipt()));p.put("reason","Current supervisor reviewed responsibility");if(type==CommandEnvelope.Type.TRANSFER_OPPORTUNITY_RESPONSIBILITY)p.put("receiverAppointmentId",receiver.toString());else p.put("reviewDueAt",Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString());return new CommandEnvelope(type,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),p);}
 private UUID receiver()throws Exception{UUID principal=UUID.randomUUID(),appointment=UUID.randomUUID();mutate("insert into identity.principal (tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) values (?,?,'HUMAN','FIXTURE',?,'Qualified receiver','ACTIVE',clock_timestamp())",seed.tenant(),principal,CanonicalJson.digest(principal.toString()));mutate("insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'OWNER',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),appointment,principal,seed.org());mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,'SALES_OPPORTUNITY_OWNER',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),appointment,seed.appointment(),seed.org());return appointment;}

 private final OpportunityProgressProtection cipher=OpportunityProgressProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES"));
 private Subject task, priorProgress, wait;
 private Instant due;
 private OpportunityOwnerExceptionService.Snapshot arrange(boolean waiting)throws Exception {
  setup(true);
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
   var first=TaskFactory.databaseBacked().createInitialOpportunity(x,seed.tenant(),seed.appointment(),opportunity,ZoneId.of("Asia/Shanghai"),businessAt);
   task=first.selector();
   if(waiting){due=businessAt.plusSeconds(86400);var result=R2OpportunityProgressServices.create(cipher).record(x,seed.request().actor(),opportunity,task,new OpportunityProgressInput("PHONE_CONNECTED","Existing confirmed progress",businessAt,due),ZoneId.of("Asia/Shanghai"),businessAt.plusSeconds(60));task=result.nextTask();priorProgress=result.progress();wait=EventResponsibilityReader.databaseBacked().latestWait(x,seed.tenant(),task.id()).selector();}
   return null;
  });}
  mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and grantee_appointment_id=? and authority_code='SALES_OPPORTUNITY_OWNER'",seed.tenant(),seed.appointment());
  var exception=snapshot(execute(observation).resultFact());
  // Restoring authority does not itself consume the persisted exception cycle.
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"SALES_OPPORTUNITY_OWNER");return null;});}
  return exception;
 }
 private CommandEnvelope progressCommand()throws Exception {
  var now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  var values=Map.<String,Object>of("progressTypeCode","MEETING","progressSummary","Original owner confirmed next meeting","occurredAt",now.minusSeconds(2).toString(),"nextCheckAt",now.plusSeconds(86400).toString());
  assertEquals(CommandOutcome.Status.SUCCEEDED,execute(new CommandEnvelope(CommandEnvelope.Type.SAVE_ACTION_DRAFT,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),Map.of("actionCode","RECORD_OPPORTUNITY_PROGRESS","schemaVersion",1,"values",values),null,new CommandEnvelope.DraftPrecondition(task.id(),null,"*"))).status());
  try(var c=database.apiConnection()){return inTransaction(c,Capability.QUERY,x->{var draft=ActionDraftService.databaseBacked().read(x,seed.tenant(),task.id());var currentTask=TaskFactory.databaseBacked().read(x,seed.tenant(),task.id());var body=new TreeMap<String,Object>(values);body.put("draftId",draft.selector().id().toString());body.put("expectedDraftRevision",draft.selector().revision());body.put("draftDigest",draft.digest());return new CommandEnvelope(CommandEnvelope.Type.RECORD_OPPORTUNITY_PROGRESS,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),body,new CommandEnvelope.TaskPrecondition(task.id(),R1ResourceTags.task(seed.request().actor(),currentTask.selector(),currentTask.state(),opportunity)));});}
 }
 private String attempt(CommandEnvelope command,java.util.concurrent.CyclicBarrier start)throws Exception {
  start.await(20,java.util.concurrent.TimeUnit.SECONDS);
  try(var c=database.apiConnection()){try{var result=assertInstanceOf(CommandOutcome.class,runtime.execute(c,command));return result.status()==CommandOutcome.Status.SUCCEEDED?"SUCCEEDED":result.rejectionCode();}catch(CommandHandler.Rejected rejected){return rejected.code();}}
 }
 private List<String> race(CommandEnvelope transfer,CommandEnvelope other)throws Exception {
  var start=new java.util.concurrent.CyclicBarrier(2);
  try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
   var a=pool.submit(()->attempt(transfer,start));var b=pool.submit(()->attempt(other,start));
   var results=List.of(a.get(60,java.util.concurrent.TimeUnit.SECONDS),b.get(60,java.util.concurrent.TimeUnit.SECONDS));
   assertEquals(1,results.stream().filter("SUCCEEDED"::equals).count(),results.toString());
   assertTrue(results.stream().filter(x->!"SUCCEEDED".equals(x)).allMatch(x->Set.of("STALE_TASK","STALE_SUBJECT","NOT_AUTHORIZED","TASK_NOT_OPEN","FORBIDDEN").contains(x)),results.toString());
   assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and state in ('OPEN','WAITING')",seed.tenant(),opportunity.id()));
   return results;
  }
 }
 @Test void transfer_races_original_owner_progress_without_double_card_or_fabricated_progress()throws Exception {
  var exception=arrange(false);var progress=progressCommand();var transfer=decision(CommandEnvelope.Type.TRANSFER_OPPORTUNITY_RESPONSIBILITY,exception,receiver());
  var outcomes=race(transfer,progress);boolean transferred="SUCCEEDED".equals(outcomes.get(0));
  assertEquals(transferred?"1":"0",scalar("select count(*) from opportunity.responsibility_handoff where tenant_id=?",seed.tenant()));
  assertEquals(transferred?"0":"1",scalar("select count(*) from opportunity.opportunity_progress where tenant_id=?",seed.tenant()));
  assertEquals(transferred?"CANCELLED":"DONE",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),task.id()));
 }
 @Test void transfer_races_due_recovery_with_original_expectation_and_one_live_task()throws Exception {
  var exception=arrange(true);var actor=service("OPPORTUNITY_TASK_RECOVER");
  var recovery=new CommandEnvelope(CommandEnvelope.Type.REOPEN_DUE_OPPORTUNITY_TASKS,UUID.randomUUID(),UUID.randomUUID(),actor,Map.of("opportunityId",opportunity.id().toString(),"expectedOpportunityRevision",opportunity.revision(),"taskId",task.id().toString(),"expectedTaskRevision",task.revision(),"waitReceiptId",wait.id().toString(),"waitReceiptHash",wait.hash(),"progressId",priorProgress.id().toString(),"progressHash",priorProgress.hash(),"dueCutoff",due.toString()));
  var transfer=decision(CommandEnvelope.Type.TRANSFER_OPPORTUNITY_RESPONSIBILITY,exception,receiver());
  var outcomes=race(transfer,recovery);boolean transferred="SUCCEEDED".equals(outcomes.get(0));
  assertEquals(transferred?"1":"0",scalar("select count(*) from opportunity.responsibility_handoff where tenant_id=?",seed.tenant()));
  assertEquals("1",scalar("select count(*) from opportunity.opportunity_progress where tenant_id=?",seed.tenant()));
  assertEquals(transferred?"CANCELLED":"OPEN",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),task.id()));
 }
}
