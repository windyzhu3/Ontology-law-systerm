package io.github.windyzhu3.ontologylaw.audit;
import io.github.windyzhu3.ontologylaw.audit.internal.AuditRecordSummary;
import io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AuditRecordSummaryTest {
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
