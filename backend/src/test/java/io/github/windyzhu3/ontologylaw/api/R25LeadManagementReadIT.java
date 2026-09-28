package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.lead.*;
import io.github.windyzhu3.ontologylaw.responsibility.TaskFactory;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;

class R25LeadManagementReadIT extends ContactFlowFixture {
 private R25LeadManagementReadService reads(LeadProtection p,AuditAppender a){return new R25LeadManagementReadService(new byte[32],p,policies,List.of(new LeadIntakeSources.Source("FIXTURE","合成受控来源","TEST","CONSULTATION","CN","NORMAL")),a);}
 private R25LeadManagementReadService reads(){return reads(protection,AuditAppender.databaseBacked("R25_LEAD_MANAGEMENT_IT"));}
 private void allow()throws Exception{try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"LEAD_MANAGEMENT_READ");return null;});}}
 @Test void source_catalog_uses_its_exact_organization_scope_and_never_grants_capture()throws Exception {
  setupContact();var actor=seed.request().actor();
  try(var c=database.apiConnection()){assertEquals(403,assertThrows(R1ServiceReadRuntime.Failure.class,()->reads().sources(c,actor)).status());}
  allow();
  try(var c=database.apiConnection()){
   var items=(List<?>)reads().sources(c,actor).get("items");assertEquals(1,items.size());var source=(Map<?,?>)items.getFirst();assertEquals("FIXTURE",source.get("code"));assertEquals("合成受控来源",source.get("label"));assertEquals("MANUAL",source.get("assignmentMode"));assertFalse(source.containsKey("canCapture"));
   assertThrows(java.sql.SQLException.class,()->reads(protection,(tx,e)->{}).sources(c,actor));
  }
  long revision=Long.parseLong(scalar("select revision from identity.organization_unit where tenant_id=? and organization_unit_id=?",seed.tenant(),seed.org()));
  mutate("insert into identity.object_access_grant(tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values(?,?,?,?,'LEAD_MANAGEMENT_READ','DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),'identity.organization_unit',?,?)",seed.tenant(),UUID.randomUUID(),actor.principalId(),actor.appointmentId(),seed.org(),revision);
  try(var c=database.apiConnection()){assertTrue(((List<?>)reads().sources(c,actor).get("items")).isEmpty());}
 }
 @Test void independent_read_keeps_early_contact_invalid_and_opportunity_sources_distinct()throws Exception {
  setupFlow(TaskFactory.Type.COMPLETE_LEAD_INGRESS);allow();
  try(var c=database.apiConnection()){
   var detail=reads().detail(c,seed.request().actor(),current.lead().id());assertEquals("INCOMPLETE",detail.get("state"));assertNull(detail.get("opportunityId"));
  }
  setupContact();allow();UUID lead=current.lead().id();
  try(var c=database.apiConnection()){
   var rows=(List<?>)reads().list(c,seed.request().actor(),20,null,"","","","CONTACT").get("items");assertEquals(1,rows.size());assertEquals(lead.toString(),((Map<?,?>)rows.getFirst()).get("id"));assertEquals(seed.appointment().toString(),((Map<?,?>)rows.getFirst()).get("ownerId"));
   assertEquals(current.selector().id().toString(),reads().detail(c,seed.request().actor(),lead).get("taskId"));
  }
  var invalid=execute(prepare(contact("SUSPECT_INVALID")));selectTask(TaskFactory.Type.REVIEW_LEAD_VALIDITY);assertEquals(CommandOutcome.Status.SUCCEEDED,execute(prepare(review(invalid,"CONFIRM_INVALID"))).status());
  try(var c=database.apiConnection()){var detail=reads().detail(c,seed.request().actor(),lead);assertEquals("INVALID",detail.get("state"));assertNull(detail.get("taskId"));assertNull(detail.get("action"));assertTrue(detail.toString().contains("Supervisor checked current exact result"));}
  setupContact();allow();lead=current.lead().id();execute(prepare(contact("CONNECTED_VALID")));
  try(var c=database.apiConnection()){var detail=reads().detail(c,seed.request().actor(),lead);assertEquals("OPPORTUNITY",detail.get("state"));assertNotNull(detail.get("opportunityId"));assertNull(detail.get("taskId"));}
 }
 @Test void read_permission_does_not_inherit_capture_or_team_and_denial_precedes_decryption()throws Exception {
  setupContact();var actor=seed.request().actor();UUID lead=current.lead().id();
  try(var c=database.apiConnection()){
   var noGrant=assertThrows(R1ServiceReadRuntime.Failure.class,()->reads().list(c,actor,20,null,"","","",""));assertEquals(403,noGrant.status());
   inTransaction(c,Capability.COMMAND,x->{grant(x,"TEAM_TASK_READ");return null;});
   assertEquals(403,assertThrows(R1ServiceReadRuntime.Failure.class,()->reads().detail(c,actor,lead)).status());
  }
  allow();
  try(var c=database.apiConnection()){
   assertFalse(((List<?>)reads().list(c,actor,20,null,"","","","").get("items")).isEmpty());
   assertThrows(java.sql.SQLException.class,()->reads(protection,(tx,e)->{}).detail(c,actor,lead));
   assertEquals(400,assertThrows(R1ServiceReadRuntime.Failure.class,()->reads().list(c,actor,101,null,"","","","")).status());
   var delegated=new Actor(actor.tenantId(),actor.principalId(),actor.appointmentId(),actor.principalId(),actor.appointmentId());
   assertEquals(403,assertThrows(R1ServiceReadRuntime.Failure.class,()->reads().list(c,delegated,20,null,"","","","")).status());
  }
  mutate("insert into identity.object_access_grant(tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values(?,?,?,?,'LEAD_MANAGEMENT_READ','DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),'lead.lead',?,?)",seed.tenant(),UUID.randomUUID(),actor.principalId(),actor.appointmentId(),lead,current.lead().revision());
  var bomb=new LeadProtection(){public byte[] encrypt(UUID t,Field f,String v){throw new AssertionError();}public String decrypt(UUID t,Field f,byte[] v){throw new AssertionError("Denied lead decrypted");}public byte[] hmac(UUID t,Purpose p,String a,String v){throw new AssertionError();}};
  try(var c=database.apiConnection()){
   var denied=reads(bomb,AuditAppender.databaseBacked("R25_LEAD_DENY"));
   assertTrue(((List<?>)denied.list(c,actor,20,null,"","","","").get("items")).isEmpty());
   assertEquals(403,assertThrows(R1ServiceReadRuntime.Failure.class,()->denied.detail(c,actor,lead)).status());
  }
 }
 @Test void page_cursor_is_bound_to_filters_actor_and_tenant_and_read_only_cannot_handle()throws Exception {
  setupContact();allow();var actor=seed.request().actor();String previousCursor;
  try(var c=database.apiConnection()){
   inTransaction(c,Capability.COMMAND,x->{var repo=LeadIngressService.databaseBacked(protection);for(int i=0;i<3;i++)repo.capture(x,seed.tenant(),input(false),CanonicalJson.digest("R25-CURSOR-"+i),businessAt);return null;});
   var page=reads().list(c,actor,1,null,"","","","");String next=(String)page.get("nextCursor");assertNotNull(next);previousCursor=next;
   var second=reads().list(c,actor,1,next,"","","","");assertNotEquals(((Map<?,?>)((List<?>)page.get("items")).getFirst()).get("id"),((Map<?,?>)((List<?>)second.get("items")).getFirst()).get("id"));
   assertEquals(400,assertThrows(R1ServiceReadRuntime.Failure.class,()->reads().list(c,actor,1,next,"changed","","","")).status());
   assertEquals(400,assertThrows(R1ServiceReadRuntime.Failure.class,()->reads().list(c,actor,1,next,"","FIXTURE","","")).status());
   assertEquals(400,assertThrows(R1ServiceReadRuntime.Failure.class,()->reads().list(c,actor,20,next,"","","","")).status());
  }
  mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_code='SALES_CONTACT_OWNER'",seed.tenant());
  try(var c=database.apiConnection()){var detail=reads().detail(c,actor,current.lead().id());assertNull(detail.get("action"));assertNull(detail.get("taskId"));assertEquals("CONTACT",detail.get("state"));}
  var originalTenant=seed.tenant();var originalLead=current.lead().id();setupContact();allow();
  try(var c=database.apiConnection()){assertNotEquals(originalTenant,seed.tenant());assertEquals(404,assertThrows(R1ServiceReadRuntime.Failure.class,()->reads().detail(c,seed.request().actor(),originalLead)).status());assertEquals(400,assertThrows(R1ServiceReadRuntime.Failure.class,()->reads().list(c,seed.request().actor(),1,previousCursor,"","","","")).status());}
 }
 @Test void original_early_responsibilities_wait_and_terminal_close_remain_findable()throws Exception {
  var states=Map.of(TaskFactory.Type.RESOLVE_LEAD_DUPLICATE,"DUPLICATE",TaskFactory.Type.COMPLETE_LEAD_INGRESS,"INCOMPLETE",TaskFactory.Type.ASSIGN_LEAD,"ASSIGNMENT",TaskFactory.Type.RESOLVE_SOURCE_REQUEST,"SOURCE_REVIEW",TaskFactory.Type.ACK_SOURCE_INTAKE_STOP_REQUEST,"SOURCE_REVIEW",TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP,"ROUTING");
  for(var entry:states.entrySet()){
   setupFlow(entry.getKey());allow();
   try(var c=database.apiConnection()){var detail=reads().detail(c,seed.request().actor(),current.lead().id());assertEquals(entry.getValue(),detail.get("state"),entry.getKey().name());var page=reads().list(c,seed.request().actor(),20,null,"张测试","FIXTURE","",entry.getValue());assertTrue(((List<?>)page.get("items")).stream().anyMatch(item->((Map<?,?>)item).get("id").equals(current.lead().id().toString())));}
  }
  setupContact();allow();var lead=current.lead().id();assertEquals(CommandOutcome.Status.SUCCEEDED,execute(prepare(contact("NOT_CONNECTED"))).status());
  try(var c=database.apiConnection()){var detail=reads().detail(c,seed.request().actor(),lead);assertEquals("WAITING",detail.get("state"));assertNull(detail.get("taskId"));assertFalse(detail.toString().contains("衔接异常"));}
  recoverContact();var invalid=execute(prepare(contact("SUSPECT_INVALID")));selectTask(TaskFactory.Type.REVIEW_LEAD_VALIDITY);
  try(var c=database.apiConnection()){assertEquals("VALIDITY_REVIEW",reads().detail(c,seed.request().actor(),lead).get("state"));}
  var command=prepare(review(invalid,"CLOSE_UNREACHED"));assertEquals(CommandOutcome.Status.SUCCEEDED,execute(command).status());assertEquals(CommandOutcome.Status.SUCCEEDED,execute(command).status());
  try(var c=database.apiConnection()){var detail=reads().detail(c,seed.request().actor(),lead);assertEquals("CLOSED",detail.get("state"));assertNull(detail.get("taskId"));assertTrue(detail.toString().contains("Supervisor checked current exact result"));}
 }
}
