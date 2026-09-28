package io.github.windyzhu3.ontologylaw.lead.internal.persistence;
import io.github.windyzhu3.ontologylaw.lead.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.util.*;
public final class JooqOpportunityLedgerLeadReader implements OpportunityLedgerLeadReader {
 private final LeadProtection protection;
 public JooqOpportunityLedgerLeadReader(LeadProtection p){protection=Objects.requireNonNull(p);}
 public Metadata metadata(Connection c,UUID tenant,UUID id)throws SQLException{
  return readMetadata(c,tenant,id);
 }
 public static Metadata readMetadata(Connection c,UUID tenant,UUID id)throws SQLException{
  try(var p=c.prepareStatement("select revision,parsed_party_id from lead.lead where tenant_id=? and lead_id=?")){p.setObject(1,tenant);p.setObject(2,id);try(var r=p.executeQuery()){return r.next()?new Metadata(new Subject("lead.lead",id,r.getLong(1),null),r.getObject(2,UUID.class)):null;}}
 }
 public String label(Connection c,UUID tenant,Subject exact)throws SQLException {
  try(var p=c.prepareStatement("select customer_name_ciphertext,captured_name_ciphertext from lead.lead where tenant_id=? and lead_id=? and revision=?")){
   p.setObject(1,tenant);p.setObject(2,exact.id());p.setLong(3,exact.revision());try(var r=p.executeQuery()){if(!r.next())throw new SQLException("Lead changed","40001");String value=protection.decrypt(tenant,LeadProtection.Field.CUSTOMER_NAME,r.getBytes(1));if(value==null)value=protection.decrypt(tenant,LeadProtection.Field.CAPTURED_NAME,r.getBytes(2));return value==null?"客户委托":value;}
  }
 }
}
