package io.github.windyzhu3.ontologylaw.api;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.lead.ContactFlowFixture;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
class R2CustomerRequirementsHandoffIT extends ContactFlowFixture {
 @Test void receiverKeepsConfirmedIdentityButNeverInheritsFormerOwnersDraft()throws Exception {
  setupContact();var contact=execute(prepare(contact("CONNECTED_VALID")));Subject opportunity;
  var cipher=OpportunityProgressProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES"));
  try(var c=database.apiConnection()){opportunity=inTransaction(c,Capability.COMMAND,x->{for(String code:List.of("SALES_OPPORTUNITY_OWNER","CUSTOMER_REQUIREMENTS_MANAGE","OPPORTUNITY_OWNER_EXCEPTION_READ","OPPORTUNITY_OWNER_EXCEPTION_RESOLVE"))grant(x,code);return EventOpportunityReader.databaseBacked().forContact(x,seed.tenant(),contact.resultFact().id()).selector();});}
  runtime=R2OpportunityCommandRuntime.fromSourcePolicy(policies,protection,cipher,null,"T05_HANDOFF_IT");
  var p=new LinkedHashMap<String,Object>();p.put("role","CLIENT");p.put("party",null);p.put("profileChange",null);p.put("newParty",Map.of("kind","NATURAL_PERSON","name","合成客户甲","distinctIdentityConfirmed",true));
  var doc=new LinkedHashMap<String,Object>(Map.of("participants",List.of(p),"unknownOpponent",true,"matterName","商机事项","customerGoal","原客户目标","serviceScope","咨询","knownConstraints","","contactName","","contactPhone",""));
  var body=new TreeMap<String,Object>();body.put("opportunityId",opportunity.id().toString());body.put("expectedOpportunityRevision",opportunity.revision());body.put("responsibilityBasis",R2CustomerRequirementsServices.selector(opportunity));body.put("expectedDraft",null);body.put("expectedConfirmation",null);body.put("document",doc);
  var save=new CommandEnvelope(CommandEnvelope.Type.SAVE_OPPORTUNITY_CUSTOMER_DRAFT,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),body);var saved=execute(save);assertEquals(CommandOutcome.Status.SUCCEEDED,saved.status());
  body.put("expectedDraft",R2CustomerRequirementsServices.selector(saved.resultFact()));body.remove("document");var confirmed=execute(new CommandEnvelope(CommandEnvelope.Type.CONFIRM_OPPORTUNITY_CUSTOMER_REQUIREMENTS,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),body));assertEquals(CommandOutcome.Status.SUCCEEDED,confirmed.status());
  var readService=new R2CustomerRequirementsReadService(new byte[32],protection,cipher,AuditAppender.databaseBacked("T05_HANDOFF_READ"));
  try(var c=database.apiConnection()){var context=readService.read(c,seed.request().actor(),opportunity.id(),null,false);doc=new LinkedHashMap<>((Map<String,Object>)((Map<?,?>)context.get("confirmation")).get("document"));}
  doc.put("customerGoal","原负责人未确认的修改");body.put("document",doc);body.put("expectedConfirmation",R2CustomerRequirementsServices.selector(confirmed.resultFact()));var pending=execute(new CommandEnvelope(CommandEnvelope.Type.SAVE_OPPORTUNITY_CUSTOMER_DRAFT,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),body));assertEquals(CommandOutcome.Status.SUCCEEDED,pending.status());
  mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and grantee_appointment_id=? and authority_code='SALES_OPPORTUNITY_OWNER'",seed.tenant(),seed.appointment());
  var observer=service("OPPORTUNITY_OWNER_EXCEPTION_DISCOVER");var observed=execute(new CommandEnvelope(CommandEnvelope.Type.OBSERVE_OPPORTUNITY_OWNER_EXCEPTION,UUID.randomUUID(),UUID.randomUUID(),observer,Map.of("opportunityId",opportunity.id().toString(),"expectedOpportunityRevision",opportunity.revision())));
  OpportunityOwnerExceptionService.Snapshot snapshot;try(var c=database.apiConnection()){snapshot=inTransaction(c,Capability.QUERY,x->R2OpportunityOwnerExceptionAssembly.service().read(x,seed.tenant(),observed.resultFact()));}
  UUID principal=UUID.randomUUID(),appointment=UUID.randomUUID();
  mutate("insert into identity.principal (tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) values (?,?,'HUMAN','FIXTURE',?,'T05 receiver','ACTIVE',clock_timestamp())",seed.tenant(),principal,CanonicalJson.digest(principal.toString()));
  mutate("insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'OWNER',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),appointment,principal,seed.org());
  for(String code:List.of("SALES_OPPORTUNITY_OWNER","CUSTOMER_REQUIREMENTS_MANAGE"))mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,?,clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),appointment,seed.appointment(),seed.org(),code);
  var transfer=new TreeMap<String,Object>();transfer.put("opportunityId",opportunity.id().toString());transfer.put("expectedOpportunityRevision",opportunity.revision());transfer.put("exceptionId",snapshot.selector().id().toString());transfer.put("expectedExceptionRevision",snapshot.selector().revision());transfer.put("expectedBasis",CommandScope.selector(snapshot.responsibility().basis()));transfer.put("expectedTask",CommandScope.selector(snapshot.task()));transfer.put("expectedWait",CommandScope.selector(snapshot.waitReceipt()));transfer.put("reason","合成验收移交");transfer.put("receiverAppointmentId",appointment.toString());
  var transferred=execute(new CommandEnvelope(CommandEnvelope.Type.TRANSFER_OPPORTUNITY_RESPONSIBILITY,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),transfer));assertEquals(CommandOutcome.Status.SUCCEEDED,transferred.status(),transferred.rejectionCode());
  var receiver=new Actor(seed.tenant(),principal,appointment,null,null,PrincipalKind.HUMAN);
  Map<String,Object> receiverContext;try(var c=database.apiConnection()){receiverContext=readService.read(c,receiver,opportunity.id(),null,false);}
  assertFalse(receiverContext.containsKey("draft"));assertEquals(appointment.toString(),receiverContext.get("currentOwnerAppointmentId"));assertEquals("原客户目标",((Map<?,?>)((Map<?,?>)receiverContext.get("confirmation")).get("document")).get("customerGoal"));
  body.put("expectedDraft",R2CustomerRequirementsServices.selector(pending.resultFact()));body.remove("document");var stale=new CommandEnvelope(CommandEnvelope.Type.CONFIRM_OPPORTUNITY_CUSTOMER_REQUIREMENTS,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),body);try(var c=database.apiConnection()){assertThrows(CommandHandler.Rejected.class,()->runtime.execute(c,stale));}
  body.put("document",((Map<?,?>)receiverContext.get("confirmation")).get("document"));body.put("responsibilityBasis",receiverContext.get("responsibilityBasis"));body.put("expectedDraft",null);
  var receiverSaved=execute(new CommandEnvelope(CommandEnvelope.Type.SAVE_OPPORTUNITY_CUSTOMER_DRAFT,UUID.randomUUID(),UUID.randomUUID(),receiver,body));assertEquals(CommandOutcome.Status.SUCCEEDED,receiverSaved.status(),receiverSaved.rejectionCode());
  assertEquals("1",scalar("select count(*) from party.party where tenant_id=?",seed.tenant()));assertEquals("1",scalar("select count(*) from opportunity.customer_requirement_confirmation where tenant_id=?",seed.tenant()));
 }
}
