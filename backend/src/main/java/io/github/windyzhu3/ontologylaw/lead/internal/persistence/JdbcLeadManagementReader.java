package io.github.windyzhu3.ontologylaw.lead.internal.persistence;

import io.github.windyzhu3.ontologylaw.lead.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.util.*;
import static io.github.windyzhu3.ontologylaw.lead.LeadProtection.Field.*;

/** Explicit QUERY columns, including only the four V860 contact supplementation capabilities. */
public final class JdbcLeadManagementReader implements LeadManagementReader {
 private static final String METADATA="lead_id,revision,source_account_code,source_channel_code,captured_at,current_assignment_id,parsed_party_id";
 private final LeadProtection protection;
 public JdbcLeadManagementReader(LeadProtection protection){this.protection=Objects.requireNonNull(protection);}
 private static void transaction(Connection c)throws SQLException{if(c.getAutoCommit())throw new SQLException("Lead management requires an active transaction","25000");}
 public List<Metadata> scan(Connection c,UUID tenant,UUID after,int limit)throws SQLException {
  transaction(c);if(tenant==null||limit<1||limit>100)throw new IllegalArgumentException("Bounded tenant scan required");
  try(var p=c.prepareStatement("select "+METADATA+" from lead.lead where tenant_id=?"+(after==null?"":" and lead_id>?")+" order by lead_id limit ?")){
   int n=1;p.setObject(n++,tenant);if(after!=null)p.setObject(n++,after);p.setInt(n,limit);
   var result=new ArrayList<Metadata>();try(var r=p.executeQuery()){while(r.next())result.add(metadata(r));}return List.copyOf(result);
  }
 }
 public Metadata metadata(Connection c,UUID tenant,UUID id)throws SQLException {
  transaction(c);try(var p=c.prepareStatement("select "+METADATA+" from lead.lead where tenant_id=? and lead_id=?")){
   p.setObject(1,tenant);p.setObject(2,id);try(var r=p.executeQuery()){return r.next()?metadata(r):null;}
  }
 }
 private static Metadata metadata(ResultSet r)throws SQLException{return new Metadata(new Subject("lead.lead",r.getObject(1,UUID.class),r.getLong(2),null),r.getString(3),r.getString(4),r.getTimestamp(5).toInstant(),r.getObject(6,UUID.class),r.getObject(7,UUID.class));}
 public Summary summary(Connection c,UUID tenant,Subject exact)throws SQLException {
  transaction(c);if(exact==null||!"lead.lead".equals(exact.type())||exact.revision()==null||exact.hash()!=null)throw changed();
  try(var p=c.prepareStatement("select customer_name_ciphertext,contact_name_ciphertext,captured_name_ciphertext,captured_phone_ciphertext,captured_email_ciphertext,ingress_completion_phone_ciphertext,ingress_completion_email_ciphertext from lead.lead where tenant_id=? and lead_id=? and revision=?")){
   p.setObject(1,tenant);p.setObject(2,exact.id());p.setLong(3,exact.revision());try(var r=p.executeQuery()){
    if(!r.next())throw changed();
    String phone=protection.decrypt(tenant,CAPTURED_PHONE,r.getBytes(4)),email=protection.decrypt(tenant,CAPTURED_EMAIL,r.getBytes(5));
    if(phone==null||phone.isEmpty())phone=protection.decrypt(tenant,INGRESS_PHONE,r.getBytes(6));
    if(email==null||email.isEmpty())email=protection.decrypt(tenant,INGRESS_EMAIL,r.getBytes(7));
    return new Summary(protection.decrypt(tenant,CUSTOMER_NAME,r.getBytes(1)),protection.decrypt(tenant,CONTACT_NAME,r.getBytes(2)),protection.decrypt(tenant,CAPTURED_NAME,r.getBytes(3)),phone,email);
   }
  }
 }
 public Subject latestContact(Connection c,UUID tenant,UUID lead)throws SQLException {
  transaction(c);UUID id;try(var p=c.prepareStatement("select lead_contact_result_id from lead.lead_contact_result where tenant_id=? and lead_id=? order by contact_no desc,lead_contact_result_id desc limit 1")){
   p.setObject(1,tenant);p.setObject(2,lead);try(var r=p.executeQuery()){if(!r.next())return null;id=r.getObject(1,UUID.class);}
  }
  var record=CurrentLeadReader.databaseBacked(protection).contactResult(c,tenant,id);
  if(record==null||!record.leadId().equals(lead))throw changed();return record.selector();
 }
 private static SQLException changed(){return new SQLException("Exact lead source unavailable","40001");}
}
