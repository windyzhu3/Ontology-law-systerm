package io.github.windyzhu3.ontologylaw.api;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
/** True query/audit role tests; closure creation here prepares isolated read fixtures. */
class R2OpportunityClosureReadIT extends R1HttpFixture {
 private Subject opportunity;
 private void setupClosure(boolean task,boolean closable)throws Exception {
  setupContact();var result=execute(prepare(contact("CONNECTED_VALID")));
  opportunityProtection=OpportunityProgressProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES"));
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{opportunity=EventOpportunityReader.databaseBacked().forContact(x,seed.tenant(),result.resultFact().id()).selector();grant(x,"SALES_OPPORTUNITY_OWNER");if(closable)grant(x,"OPPORTUNITY_CLOSE");if(task)current=TaskFactory.databaseBacked().createInitialOpportunity(x,seed.tenant(),seed.appointment(),opportunity,ZoneId.of("Asia/Shanghai"),businessAt);return null;});}
 }
 private Map<String,Object> read()throws Exception{try(var c=database.apiConnection()){return new R2OpportunityClosureReadService(new byte[32],protection,opportunityProtection,AuditAppender.databaseBacked("T04_READ_IT")).read(c,seed.request().actor(),opportunity.id());}}
 private OpportunityClosureService.Closure closeFixture()throws Exception {try(var c=database.apiConnection()){return inTransaction(c,Capability.COMMAND,x->{var service=io.github.windyzhu3.ontologylaw.api.R2OpportunityClosureServices.create(opportunityProtection);var s=service.inspect(x,seed.tenant(),opportunity.id());return service.close(x,seed.tenant(),new OpportunityClosureService.Input(s.opportunity(),s.responsibility().basis(),s.task(),s.waitReceipt(),seed.appointment(),"CLIENT_DECLINED","客户已明确结束本次洽谈。"));});}}
 @Test void ledger_read_does_not_grant_close_or_expose_selectors()throws Exception {setupClosure(true,false);var before=counts();var r=read();assertEquals("READ_ONLY",r.get("status"));assertEquals(Set.of("opportunity","status"),r.keySet());var after=counts();for(int i=0;i<before.size();i++)if(i!=8)assertEquals(before.get(i),after.get(i));}
 @Test void no_task_ready_preserves_explicit_null_preconditions_and_does_not_create_task()throws Exception {setupClosure(false,true);var r=read();assertEquals("READY",r.get("status"));assertTrue(r.containsKey("expectedTask"));assertNull(r.get("expectedTask"));assertTrue(r.containsKey("expectedWait"));assertNull(r.get("expectedWait"));assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=?",seed.tenant(),opportunity.id()));}
 @Test void exact_close_task_deny_keeps_read_only_and_no_write_tokens()throws Exception {setupClosure(true,true);deny(current.selector(),"OPPORTUNITY_CLOSE");assertEquals("READ_ONLY",read().get("status"));assertFalse(read().containsKey("expectedTask"));}
 @Test void closed_fact_is_visible_and_exact_deny_precedes_summary_decryption()throws Exception {setupClosure(true,true);var closed=closeFixture();var r=read();assertEquals("CLOSED",r.get("status"));assertEquals("客户已明确结束本次洽谈。",((Map<?,?>)r.get("closure")).get("summary"));deny(closed.selector(),"SALES_OPPORTUNITY_OWNER");opportunityProtection=new OpportunityProgressProtection(){public byte[] encrypt(UUID t,UUID o,UUID p,String b){throw new AssertionError();}public String decrypt(UUID t,UUID o,UUID p,byte[] b){throw new AssertionError();}public String decryptClosure(UUID t,UUID o,UUID p,byte[] b){throw new AssertionError("Denied closure decrypted");}};r=read();assertEquals("CLOSED",r.get("status"));assertFalse(r.containsKey("closure"));}
 @Test void closure_disclosure_audit_failure_rolls_back_and_never_returns_context()throws Exception {setupClosure(true,true);long before=auditCount();var fail=new AuditAppender(){public void append(Connection c,Entry e)throws SQLException{throw new SQLException("Injected");}public void append(Connection c,OpportunityClosureDisclosureEntry e)throws SQLException{throw new SQLException("Injected closure audit");}};try(var c=database.apiConnection()){assertThrows(SQLException.class,()->new R2OpportunityClosureReadService(new byte[32],protection,opportunityProtection,fail).read(c,seed.request().actor(),opportunity.id()));}assertEquals(before,auditCount());}
}
