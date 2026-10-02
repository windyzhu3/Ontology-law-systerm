package io.github.windyzhu3.ontologylaw.audit;
import io.github.windyzhu3.ontologylaw.audit.internal.AuditRecordSummary;
import io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AuditRecordSummaryTest {
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
