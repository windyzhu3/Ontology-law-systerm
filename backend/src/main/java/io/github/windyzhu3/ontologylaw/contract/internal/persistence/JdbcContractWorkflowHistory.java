package io.github.windyzhu3.ontologylaw.contract.internal.persistence;
import io.github.windyzhu3.ontologylaw.contract.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.util.*;
import java.security.MessageDigest;
/** Bounded projection of the authorized immutable record; never reads a newer decision. */
public final class JdbcContractWorkflowHistory {
 private JdbcContractWorkflowHistory(){}
 public static String confirmedReason(Connection c,UUID tenant,UUID opportunity,Subject exact,ContractProtection protection,ContractPreparationRepository.Codec codec)throws SQLException {
  ContractProtection.Kind kind=switch(exact.type()){
   case "contract.preparation_request"->ContractProtection.Kind.REQUEST;
   case "contract.preparation_decision","contract.revision_approval_decision"->ContractProtection.Kind.DECISION;
   case "contract.revision_review_request","contract.revision_review_decision"->ContractProtection.Kind.REVIEW;
   case "contract.signature_verification","contract.signature_archive"->ContractProtection.Kind.SIGNATURE;
   case "contract.negotiation_disposition"->ContractProtection.Kind.NEGOTIATION;
   default->null;
  };if(kind==null)return null;
  if(c.getAutoCommit())throw new SQLException("Query transaction required","25000");
  var metadata=JdbcContractWorkflowMetadata.read(c,tenant,exact);
  if(metadata==null||!metadata.opportunityId().equals(opportunity))throw invalid();
  if(protection==null)throw new SQLException("Contract history protection unavailable","55000");
  // The identifier is drawn only from the explicit Owner-owned selector switch above.
  String table=exact.type().substring(9);
  try(var p=c.prepareStatement("select body_ciphertext,body_digest from contract."+table+" where tenant_id=? and "+table+"_id=?")){
   p.setObject(1,tenant);p.setObject(2,exact.id());try(var row=p.executeQuery()){
    if(!row.next())throw invalid();String clear=protection.open(tenant,opportunity,exact.id(),kind,row.getBytes(1));
    if(!MessageDigest.isEqual(ContractCanonicalJson.digest(clear),row.getBytes(2)))throw invalid();
    var body=codec.decode(clear);
    Object reason=body.get(kind==ContractProtection.Kind.NEGOTIATION?"summary":"reason");
    if(!(reason instanceof String text))throw invalid();return text;
   }
  }
 }
 private static SQLException invalid(){return new SQLException("Exact contract history source unavailable","22000");}
}
