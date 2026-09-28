package io.github.windyzhu3.ontologylaw.audit;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.util.*;
/** Exact immutable validation evidence. Restricted audit view is never a public disclosure. */
public final class OpportunityOwnerValidationReader {
 private OpportunityOwnerValidationReader(){}
 public static UUID id(UUID tenant,UUID command){return UUID.nameUUIDFromBytes(("R2_OWNER_VALIDATION_V1:"+tenant+":"+command).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 public static Subject read(Connection c,UUID tenant,UUID id,Subject opportunity,Subject basis,boolean requireValid)throws SQLException {
  try(var p=c.prepareStatement("select change_summary::text,change_summary_digest from audit.audit_entry_classified_v where tenant_id=? and audit_entry_id=? and summary_schema_code='R2_OPPORTUNITY_OWNER_VALIDATION_V1' and summary_schema_version=1 and action_code='OBSERVE_OPPORTUNITY_OWNER_VALIDATION' and subject_type='opportunity.opportunity' and subject_id=? and subject_revision=?")){
   p.setObject(1,tenant);p.setObject(2,id);p.setObject(3,opportunity.id());p.setObject(4,opportunity.revision());try(var r=p.executeQuery()){
    if(!r.next())return null;var body=io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.object(io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.parse(r.getString(1)));
    if(!"R2_OPPORTUNITY_OWNER_VALIDATION_V1".equals(body.get("profile"))||requireValid&&!Boolean.TRUE.equals(body.get("validated")))return null;
    if(basis!=null&&!basis.equals(io.github.windyzhu3.ontologylaw.audit.ReceiptRecoveryMetadata.selector(body.get("basis"),basis.type(),false)))return null;
    var digest=r.getBytes(2);if(!java.security.MessageDigest.isEqual(digest,io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.digest(io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.encode(body))))throw new SQLException("Invalid validation audit digest","22000");
    return new Subject("audit.audit_entry",id,null,Base64.getUrlEncoder().withoutPadding().encodeToString(digest));
   }
  }
 }
}
