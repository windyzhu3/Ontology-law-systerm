package io.github.windyzhu3.ontologylaw.api;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.util.*;
import java.time.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
class QuoteHandoffIT extends R2QuoteWorkflowIT {
 @ParameterizedTest @ValueSource(booleans={false,true})
 void quote_handoff_preserves_phase_wait_and_sla_and_requires_fresh_quote(boolean waiting)throws Exception {
  Instant due=null;
  if(waiting){var evidence=delivered();due=now().plusSeconds(3);quoteCommand("RECORD_QUOTE_RESPONSE",Map.of("kind","NOT_ACCEPTED","statement","later","occurredAt",now().toString(),"nextCheckAt",due.toString(),"evidence",R2CustomerRequirementsServices.selector(evidence)));}
  else{setup(true,true);confirmed();policy("SELF_AUTHORIZED",List.of(seed.appointment()));quoteCommand("FORM_QUOTE",commercial());}
  try(var c=database.apiConnection()){opportunity=inTransaction(c,Capability.COMMAND,x->{for(String code:List.of("OPPORTUNITY_OWNER_EXCEPTION_READ","OPPORTUNITY_OWNER_EXCEPTION_RESOLVE"))grant(x,code);return EventOpportunityReader.databaseBacked().byId(x,seed.tenant(),opportunity.id()).selector();});}
  UUID oldTask=UUID.fromString(scalar("select task_occurrence_id from responsibility.task_occurrence where tenant_id=? and subject_id=? and state in ('OPEN','WAITING')",seed.tenant(),opportunity.id()));
  String sla=scalar("select original_sla_due_at::text from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),oldTask);
  String workflow=scalar("select quote_workflow_id from opportunity.quote_workflow w where tenant_id=? and opportunity_id=? and not exists(select 1 from opportunity.quote_workflow n where n.tenant_id=w.tenant_id and n.previous_workflow_id=w.quote_workflow_id)",seed.tenant(),opportunity.id());
  mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and grantee_appointment_id=? and authority_code='SALES_OPPORTUNITY_OWNER'",seed.tenant(),seed.appointment());
  var observed=execute(new CommandEnvelope(CommandEnvelope.Type.OBSERVE_OPPORTUNITY_OWNER_EXCEPTION,UUID.randomUUID(),UUID.randomUUID(),service("OPPORTUNITY_OWNER_EXCEPTION_DISCOVER"),Map.of("opportunityId",opportunity.id().toString(),"expectedOpportunityRevision",opportunity.revision())));
  assertEquals(CommandOutcome.Status.SUCCEEDED,observed.status(),observed.rejectionCode());
  OpportunityOwnerExceptionService.Snapshot snapshot;try(var c=database.apiConnection()){snapshot=inTransaction(c,Capability.QUERY,x->R2OpportunityOwnerExceptionAssembly.service().read(x,seed.tenant(),observed.resultFact()));}
  UUID principal=UUID.randomUUID(),appointment=UUID.randomUUID();
  mutate("insert into identity.principal(tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) values(?,?,'HUMAN','FIXTURE',?,'receiver','ACTIVE',clock_timestamp())",seed.tenant(),principal,CanonicalJson.digest(principal.toString()));
  mutate("insert into identity.appointment(tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values(?,?,?,?,'OWNER',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),appointment,principal,seed.org());
  for(String code:List.of("SALES_OPPORTUNITY_OWNER","QUOTE_PREPARE","QUOTE_READ","QUOTE_RESPONSE","QUOTE_DELIVER"))mutate("insert into identity.authority_grant(tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values(?,?,?,?,?,?,clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),appointment,seed.appointment(),seed.org(),code);
  var transfer=new TreeMap<String,Object>();transfer.put("opportunityId",opportunity.id().toString());transfer.put("expectedOpportunityRevision",opportunity.revision());transfer.put("exceptionId",snapshot.selector().id().toString());transfer.put("expectedExceptionRevision",snapshot.selector().revision());transfer.put("expectedBasis",CommandScope.selector(snapshot.responsibility().basis()));transfer.put("expectedTask",CommandScope.selector(snapshot.task()));transfer.put("expectedWait",CommandScope.selector(snapshot.waitReceipt()));transfer.put("reason","exact quote handoff");transfer.put("receiverAppointmentId",appointment.toString());
  var transferred=execute(new CommandEnvelope(CommandEnvelope.Type.TRANSFER_OPPORTUNITY_RESPONSIBILITY,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),transfer));assertEquals(CommandOutcome.Status.SUCCEEDED,transferred.status(),transferred.rejectionCode());
  UUID next=UUID.fromString(scalar("select new_task_occurrence_id from opportunity.responsibility_handoff where tenant_id=? and opportunity_id=?",seed.tenant(),opportunity.id()));
  assertEquals("CANCELLED",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),oldTask));
  assertEquals(sla,scalar("select original_sla_due_at::text from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),next));
  assertEquals(waiting?"RECORD_QUOTE_REPLY":"DELIVER_QUOTE",scalar("select business_purpose_code from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),next));
  assertEquals(seed.appointment().toString(),scalar("select owner_appointment_id from opportunity.quote_workflow where tenant_id=? and quote_workflow_id=?",seed.tenant(),UUID.fromString(workflow)));
  var receiver=new Actor(seed.tenant(),principal,appointment,null,null,PrincipalKind.HUMAN);
  try(var c=database.apiConnection()){var ctx=inTransaction(c,Capability.QUERY,x->quotes().context(x,receiver,opportunity.id()));assertEquals(List.of("SAVE_QUOTE_DRAFT","FORM_QUOTE"),ctx.get("allowedActions"));}
  if(waiting){
   assertEquals("R2_QUOTE_HANDOFF_WAIT_V1",scalar("select wait_contract_code from responsibility.wait_receipt where tenant_id=? and task_occurrence_id=?",seed.tenant(),next));
   long millis=Duration.between(Instant.now(),due.plusMillis(20)).toMillis();if(millis>0)Thread.sleep(millis);
   var actor=service("OPPORTUNITY_TASK_RECOVER");R2OpportunityDiscoveryService.Candidate candidate;
   try(var c=database.apiConnection()){var page=new R2OpportunityDiscoveryService(new byte[32]).list(c,actor,R2OpportunityDiscoveryService.Kind.DUE,50,null);assertEquals(200,page.status());assertEquals(1,page.page().candidates().size());candidate=page.page().candidates().getFirst();}
   assertEquals(next,candidate.task().id());var command=R2OpportunityCommandRuntime.recovery(actor,candidate,UUID.randomUUID());
   try(var c=database.apiConnection()){var result=assertInstanceOf(CommandOutcome.class,runtime.execute(c,command));assertEquals(CommandOutcome.Status.SUCCEEDED,result.status(),result.rejectionCode());}
   assertEquals("OPEN",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),next));assertEquals(sla,scalar("select original_sla_due_at::text from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),next));
  }
  var oldQuote=scalar("select current_quote_revision_id from opportunity.opportunity where tenant_id=? and opportunity_id=?",seed.tenant(),opportunity.id());
  var fresh=quoteCommand("FORM_QUOTE",commercial(),receiver);assertNotEquals(oldQuote,fresh.id().toString());
  assertEquals("CANCELLED",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),next));
  assertEquals("0",scalar("select count(*) from opportunity.quote_issue where tenant_id=? and quote_revision_id=?",seed.tenant(),fresh.id()));
  assertEquals("0",scalar("select count(*) from opportunity.quote_approval_request where tenant_id=? and quote_revision_id=?",seed.tenant(),fresh.id()));
 }
}
