package io.github.windyzhu3.ontologylaw.api;
import java.util.*;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
class R2TransferHistoryIT extends R2TransferAuthorityIT {
 @Test void completed_ledger_projection_does_not_build_unused_classification_command_facts()throws Exception{
  real_transfer_authority_closes_submit_review_accept_classify_chain();
  var contract=UUID.fromString(scalar("select contract_id from transfer.transfer_request where tenant_id=?",seed.tenant()));
  try(var c=database.apiConnection()){
   var wrapped=(java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(java.sql.Connection.class.getClassLoader(),new Class<?>[]{java.sql.Connection.class},(p,m,args)->{
    if(m.getName().equals("prepareStatement")&&((String)args[0]).contains("from contract.preparation_request"))fail("Completed ledger needs transfer disclosure facts, not classification command preparation facts");
    try{return m.invoke(c,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
   });
   var probe=new ReadConnectionProbe(wrapped);
   var value=io.github.windyzhu3.ontologylaw.execution.ContractReadRuntime.read(probe.connection(),actor(),AuditAppender.databaseBacked("LEDGER_MINIMAL"),(tx,now)->{
    var context=new LinkedHashMap<String,Object>();var disclosures=new ArrayList<AuditAppender.ContractDisclosureEntry>();
    assertEquals("案件分类及承接已确认",R2TransferHistoryProjection.append(tx,actor(),contract,context,disclosures,false));
    assertFalse(disclosures.isEmpty());assertEquals(false,((Map<?,?>)context.get("transfer")).get("canHandle"));
    return new io.github.windyzhu3.ontologylaw.execution.ContractReadRuntime.Prepared<>(context,disclosures);
   });assertNotNull(value.get("transfer"));
   assertEquals(3,probe.statements.stream().filter("select clock_timestamp()"::equals).count(),"One runtime clock plus one complete stable authorization batch, independent of disclosed fact count");
  }
 }
 @Test void completed_transfer_is_visible_to_sales_in_existing_contract_history_and_ledger()throws Exception{
  real_transfer_authority_closes_submit_review_accept_classify_chain();
  var reads=new R2ContractReadService(R2ContractServices.create(protectedBodies,cipher,null),AuditAppender.databaseBacked("F11_HISTORY"));
  try(var c=database.apiConnection()){var context=reads.read(c,actor(),opportunity.id());var history=(List<?>)context.get("history");assertTrue(history.stream().anyMatch(raw->"转案接收".equals(((Map<?,?>)raw).get("label"))));assertTrue(history.stream().anyMatch(raw->"案件分类及承接".equals(((Map<?,?>)raw).get("label"))&&((String)((Map<?,?>)raw).get("summary")).contains("综法业务")));}
  try(var c=database.apiConnection()){var rows=(List<?>)reads.ledger(c,actor()).get("items");assertTrue(rows.stream().anyMatch(raw->"案件分类及承接已确认".equals(((Map<?,?>)raw).get("stateLabel"))));}
 }
 @Test void correction_entry_is_only_exposed_to_authorized_case_manager()throws Exception{
  real_transfer_authority_closes_submit_review_accept_classify_chain();
  mutate("insert into identity.authority_grant(tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values(?,?,?,?,?,'CONTRACT_READ',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),reviewer,seed.appointment(),seed.org());
  var reads=new R2ContractReadService(R2ContractServices.create(protectedBodies,cipher,null),AuditAppender.databaseBacked("Q1_ENTRY"));
  try(var c=database.apiConnection()){assertEquals(true,((Map<?,?>)reads.read(c,reviewerActor(),opportunity.id()).get("transfer")).get("canCorrectClassification"));}
  try(var c=database.apiConnection()){assertEquals(false,((Map<?,?>)reads.read(c,actor(),opportunity.id()).get("transfer")).get("canCorrectClassification"));}
  mutate("update identity.authority_grant set state='REVOKED',revision=revision+1,revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE' where tenant_id=? and grantee_appointment_id=? and authority_code='MATTER_CLASSIFY'",seed.tenant(),reviewer);
  try(var c=database.apiConnection()){assertEquals(false,((Map<?,?>)reads.read(c,reviewerActor(),opportunity.id()).get("transfer")).get("canCorrectClassification"));}
 }
 @Test void pending_transfer_ledger_targets_only_the_exact_authorized_task()throws Exception{
  beginTransfer();try(var c=database.apiConnection()){io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.inTransaction(c,io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.Capability.COMMAND,x->{grant(x,"TRANSFER_SUBMIT");grant(x,"CONTRACT_READ");return null;});}
  var reads=new R2ContractReadService(R2ContractServices.create(protectedBodies,cipher,null),AuditAppender.databaseBacked("F11_ENTRY"));
  try(var c=database.apiConnection()){var context=reads.read(c,actor(),opportunity.id());var transfer=(Map<?,?>)context.get("transfer");assertNotNull(transfer);assertEquals(true,transfer.get("canHandle"));assertEquals(scalar("select task_id from transfer.workflow where tenant_id=?",seed.tenant()),((Map<?,?>)transfer.get("task")).get("id"));}
  try(var c=database.apiConnection()){var rows=(List<?>)reads.ledger(c,actor()).get("items");assertTrue(rows.stream().anyMatch(raw->"待准备转案资料".equals(((Map<?,?>)raw).get("stateLabel"))&&Boolean.TRUE.equals(((Map<?,?>)raw).get("canHandle"))));}
  mutate("update identity.authority_grant set state='REVOKED',revision=revision+1,revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE' where tenant_id=? and grantee_appointment_id=? and authority_code='TRANSFER_SUBMIT'",seed.tenant(),seed.appointment());
  try(var c=database.apiConnection()){var context=reads.read(c,actor(),opportunity.id());var transfer=(Map<?,?>)context.get("transfer");assertEquals(false,transfer.get("canHandle"));assertNull(transfer.get("task"));}
 }

}
