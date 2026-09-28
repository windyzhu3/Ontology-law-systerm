package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;
class TeamQuoteHistoryIT extends R2QuoteWorkflowIT {
 @Test void approval_history_keeps_the_original_reason_after_a_new_version()throws Exception {
  setup(true,true);confirmed();policy("REQUIRE_APPROVAL",List.of(seed.appointment()));quoteCommand("FORM_QUOTE",commercial());quoteCommand("REQUEST_QUOTE_APPROVAL",Map.of());
  var decision=quoteCommand("RECORD_QUOTE_DECISION",Map.of("decision","RETURNED","reason","原版需要调整付款安排"));
  quoteCommand("FORM_QUOTE",commercial());quoteCommand("REQUEST_QUOTE_APPROVAL",Map.of());quoteCommand("RECORD_QUOTE_DECISION",Map.of("decision","APPROVED","reason","新版本同意"));
  assertHistoryReason(decision,"原版需要调整付款安排");
 }
 @Test void reply_history_reads_the_exact_confirmed_customer_statement()throws Exception {
  var evidence=delivered();var reply=quoteCommand("RECORD_QUOTE_RESPONSE",Map.of("kind","ACCEPTED","statement","客户接受本次准确报价","occurredAt",now().toString(),"evidence",R2CustomerRequirementsServices.selector(evidence)));
  assertHistoryReason(reply,"客户接受本次准确报价");
 }
 private void assertHistoryReason(Subject completion,String expected)throws Exception {
  UUID task=UUID.fromString(scalar("select task_occurrence_id from responsibility.task_occurrence where tenant_id=? and completion_fact_id=?",seed.tenant(),completion.id()));
  try(var c=database.apiConnection()){
   inTransaction(c,Capability.COMMAND,x->{grant(x,"TEAM_TASK_READ");return null;});
   var detail=new R2TeamManagementReadService(new byte[32],protection,cipher,AuditAppender.databaseBacked("TEAM_QUOTE_HISTORY")).detail(c,seed.request().actor(),"history",task);
   assertNull(detail.get("action"));assertTrue(((List<?>)detail.get("facts")).contains(List.of("原因或说明",expected)),"The history must use the original exact record");
   var failing=new R2TeamManagementReadService(new byte[32],protection,cipher,(tx,entry)->{});
   assertThrows(java.sql.SQLException.class,()->failing.detail(c,seed.request().actor(),"history",task));
   var bomb=new io.github.windyzhu3.ontologylaw.opportunity.OpportunityProgressProtection(){
    public byte[] encrypt(UUID t,UUID o,UUID f,String text){throw new AssertionError();}
    public String decrypt(UUID t,UUID o,UUID f,byte[] bytes){throw new AssertionError("Denied history was decrypted");}
   };
   inTransaction(c,Capability.QUERY,x->{
    assertThrows(java.sql.SQLException.class,()->io.github.windyzhu3.ontologylaw.opportunity.QuoteWorkflowService.confirmedReason(x,UUID.randomUUID(),opportunity.id(),completion,bomb,R2TeamTaskResolver.HISTORY_CODEC));
    assertThrows(java.sql.SQLException.class,()->io.github.windyzhu3.ontologylaw.opportunity.QuoteWorkflowService.confirmedReason(x,seed.tenant(),UUID.randomUUID(),completion,bomb,R2TeamTaskResolver.HISTORY_CODEC));return null;
   });
   mutate("insert into identity.object_access_grant(tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision,object_subject_hash) values(?,?,?,?,'TEAM_TASK_READ','DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),?,?,?,?)",seed.tenant(),UUID.randomUUID(),seed.request().actor().principalId(),seed.appointment(),completion.type(),completion.id(),completion.revision(),completion.hash()==null?null:Base64.getUrlDecoder().decode(completion.hash()));
   var denied=new R2TeamManagementReadService(new byte[32],protection,bomb,AuditAppender.databaseBacked("TEAM_HISTORY_DENY"));
   var failure=assertThrows(io.github.windyzhu3.ontologylaw.execution.R1ServiceReadRuntime.Failure.class,()->denied.detail(c,seed.request().actor(),"history",task));assertEquals(403,failure.status());
  }
 }
}
