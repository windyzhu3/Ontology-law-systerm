package io.github.windyzhu3.ontologylaw.api;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.lead.WorkcardTestFixture;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class PersonalWaitingIT extends WorkcardTestFixture {
 private CurrentWorkCardDisclosureService.Response waiting(UUID id,String cursor)throws Exception {try(var c=database.apiConnection()){return new CurrentWorkCardDisclosureService(protection,policies,"PERSONAL_WAITING_IT").readWaiting(c,seed.request().actor(),UUID.randomUUID(),id,20,cursor);}}
 private void setupWait()throws Exception {setupCard(TaskFactory.Type.CONTACT_LEAD);try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{var factory=TaskFactory.databaseBacked();factory.waitUntil(x,seed.tenant(),current,seed.appointment(),current.createdAt().plusSeconds(86400),current.createdAt());current=factory.read(x,seed.tenant(),current.selector().id());return null;});}}
 @Test void personal_wait_list_matches_card_count_audits_and_never_reopens()throws Exception {setupWait();var result=waiting(null,null);assertEquals(200,result.status(),result.errorCode());assertEquals(readCard(null).body().get("waitingCount"),result.body().get("totalCount"));var items=(List<?>)result.body().get("waitingItems");assertEquals(1,items.size());assertTrue(result.body().toString().contains("WAIT_FUTURE"));assertTrue(auditCount()>0);try(var c=database.apiConnection()){assertEquals("WAITING",inTransaction(c,Capability.QUERY,x->CurrentTaskReader.databaseBacked().read(x,seed.tenant(),current.selector().id()).state()));}}
 @Test void exact_detail_denies_foreign_task_and_clears_revoked_objects()throws Exception {setupWait();assertEquals(404,waiting(UUID.randomUUID(),null).status());var detail=waiting(current.selector().id(),null);assertEquals(200,detail.status());assertEquals(false,((Map<?,?>)detail.body().get("waitingDetail")).get("canHandle"));deny(current.selector(),"SALES_CONTACT_OWNER");assertEquals(0,waiting(null,null).body().get("totalCount"));assertEquals(404,waiting(current.selector().id(),null).status());}
 @Test void reopened_exact_task_is_ready_and_excluded_from_wait_count()throws Exception {setupWait();try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{TaskFactory.databaseBacked().reopen(x,seed.tenant(),current);return null;});}var r=waiting(current.selector().id(),null);assertEquals(200,r.status(),r.errorCode());assertEquals(0,r.body().get("totalCount"));var d=(Map<?,?>)r.body().get("waitingDetail");assertEquals("READY",d.get("state"));assertEquals(true,d.get("canHandle"));}
 @Test void stale_or_malformed_cursor_is_rejected()throws Exception {setupWait();assertEquals(400,waiting(null,"bad").status());}
 @Test void reopened_task_with_stale_business_basis_is_not_ready()throws Exception {
  setupWait();try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{TaskFactory.databaseBacked().reopen(x,seed.tenant(),current);return null;});}
  mutate("update lead.lead set disposition_code='KEEP_SEPARATE',revision=revision+1 where tenant_id=? and lead_id=?",seed.tenant(),current.lead().id());
  var result=waiting(current.selector().id(),null);assertEquals(412,result.status());assertNull(result.body());
 }

 @Test void pagination_is_stable_and_rejects_changed_wait_set()throws Exception {
  setupWait();try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"LEAD_ROUTING_DECIDE");return null;});}
  var later=addTask(UUID.randomUUID(),current.createdAt(),current.createdAt().plusSeconds(172800),"WAITING");
  CurrentWorkCardDisclosureService.Response first;try(var c=database.apiConnection()){first=new CurrentWorkCardDisclosureService(protection,policies,"PAGE_IT").readWaiting(c,seed.request().actor(),UUID.randomUUID(),null,1,null);}
  assertEquals(200,first.status());assertEquals(2,first.body().get("totalCount"));String cursor=(String)first.body().get("nextCursor");assertNotNull(cursor);assertEquals(1,((List<?>)waiting(null,cursor).body().get("waitingItems")).size());
  deny(later.selector(),"LEAD_ROUTING_DECIDE");assertEquals(412,waiting(null,cursor).status());assertEquals(1,waiting(null,null).body().get("totalCount"));
 }
 @Test void wait_receipt_deny_filters_both_count_and_list()throws Exception {setupWait();io.github.windyzhu3.ontologylaw.execution.R1EventFacts.Wait wait;try(var c=database.apiConnection()){wait=inTransaction(c,Capability.QUERY,x->EventResponsibilityReader.databaseBacked().latestWait(x,seed.tenant(),current.selector().id()));}deny(wait.selector(),"SALES_CONTACT_OWNER");assertEquals(0,readCard(null).body().get("waitingCount"));assertEquals(0,waiting(null,null).body().get("totalCount"));}
 @Test void audit_failure_suppresses_personal_content_and_rolls_back()throws Exception {setupWait();try(var admin=database.adminConnection();var sql=admin.createStatement()){
  sql.execute("create function public.waiting_fail() returns trigger language plpgsql as 'begin raise exception ''Synthetic audit failure''; end'");sql.execute("create trigger waiting_fail before insert on audit.audit_entry for each row execute function public.waiting_fail()");
  try{var r=waiting(null,null);assertEquals(503,r.status());assertNull(r.body());assertEquals(0,auditCount());}finally{sql.execute("drop trigger waiting_fail on audit.audit_entry");sql.execute("drop function public.waiting_fail()");}
 }}
 @Test void represented_appointment_scope_is_isolated_and_revocation_fails_closed()throws Exception {
  setupWait();UUID principal=UUID.randomUUID(),appointment=UUID.randomUUID();
  mutate("insert into identity.principal (tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) values (?,?,'HUMAN','OTHER',?,'代办测试','ACTIVE',clock_timestamp())",seed.tenant(),principal,io.github.windyzhu3.ontologylaw.execution.CanonicalJson.digest(principal.toString()));
  mutate("insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'OWNER',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),appointment,principal,seed.org());
  mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,'SALES_CONTACT_OWNER',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),appointment,seed.appointment(),seed.org());
  var service=new CurrentWorkCardDisclosureService(protection,policies,"DELEGATED_WAIT_IT");var own=new io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor(seed.tenant(),principal,appointment,null,null);
  try(var c=database.apiConnection()){assertEquals(0,service.readWaiting(c,own,UUID.randomUUID(),null,20,null).body().get("totalCount"));assertEquals(404,service.readWaiting(c,own,UUID.randomUUID(),current.selector().id(),20,null).status());}
  mutate("insert into identity.delegation_grant (tenant_id,delegation_grant_id,source_authority_grant_id,delegator_appointment_id,delegate_appointment_id,scope_organization_unit_id,valid_from,state,created_at) values (?,?,?,?,?,?,clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),seed.grant(),seed.appointment(),appointment,seed.org());
  var represented=new io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor(seed.tenant(),principal,appointment,seed.principal(),seed.appointment());
  try(var c=database.apiConnection()){assertEquals(1,service.readWaiting(c,represented,UUID.randomUUID(),null,20,null).body().get("totalCount"));}
  mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='TEST',revision=revision+1 where tenant_id=? and authority_grant_id=?",seed.tenant(),seed.grant());
  try(var c=database.apiConnection()){var result=service.readWaiting(c,represented,UUID.randomUUID(),null,20,null);assertEquals(403,result.status());assertNull(result.body());}
 }
}
