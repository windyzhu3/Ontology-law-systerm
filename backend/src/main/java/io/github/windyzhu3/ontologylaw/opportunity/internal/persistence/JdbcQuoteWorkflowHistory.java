package io.github.windyzhu3.ontologylaw.opportunity.internal.persistence;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.util.*;
import java.security.MessageDigest;
/** Exact confirmed text only; the caller authorizes and audits this selector before disclosure. */
public final class JdbcQuoteWorkflowHistory {
 private JdbcQuoteWorkflowHistory(){}
 public static String confirmedReason(Connection c,UUID tenant,UUID opportunity,Subject exact,OpportunityProgressProtection protection,QuoteDraftService.Codec codec)throws SQLException {
  boolean decision="opportunity.quote_approval_decision".equals(exact.type()),reply="opportunity.quote_response".equals(exact.type());
  if(!decision&&!reply)return null;
  if(c.getAutoCommit())throw new SQLException("Query transaction required","25000");
  var metadata=JdbcQuoteWorkflowMetadata.read(c,tenant,exact);
  if(metadata==null||!metadata.opportunityId().equals(opportunity))throw invalid();
  if(protection==null)throw new SQLException("Quote history protection unavailable","55000");
  String sql=decision?"select reason_ciphertext,reason_digest from opportunity.quote_approval_decision where tenant_id=? and quote_approval_decision_id=?":"select response_content_ciphertext,response_content_digest from opportunity.quote_response where tenant_id=? and quote_response_id=?";
  try(var p=c.prepareStatement(sql)){p.setObject(1,tenant);p.setObject(2,exact.id());try(var row=p.executeQuery()){
   if(!row.next())throw invalid();String clear=protection.decryptQuote(tenant,opportunity,exact.id(),false,row.getBytes(1));
   if(!MessageDigest.isEqual(QuoteCanonicalJson.digest(clear),row.getBytes(2)))throw invalid();
   if(decision)return clear;
   var body=codec.decode(clear);
   if(!(body.get("statement") instanceof String statement))throw invalid();return statement;
  }}
 }
 private static SQLException invalid(){return new SQLException("Exact quote history source unavailable","22000");}
}
