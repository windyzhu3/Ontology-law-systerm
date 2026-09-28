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
class R25BusinessOverviewReadIT extends ContactFlowFixture {
 private R25BusinessOverviewReadService reads(LeadProtection p,AuditAppender a){return new R25BusinessOverviewReadService(new byte[32],p,policies,a);}
 private R25BusinessOverviewReadService reads(){return reads(protection,AuditAppender.databaseBacked("R25_OVERVIEW_IT"));}
 private Map<?,?> metric(Map<String,Object> summary,String key){return (Map<?,?>)((List<?>)summary.get("metrics")).stream().filter(x->key.equals(((Map<?,?>)x).get("key"))).findFirst().orElseThrow();}
 private void allow(String... codes)throws Exception {try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{for(var code:codes)grant(x,code);return null;});}}
 @Test void handoff_does_not_move_business_read_scope_to_the_receivers_organization()throws Exception {
  setupContact();var valid=execute(prepare(contact("CONNECTED_VALID")));assertEquals(CommandOutcome.Status.SUCCEEDED,valid.status());
  allow("OPPORTUNITY_LEDGER_READ","OPPORTUNITY_OWNER_EXCEPTION_READ","OPPORTUNITY_OWNER_EXCEPTION_RESOLVE");
  UUID otherOrg=UUID.randomUUID();mutate("insert into identity.organization_unit(tenant_id,organization_unit_id,unit_code,display_name,state,created_at) values(?,?,'OTHER_ROOT','Separate synthetic organization','ACTIVE',clock_timestamp())",seed.tenant(),otherOrg);
  var receiver=overviewActor(otherOrg,seed.org(),"SALES_OPPORTUNITY_OWNER");var otherReader=overviewActor(otherOrg,otherOrg,"OPPORTUNITY_LEDGER_READ");
  try(var c=database.apiConnection()){
   inTransaction(c,Capability.COMMAND,x->{
    var opening=io.github.windyzhu3.ontologylaw.opportunity.EventOpportunityReader.databaseBacked().forContact(x,seed.tenant(),valid.resultFact().id());var service=R2OpportunityOwnerExceptionAssembly.service();var exception=service.observe(x,seed.tenant(),opening.selector()).orElseThrow();
    var decision=new io.github.windyzhu3.ontologylaw.opportunity.OpportunityOwnerExceptionService.Decision(exception.selector(),exception.opportunity(),exception.responsibility().basis(),exception.task(),exception.waitReceipt(),seed.appointment(),"Authorized receiver in another organization");
    assertTrue(service.transfer(x,seed.tenant(),decision,receiver.appointmentId(),java.time.ZoneId.of("Asia/Shanghai")).changed());return null;
   });
   assertEquals(1L,metric(reads().summary(c,seed.request().actor(),"2026-08"),"opportunities").get("count"));
   assertEquals(1L,metric(reads().summary(c,receiver,"2026-08"),"opportunities").get("count"));
   assertEquals(0L,metric(reads().summary(c,otherReader,"2026-08"),"opportunities").get("count"));
   assertTrue(((List<?>)reads().details(c,otherReader,"opportunities","2026-08",20,null).get("items")).isEmpty());
  }
 }
 private Actor overviewActor(UUID organization,UUID scope,String authority)throws Exception {
  UUID principal=UUID.randomUUID(),appointment=UUID.randomUUID();
  mutate("insert into identity.principal(tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) values(?,?,'HUMAN','FIXTURE',?,'Overview synthetic reader','ACTIVE',clock_timestamp())",seed.tenant(),principal,CanonicalJson.digest(principal.toString()));
  mutate("insert into identity.appointment(tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values(?,?,?,?,'OWNER',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),appointment,principal,organization);
  mutate("insert into identity.authority_grant(tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values(?,?,?,?,?,?,clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),appointment,seed.appointment(),scope,authority);
  return new Actor(seed.tenant(),principal,appointment,null,null);
 }
 @Test void candidate_budget_exhaustion_returns_no_truncated_total_and_commits_no_disclosure()throws Exception {
  setupFlow(TaskFactory.Type.COMPLETE_LEAD_INGRESS);allow("LEAD_MANAGEMENT_READ");
  // Isolated test fixture only: unconfigured source rows must still consume the raw budget.
  mutate("insert into lead.lead select (jsonb_populate_record(null::lead.lead,to_jsonb(l)||jsonb_build_object('lead_id',gen_random_uuid(),'source_account_code','UNCONFIGURED','source_record_key_digest',encode(decode(md5(g.n::text)||md5(g.n::text),'hex'),'escape')))).* from lead.lead l cross join generate_series(1,5000) g(n) where l.tenant_id=? and l.lead_id=?",seed.tenant(),current.lead().id());
  var before=scalar("select count(*) from audit.audit_entry_classified_v where tenant_id=?",seed.tenant());
  try(var c=database.apiConnection()){var failure=assertThrows(R1ServiceReadRuntime.Failure.class,()->reads().summary(c,seed.request().actor(),"2026-08"));assertEquals(503,failure.status());assertEquals("SERVICE_UNAVAILABLE",failure.code());}
  assertEquals(before,scalar("select count(*) from audit.audit_entry_classified_v where tenant_id=?",seed.tenant()));
 }
 @Test void authority_expiring_during_a_detail_read_cannot_disclose_stale_counts_or_labels()throws Exception {
  setupFlow(TaskFactory.Type.COMPLETE_LEAD_INGRESS);
  mutate("insert into identity.authority_grant(tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,valid_until,state,created_at) values(?,?,?,?,?,'LEAD_MANAGEMENT_READ',clock_timestamp()-interval '1 day',clock_timestamp()+interval '2 seconds','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),seed.appointment(),seed.appointment(),seed.org());
  var original=protection;var slow=new LeadProtection(){boolean delayed;public byte[] encrypt(UUID t,Field f,String v){return original.encrypt(t,f,v);}public byte[] hmac(UUID t,Purpose p,String a,String v){return original.hmac(t,p,a,v);}public String decrypt(UUID t,Field f,byte[] v){if(!delayed){delayed=true;try{Thread.sleep(2200);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}return original.decrypt(t,f,v);}};
  var before=scalar("select count(*) from audit.audit_entry_classified_v where tenant_id=?",seed.tenant());
  try(var c=database.apiConnection()){assertEquals(403,assertThrows(R1ServiceReadRuntime.Failure.class,()->reads(slow,AuditAppender.databaseBacked("R25_OVERVIEW_EXPIRY")).details(c,seed.request().actor(),"leads","2026-08",20,null)).status());}
  assertEquals(before,scalar("select count(*) from audit.audit_entry_classified_v where tenant_id=?",seed.tenant()));
 }
 @Test void waiting_keeps_original_due_stock_and_contact_completion_is_not_an_extra_opportunity()throws Exception {
  setupContact();allow("LEAD_MANAGEMENT_READ","TEAM_TASK_READ","OPPORTUNITY_LEDGER_READ");
  var attempt=prepare(contact("NOT_CONNECTED"));assertEquals(CommandOutcome.Status.SUCCEEDED,execute(attempt).status());assertEquals(CommandOutcome.Status.SUCCEEDED,execute(attempt).status());
  try(var c=database.apiConnection()){
   var summary=reads().summary(c,seed.request().actor(),"2026-08");assertEquals(1L,metric(summary,"leads").get("count"));assertEquals(0L,metric(summary,"opportunities").get("count"));assertEquals(1L,metric(summary,"overdueTasks").get("count"));
   assertEquals(1L,metric(reads().summary(c,seed.request().actor(),"2026-07"),"overdueTasks").get("count"));
   var details=(List<?>)reads().details(c,seed.request().actor(),"overdueTasks","2026-08",20,null).get("items");assertEquals(1,details.size());assertTrue(((String)((Map<?,?>)details.getFirst()).get("stateLabel")).contains("等待中"));
  }
  recoverContact();var valid=prepare(contact("CONNECTED_VALID"));assertEquals(CommandOutcome.Status.SUCCEEDED,execute(valid).status());assertEquals(CommandOutcome.Status.SUCCEEDED,execute(valid).status());
  try(var c=database.apiConnection()){assertEquals(1L,metric(reads().summary(c,seed.request().actor(),"2026-08"),"opportunities").get("count"));}
 }
 @Test void cursors_bind_month_metric_limit_and_actor_and_expire()throws Exception {
  setupContact();allow("LEAD_MANAGEMENT_READ","TEAM_TASK_READ");var actor=seed.request().actor();
  String scope=CanonicalJson.encode(Map.of("purpose","R25_BUSINESS_OVERVIEW_V1","tenant",actor.tenantId().toString(),"principal",actor.principalId().toString(),"appointment",actor.appointmentId().toString(),"metric","leads","month","2026-08","limit",20));
  var codec=new R2ManagementCursor(new byte[32]);var token=codec.encode(new UUID(0,0),scope,java.time.Instant.now());
  try(var c=database.apiConnection()){
   assertEquals(1,((List<?>)reads().details(c,actor,"leads","2026-08",20,token).get("items")).size());
   for(var input:List.of(new Object[]{"leads","2026-07",20,token},new Object[]{"overdueTasks","2026-08",20,token},new Object[]{"leads","2026-08",1,token},new Object[]{"leads","2026-08",20,codec.encode(new UUID(0,0),scope,java.time.Instant.now().minusSeconds(601))}))assertEquals(400,assertThrows(R1ServiceReadRuntime.Failure.class,()->reads().details(c,actor,(String)input[0],(String)input[1],(Integer)input[2],(String)input[3])).status());
   var foreign=new Actor(UUID.randomUUID(),actor.principalId(),actor.appointmentId(),null,null);assertEquals(403,assertThrows(R1ServiceReadRuntime.Failure.class,()->reads().summary(c,foreign,"2026-08")).status());
  }
 }
 @Test void exact_counts_and_drilldown_share_period_scope_and_never_open_protected_body_for_aggregation()throws Exception {
  setupFlow(TaskFactory.Type.COMPLETE_LEAD_INGRESS);
  try(var c=database.apiConnection()){assertEquals(403,assertThrows(R1ServiceReadRuntime.Failure.class,()->reads().summary(c,seed.request().actor(),"2026-08")).status());}
  allow("LEAD_MANAGEMENT_READ");
  var bomb=new LeadProtection(){public byte[] encrypt(UUID t,Field f,String v){throw new AssertionError();}public String decrypt(UUID t,Field f,byte[] v){throw new AssertionError("Counts must not open protected bodies");}public byte[] hmac(UUID t,Purpose p,String a,String v){throw new AssertionError();}};
  try(var c=database.apiConnection()){
   var summary=reads(bomb,AuditAppender.databaseBacked("R25_OVERVIEW_COUNTS")).summary(c,seed.request().actor(),"2026-08");assertEquals(5,((List<?>)summary.get("metrics")).size());assertEquals(1L,metric(summary,"leads").get("count"));assertNull(metric(summary,"opportunities").get("count"));assertEquals("FORBIDDEN",metric(summary,"opportunities").get("status"));
   var detail=reads().details(c,seed.request().actor(),"leads","2026-08",20,null);assertEquals(1,((List<?>)detail.get("items")).size());assertEquals(current.lead().id().toString(),((Map<?,?>)((List<?>)detail.get("items")).getFirst()).get("id"));
   assertEquals(0L,metric(reads().summary(c,seed.request().actor(),"2026-07"),"leads").get("count"));
   assertEquals(400,assertThrows(R1ServiceReadRuntime.Failure.class,()->reads().summary(c,seed.request().actor(),"2026-13")).status());
   assertThrows(java.sql.SQLException.class,()->reads(protection,(tx,e)->{}).summary(c,seed.request().actor(),"2026-08"));
   var own=seed.request().actor();var delegated=new Actor(own.tenantId(),own.principalId(),own.appointmentId(),own.principalId(),own.appointmentId());assertEquals(403,assertThrows(R1ServiceReadRuntime.Failure.class,()->reads().summary(c,delegated,"2026-08")).status());
  }
  mutate("insert into identity.object_access_grant(tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values(?,?,?,?,'LEAD_MANAGEMENT_READ','DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),'lead.lead',?,?)",seed.tenant(),UUID.randomUUID(),seed.request().actor().principalId(),seed.appointment(),current.lead().id(),current.lead().revision());
  try(var c=database.apiConnection()){assertEquals(0L,metric(reads(bomb,AuditAppender.databaseBacked("R25_OVERVIEW_DENY")).summary(c,seed.request().actor(),"2026-08"),"leads").get("count"));assertTrue(((List<?>)reads(bomb,AuditAppender.databaseBacked("R25_OVERVIEW_DENY_DETAIL")).details(c,seed.request().actor(),"leads","2026-08",20,null).get("items")).isEmpty());}
 }
}
