package io.github.windyzhu3.ontologylaw.audit.internal;

import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;

/** Closed projection: metadata is verified inside Audit Owner and never copied into display text. */
public final class AuditRecordSummary {
 private AuditRecordSummary(){}
 private static final Set<String> KNOWN=Set.of("R1_COMMAND_AUDIT_V1","R1_COMMAND_AUDIT_V2","R1_IDENTITY_COMMAND_AUDIT_V1","R1_IDENTITY_DISCLOSURE_V1","R1_IDENTITY_SELF_DISCLOSURE_V1","R1_IDENTITY_BOOTSTRAP_V1","ADM07_AUDIT_DISCLOSURE_V1","R2_OPPORTUNITY_OWNER_VALIDATION_V1","R2_WORKCARD_READ_DISCLOSURE_V1");
 public static String project(String schema,int version,String raw,byte[] expected){
  var parsed=ReceiptAuditJson.object(ReceiptAuditJson.parse(raw));
  if(expected==null||!MessageDigest.isEqual(expected,ReceiptAuditJson.digest(ReceiptAuditJson.encode(parsed))))throw new IllegalArgumentException("Invalid audit summary integrity");
  return version>=1&&version<=2&&KNOWN.contains(schema)?"已核验记录摘要；仅展示操作及结果，不展示原始业务内容。":"未识别的摘要版本；仅展示操作及结果。";
 }
 public static String name(String name){if(name==null||name.isBlank()||name.codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT)||name.codePointCount(0,name.length())>200)return "未提供显示名称";return name;}
 public static String objectLabel(String type){if(type==null)return "业务对象";return switch(type.split("\\.",2)[0]){case "identity"->"身份与授权";case "audit"->"审计记录";case "lead"->"线索";case "opportunity"->"商机";case "contract"->"合同";case "payment"->"收款";case "transfer"->"案管移交";case "responsibility"->"待办与办理";case "evidence"->"材料";case "party"->"客户资料";case "execution"->"业务执行";default->"业务对象";};}
 public static String actionLabel(String action){return Map.ofEntries(Map.entry("GET_SESSION_CONTEXT","确认当前身份"),Map.entry("CREATE_AUTHORITY_GRANT","授予任职权限"),Map.entry("REVOKE_AUTHORITY_GRANT","撤销任职权限"),Map.entry("CREATE_APPOINTMENT","创建任职"),Map.entry("CREATE_IDENTITY_PRINCIPAL","创建人员"),Map.entry("CREATE_ORGANIZATION_UNIT","创建组织"),Map.entry("LIST_AUTHORITY_GRANTS","查询任职授权"),Map.entry("LIST_APPOINTMENTS","查询任职"),Map.entry("LIST_IDENTITY_PRINCIPALS","查询人员"),Map.entry("LIST_ORGANIZATION_UNITS","查询组织"),Map.entry("READ_CURRENT_WORKCARD","查看工作卡"),Map.entry("LIST_AUDIT_RECORDS","查询审计记录"),Map.entry("GET_AUDIT_RECORD","查看审计详情"),Map.entry("LIST_RELATED_AUDIT_RECORDS","查看审计关系链")).getOrDefault(action,"执行操作");}
 public static String scopeLabel(String scope){return switch(scope){case "TENANT"->"事务所";case "ORGANIZATION"->"组织";case "OBJECT"->"对象";case "SECURITY"->"安全";default->"未识别范围";};}
 public static String resultLabel(String result){return switch(result){case "SUCCEEDED","COMPLETED"->"成功";case "NO_CHANGE"->"无变更";case "REJECTED"->"已拒绝";case "FAILED"->"失败";default->"未识别结果";};}
}
