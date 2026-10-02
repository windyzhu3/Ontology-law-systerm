package io.github.windyzhu3.ontologylaw.audit;
import io.github.windyzhu3.ontologylaw.audit.internal.AuditRecordSummary;
import io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AuditRecordSummaryTest {
 @Test void frozen_workcard_writer_digests_survive_jsonb_key_reordering(){
  for(String type:java.util.List.of("lead.lead","opportunity.opportunity")){
   var source=new io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject(type,java.util.UUID.randomUUID(),0L,null);var actor=new io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor(java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),null,null);
   var request=new io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Request(actor,source,java.util.UUID.randomUUID(),new io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Requirement(type.startsWith("opportunity")?"QUOTE_PREPARE":"LEAD_CONTACT_SELF",type.startsWith("opportunity")?"OPPORTUNITY_OWNER":"CONTACT_OWNER",io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Path.DIRECT,java.util.UUID.randomUUID()));
   var auth=new io.github.windyzhu3.ontologylaw.identity.AuthorizationSnapshot(request,java.time.Instant.now(),true,null,null,"fixture",new byte[32]);
   var entry=new AuditAppender.ReadDisclosureEntry(java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),source,source,auth,AuditAppender.ResponseMode.BODY);
   String reordered=ReceiptAuditJson.encode(ReceiptAuditJson.parse(entry.summary()));assertNotEquals(entry.summary(),reordered);
   String safe=AuditRecordSummary.project(entry.schemaCode(),1,reordered,entry.summaryDigest());assertTrue(safe.contains("读取"));assertFalse(safe.contains(source.id().toString()));
   assertThrows(IllegalArgumentException.class,()->AuditRecordSummary.project(entry.schemaCode(),1,reordered,new byte[32]));
  }
 }
 @Test void registered_identity_operations_have_distinct_business_actions_and_safe_result_summaries(){
  assertEquals("更改人员名称",AuditRecordSummary.actionLabel("RENAME_IDENTITY_PRINCIPAL"));assertEquals("挂起人员主体",AuditRecordSummary.actionLabel("SUSPEND_IDENTITY_PRINCIPAL"));
  String raw="{\"result\":{\"outcome\":\"NO_CHANGE\",\"resultFact\":\"SECRET_CONTACT\"},\"authorizationEvidence\":\"HMAC_SECRET\",\"receiptRecovery\":{\"target\":\"PRIVATE_BODY\"}}";
  String safe=AuditRecordSummary.project("R1_IDENTITY_COMMAND_AUDIT_V1",1,raw,ReceiptAuditJson.digest(ReceiptAuditJson.encode(ReceiptAuditJson.parse(raw))));assertTrue(safe.contains("无变更"));assertFalse(safe.contains("SECRET"));assertFalse(safe.contains("PRIVATE_BODY"));
 }
 @Test void unknown_schema_never_projects_raw_metadata_and_corruption_fails_closed(){
  String raw="{\"authorizationEvidence\":\"token SECRET\",\"sourceRecordKeyDigest\":\"HMAC\"}";
  var safe=AuditRecordSummary.project("FUTURE_SCHEMA",1,raw,ReceiptAuditJson.digest(ReceiptAuditJson.encode(ReceiptAuditJson.parse(raw))));
  assertFalse(safe.contains("SECRET"));assertFalse(safe.contains("HMAC"));assertTrue(safe.contains("未识别"));
  assertThrows(IllegalArgumentException.class,()->AuditRecordSummary.project("FUTURE_SCHEMA",1,raw,new byte[32]));
 }
 @Test void labels_are_static_and_names_reject_controls(){
  assertEquals("业务对象",AuditRecordSummary.objectLabel("future.private_table"));
  assertEquals("未提供显示名称",AuditRecordSummary.name("person\nsecret"));
  assertEquals("执行操作",AuditRecordSummary.actionLabel("TOKEN_SECRET"));
 }
}
