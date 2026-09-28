package io.github.windyzhu3.ontologylaw.api;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.identity.WorkcardOwnerReader;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import io.github.windyzhu3.ontologylaw.lead.*;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
/** Real QUERY/AUDIT role tests; no endpoint may turn a read into business maintenance. */
class R2OpportunityLedgerReadIT extends R1HttpFixture {
 private Subject opportunity,sourceLead;
 private void setupLedger(String right)throws Exception {
  setupContact();var result=execute(prepare(contact("CONNECTED_VALID")));
  opportunityProtection=OpportunityProgressProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES"));
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
   var opening=EventOpportunityReader.databaseBacked().forContact(x,seed.tenant(),result.resultFact().id());opportunity=opening.selector();sourceLead=CurrentLeadReader.databaseBacked(protection).selector(x,seed.tenant(),opening.leadId());grant(x,right);
   current=TaskFactory.databaseBacked().createInitialOpportunity(x,seed.tenant(),seed.appointment(),opportunity,ZoneId.of("Asia/Shanghai"),businessAt);return null;
  });}
 }
 private R2OpportunityLedgerReadService service(){return new R2OpportunityLedgerReadService(new byte[32],protection,opportunityProtection,AuditAppender.databaseBacked("T03_READ_IT"));}
 private Map<String,Object> read(String operation)throws Exception{try(var c=database.apiConnection()){return service().read(c,seed.request().actor(),operation,operation.equals("detail")?opportunity.id():null,20,null,null,null);}}
 @Test void continuation_http_exposes_only_own_exact_task()throws Exception {
  setupLedger("SALES_OPPORTUNITY_OWNER");try(var http=new HttpHarness()){
   var result=http.request("GET","/api/v1/opportunity-tasks/"+current.selector().id()+"/context",null,Map.of());assertEquals(200,result.statusCode(),result.body());assertEquals(opportunity.id().toString(),((Map<?,?>)http.body(result).get("opportunity")).get("id"));
   assertEquals(403,http.request("GET","/api/v1/opportunity-tasks/"+UUID.randomUUID()+"/context",null,Map.of()).statusCode());
  }
 }
 @Test void continuation_resolves_only_own_active_progress_task_without_writes()throws Exception {
  setupLedger("SALES_OPPORTUNITY_OWNER");var before=counts();
  try(var c=database.apiConnection()){var detail=service().read(c,seed.request().actor(),"task",current.selector().id(),20,null,null,null);assertEquals(opportunity.id().toString(),((Map<?,?>)detail.get("opportunity")).get("id"));assertEquals(true,detail.get("canHandle"));}
  var after=counts();for(int n=0;n<before.size();n++)if(n!=8)assertEquals(before.get(n),after.get(n));
  try(var c=database.apiConnection()){assertThrows(R1ServiceReadRuntime.Failure.class,()->service().read(c,seed.request().actor(),"task",UUID.randomUUID(),20,null,null,null));}
 }
 @Test void ledger_does_not_reacquire_the_same_identity_lock_per_fact()throws Exception {
  setupLedger("SALES_OPPORTUNITY_OWNER");
  try(var c=database.apiConnection()) {var probe=new ReadConnectionProbe(c);service().read(probe.connection(),seed.request().actor(),"list",null,20,null,null,null);
   long locks=probe.statements.stream().filter(q->q.contains("pg_advisory_xact_lock_shared")).count();
   System.out.printf("LEDGER_SQL kind=opportunity count=%d locks=%d sqlMs=%.1f%n",probe.statements.size(),locks,probe.sqlNanos.sum()/1e6);
   assertEquals(2,locks,"One business fence and one identity lock per locked read");
   assertEquals(1,probe.inserts.get(),"Small ledger disclosure audit should use one synchronous JDBC batch");
  }
 }
 @Test void ledger_fence_keeps_progress_changes_after_disclosure_commit()throws Exception {
  setupLedger("SALES_OPPORTUNITY_OWNER");
  try(var reader=database.apiConnection();var writer=database.apiConnection();var observer=database.adminConnection();var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
   var probe=new ReadConnectionProbe(reader);probe.pauseCommit=true;
   int writerPid;try(var q=writer.createStatement();var rs=q.executeQuery("select pg_backend_pid()")){rs.next();writerPid=rs.getInt(1);}
   var reading=executor.submit(()->service().read(probe.connection(),seed.request().actor(),"detail",opportunity.id(),20,null,null,null));
   try {
    assertTrue(probe.commitReached.await(10,java.util.concurrent.TimeUnit.SECONDS));
    var writing=executor.submit(()->inTransaction(writer,Capability.COMMAND,c->{R1BusinessFence.databaseBacked().exclusive(c,seed.tenant());return R2OpportunityProgressServices.create(opportunityProtection).record(c,seed.request().actor(),opportunity,current.selector(),new OpportunityProgressInput("MEETING","Concurrent ledger progress",businessAt,businessAt.plusSeconds(86400)),ZoneId.of("Asia/Shanghai"),businessAt.plusSeconds(60));}));
    boolean blocked=false;long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
    while(!blocked&&System.nanoTime()<until){try(var q=observer.prepareStatement("select exists(select 1 from pg_locks where pid=? and not granted and locktype='advisory')")){q.setInt(1,writerPid);try(var rs=q.executeQuery()){rs.next();blocked=rs.getBoolean(1);}}if(!blocked)Thread.sleep(5);}
    assertTrue(blocked);assertFalse(writing.isDone());probe.commitContinue.countDown();assertEquals("OPEN",reading.get(10,java.util.concurrent.TimeUnit.SECONDS).get("taskState"));writing.get(10,java.util.concurrent.TimeUnit.SECONDS);
    assertEquals("WAITING",read("detail").get("taskState"));
   }finally{probe.release();}
  }
 }
 @Test void own_open_task_is_audited_and_selectable_without_business_writes()throws Exception {
  setupLedger("SALES_OPPORTUNITY_OWNER");var before=counts();var detail=read("detail");assertEquals("OPEN",detail.get("taskState"));assertEquals(true,detail.get("canHandle"));assertEquals(current.selector().id().toString(),((Map<?,?>)detail.get("task")).get("id"));assertEquals(R1ResourceTags.task(seed.request().actor(),current.selector(),current.state(),opportunity),((Map<?,?>)detail.get("task")).get("etag"));assertEquals(1,((List<?>)read("list").get("items")).size());
  var after=counts();for(int i=0;i<before.size();i++)if(i!=8)assertEquals(before.get(i),after.get(i),"Read changed business count "+i);
  assertNotEquals("0",scalar("select count(*) from audit.audit_entry_classified_v where tenant_id=? and summary_schema_code='R2_OPPORTUNITY_LEDGER_DISCLOSURE_AUDIT_V1'",seed.tenant()));
 }
 @Test void explicit_manager_read_does_not_grant_handling_or_exception_permission_substitute()throws Exception {
  setupLedger("OPPORTUNITY_LEDGER_READ");assertEquals(false,read("detail").get("canHandle"));assertFalse(read("detail").containsKey("task"));
  mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_code='OPPORTUNITY_LEDGER_READ'",seed.tenant());
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"OPPORTUNITY_OWNER_EXCEPTION_READ");return null;});}
  assertThrows(R1ServiceReadRuntime.Failure.class,()->read("list"));
 }
 @Test void denied_lead_never_decrypts_even_for_search_and_denied_task_removes_record()throws Exception {
  setupLedger("SALES_OPPORTUNITY_OWNER");deny(sourceLead,"SALES_OPPORTUNITY_OWNER");
  LeadProtection bomb=new LeadProtection(){public byte[] encrypt(UUID t,Field f,String v){throw new AssertionError();}public String decrypt(UUID t,Field f,byte[] v){throw new AssertionError("Denied source decrypted");}public byte[] hmac(UUID t,Purpose p,String a,String v){throw new AssertionError();}};
  try(var c=database.apiConnection()){var hidden=new R2OpportunityLedgerReadService(new byte[32],bomb,opportunityProtection,AuditAppender.databaseBacked("T03_DENY"));assertEquals(List.of(),hidden.read(c,seed.request().actor(),"list",null,20,null,"test",null).get("items"));}
  assertThrows(R1ServiceReadRuntime.Failure.class,()->read("detail"));
 }
 @Test void bounded_cursor_binds_search_and_current_actor_and_cross_tenant_detail_is_unavailable()throws Exception {
  setupLedger("SALES_OPPORTUNITY_OWNER");String cursor;try(var c=database.apiConnection()){cursor=(String)service().read(c,seed.request().actor(),"list",null,1,null,null,null).get("nextCursor");}assertNotNull(cursor);
  try(var c=database.apiConnection()){assertThrows(R1ServiceReadRuntime.Failure.class,()->service().read(c,seed.request().actor(),"list",null,1,cursor,"different",null));}
  try(var c=database.apiConnection()){assertEquals(List.of(),service().read(c,seed.request().actor(),"list",null,1,cursor,null,null).get("items"));}
  var wrong=new Actor(UUID.randomUUID(),seed.principal(),seed.appointment(),null,null,PrincipalKind.HUMAN);
  try(var c=database.apiConnection()){assertThrows(R1ServiceReadRuntime.Failure.class,()->service().read(c,wrong,"detail",opportunity.id(),20,null,null,null));}
 }
 @Test void latest_confirmed_progress_waits_and_progress_deny_never_decrypts_or_removes_basic_detail()throws Exception {
  setupLedger("SALES_OPPORTUNITY_OWNER");OpportunityProgressService.Result progress;
  try(var c=database.apiConnection()){progress=inTransaction(c,Capability.COMMAND,x->R2OpportunityProgressServices.create(opportunityProtection).record(x,seed.request().actor(),opportunity,current.selector(),new OpportunityProgressInput("MEETING","Confirmed ledger progress",businessAt,businessAt.plusSeconds(86400)),ZoneId.of("Asia/Shanghai"),businessAt.plusSeconds(60)));}
  var detail=read("detail");assertEquals("WAITING",detail.get("taskState"));assertEquals(false,detail.get("canHandle"));assertEquals("Confirmed ledger progress",((Map<?,?>)detail.get("lastProgress")).get("summary"));
  deny(progress.progress(),"SALES_OPPORTUNITY_OWNER");
  var bomb=new OpportunityProgressProtection(){public byte[] encrypt(UUID t,UUID o,UUID p,String body){throw new AssertionError();}public String decrypt(UUID t,UUID o,UUID p,byte[] body){throw new AssertionError("Denied progress decrypted");}};
  try(var c=database.apiConnection()){var safe=new R2OpportunityLedgerReadService(new byte[32],protection,bomb,AuditAppender.databaseBacked("T03_PROGRESS_DENY"));var hidden=safe.read(c,seed.request().actor(),"detail",opportunity.id(),20,null,null,null);assertTrue(hidden.containsKey("customerLabel"));assertFalse(hidden.containsKey("lastProgress"));}
 }
 @Test void exact_task_deny_hides_record_and_draft_deny_removes_only_handle_flag()throws Exception {
  setupLedger("SALES_OPPORTUNITY_OWNER");
  var values=Map.<String,Object>of("progressTypeCode","MEETING","progressSummary","Private unsent draft","occurredAt",businessAt.toString(),"nextCheckAt",businessAt.plusSeconds(86400).toString());
  ActionDraftService.Draft draft;try(var c=database.apiConnection()){draft=inTransaction(c,Capability.COMMAND,x->ActionDraftService.databaseBacked().save(x,seed.tenant(),current,null,values,seed.appointment(),businessAt).draft());}
  deny(draft.selector(),"SALES_OPPORTUNITY_OWNER");var detail=read("detail");assertEquals(false,detail.get("canHandle"));assertFalse(detail.containsKey("task"));assertFalse(detail.toString().contains("Private unsent draft"));
  deny(current.selector(),"SALES_OPPORTUNITY_OWNER");assertEquals(List.of(),read("list").get("items"));assertThrows(R1ServiceReadRuntime.Failure.class,()->read("detail"));
 }
 @Test void cursor_rejects_exact_anchor_change_and_cancelled_task_is_never_offered()throws Exception {
  setupLedger("SALES_OPPORTUNITY_OWNER");String cursor;try(var c=database.apiConnection()){cursor=(String)service().read(c,seed.request().actor(),"list",null,1,null,null,null).get("nextCursor");}
  cancelCurrent();
  try(var c=database.apiConnection()){assertThrows(R1ServiceReadRuntime.Failure.class,()->service().read(c,seed.request().actor(),"list",null,1,cursor,null,null));}
  assertEquals("NONE",read("detail").get("taskState"));assertEquals(false,read("detail").get("canHandle"));
 }
 @Test void opaque_cursor_does_not_disclose_denied_scan_anchor()throws Exception {
  setupLedger("SALES_OPPORTUNITY_OWNER");deny(sourceLead,"SALES_OPPORTUNITY_OWNER");
  try(var c=database.apiConnection()){var page=service().read(c,seed.request().actor(),"list",null,1,null,null,null);assertEquals(List.of(),page.get("items"));String cursor=(String)page.get("nextCursor");assertNotNull(cursor);assertFalse(new String(Base64.getUrlDecoder().decode(cursor),java.nio.charset.StandardCharsets.UTF_8).contains(opportunity.id().toString()));assertEquals(List.of(),service().read(c,seed.request().actor(),"list",null,1,cursor,null,null).get("items"));}
 }
 @Test void unrelated_manager_grant_does_not_shadow_valid_own_sales_path()throws Exception {
  setupLedger("SALES_OPPORTUNITY_OWNER");UUID child=UUID.randomUUID();
  mutate("insert into identity.organization_unit (tenant_id,organization_unit_id,parent_organization_unit_id,unit_code,display_name,state,created_at) values (?,?,?,'LEDGER_OTHER','Other scope','ACTIVE',clock_timestamp())",seed.tenant(),child,seed.org());
  mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,'OPPORTUNITY_LEDGER_READ',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),seed.appointment(),seed.appointment(),child);
  assertEquals(true,read("detail").get("canHandle"));assertEquals(1,((List<?>)read("list").get("items")).size());
 }
 @Test void owner_identity_sales_deny_keeps_independent_read_but_blocks_handling()throws Exception {
  setupLedger("SALES_OPPORTUNITY_OWNER");Subject principal;
  try(var c=database.apiConnection()){principal=inTransaction(c,Capability.COMMAND,x->{grant(x,"OPPORTUNITY_LEDGER_READ");return WorkcardOwnerReader.databaseBacked().read(x,seed.tenant(),seed.appointment()).principal().selector();});}
  deny(principal,"SALES_OPPORTUNITY_OWNER");var detail=read("detail");assertTrue(detail.containsKey("customerLabel"));assertEquals(false,detail.get("canHandle"));assertFalse(detail.containsKey("task"));
 }
 @Test void audit_failure_never_returns_success_and_rolls_back_prior_disclosures()throws Exception {
  setupLedger("SALES_OPPORTUNITY_OWNER");long before=auditCount();var fails=new AuditAppender(){public void append(Connection c,Entry e)throws SQLException{throw new SQLException("Injected audit failure");}public void append(Connection c,OpportunityLedgerDisclosureEntry e)throws SQLException{throw new SQLException("Injected audit failure");}};
  try(var c=database.apiConnection()){assertThrows(SQLException.class,()->new R2OpportunityLedgerReadService(new byte[32],protection,opportunityProtection,fails).read(c,seed.request().actor(),"detail",opportunity.id(),20,null,null,null));}assertEquals(before,auditCount());
 }
}
