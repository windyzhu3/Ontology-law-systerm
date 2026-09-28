package io.github.windyzhu3.ontologylaw.api;
import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import java.util.*;import org.junit.jupiter.api.Test;
class R2QuoteLedgerReadIT extends R2QuoteWorkflowIT {
 @Test void accepted_quote_ledger_selects_authorized_contract_preparation()throws Exception {
  var evidence=delivered();try(var c=database.apiConnection()){io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.inTransaction(c,io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.Capability.COMMAND,x->{grant(x,"CONTRACT_PREPARE");grant(x,"CONTRACT_READ");return null;});}
  quoteCommand("RECORD_QUOTE_RESPONSE",Map.of("kind","ACCEPTED","statement","接受本版报价","occurredAt",now().toString(),"evidence",R2CustomerRequirementsServices.selector(evidence)));
  var row=ledger();assertEquals("OPEN",row.get("taskState"));assertEquals(true,row.get("canHandle"));assertEquals("准备合同正文",row.get("nextActionLabel"));assertTrue(row.containsKey("dueAt"));
  assertEquals(scalar("select task_occurrence_id from responsibility.task_occurrence where tenant_id=? and subject_id=? and business_purpose_code='PREPARE_CONTRACT' and state='OPEN'",seed.tenant(),opportunity.id()),((Map<?,?>)row.get("task")).get("id"));
 }

 Map<String,Object> ledger()throws Exception{try(var c=database.apiConnection()){return new R2OpportunityLedgerReadService(new byte[32],protection,cipher,AuditAppender.databaseBacked("T07_LEDGER_IT")).read(c,seed.request().actor(),"detail",opportunity.id(),30,null,null,null);}}
 @Test void ledger_tracks_approval_delivery_and_exact_current_task()throws Exception{
  setup(true,true);confirmed();policy("REQUIRE_APPROVAL",List.of(seed.appointment()));quoteCommand("FORM_QUOTE",commercial());
  var before=ledger();assertEquals("提交报价审批",before.get("nextActionLabel"));assertEquals("OPEN",before.get("taskState"));assertEquals(true,before.get("canHandle"));var task=before.get("task");
  quoteCommand("REQUEST_QUOTE_APPROVAL",Map.of());var during=ledger();assertEquals("审批报价",during.get("nextActionLabel"));assertNotEquals(task,during.get("task"));assertEquals(true,during.get("canHandle"));
  quoteCommand("RECORD_QUOTE_DECISION",Map.of("decision","APPROVED","reason","同意"));assertEquals("人工送达报价并记录凭据",ledger().get("nextActionLabel"));
 }
 @Test void accepted_boundary_has_no_old_task_or_due_date()throws Exception{
  var evidence=delivered();quoteCommand("RECORD_QUOTE_RESPONSE",Map.of("kind","ACCEPTED","statement","接受","occurredAt",now().toString(),"evidence",R2CustomerRequirementsServices.selector(evidence)));
  var row=ledger();assertEquals("NONE",row.get("taskState"));assertEquals(false,row.get("canHandle"));assertEquals("报价已接受，待衔接合同准备",row.get("nextActionLabel"));assertFalse(row.containsKey("dueAt"));assertFalse(row.containsKey("task"));
 }
 @Test void missing_quote_read_suppresses_quote_metadata_and_old_task()throws Exception{
  setup(true,true);confirmed();quoteCommand("FORM_QUOTE",commercial());var row=ledger();assertEquals("NONE",row.get("taskState"));assertEquals(false,row.get("canHandle"));assertFalse(row.containsKey("nextActionLabel"));assertFalse(row.containsKey("dueAt"));assertFalse(row.containsKey("task"));
 }
 @Test void exact_quote_deny_hides_metadata_without_disclosing_old_followup()throws Exception{
  setup(true,true);confirmed();policy("REQUIRE_APPROVAL",List.of(seed.appointment()));quoteCommand("FORM_QUOTE",commercial());
  quoteCommand("REQUEST_QUOTE_APPROVAL",Map.of());
  var workflow=(Map<?,?>)((Map<?,?>)context().get("workflow")).get("selector");
  mutate("insert into identity.object_access_grant(tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values(?,?,?,?,'QUOTE_READ','DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),'opportunity.quote_workflow',?,0)",seed.tenant(),UUID.randomUUID(),seed.request().actor().principalId(),seed.appointment(),UUID.fromString((String)workflow.get("id")));
  var row=ledger();assertEquals("NONE",row.get("taskState"));assertEquals(false,row.get("canHandle"));assertFalse(row.containsKey("nextActionLabel"));assertFalse(row.containsKey("task"));
 }
 @Test void waiting_reply_uses_next_check_and_never_wakes_on_read()throws Exception{
  var evidence=delivered();var due=now().plusSeconds(86400);quoteCommand("RECORD_QUOTE_RESPONSE",Map.of("kind","NOT_ACCEPTED","statement","稍后回复","occurredAt",now().toString(),"nextCheckAt",due.toString(),"evidence",R2CustomerRequirementsServices.selector(evidence)));
  var row=ledger();assertEquals("WAITING",row.get("taskState"));assertEquals("跟进客户报价回复",row.get("nextActionLabel"));assertEquals(due.toString(),row.get("dueAt"));assertEquals(false,row.get("canHandle"));
 }
}

