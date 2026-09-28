package io.github.windyzhu3.ontologylaw.identity.internal.persistence;
import io.github.windyzhu3.ontologylaw.identity.ContractAuthorityCandidates;
import java.sql.*;import java.util.*;
public final class JdbcContractAuthorityCandidates implements ContractAuthorityCandidates {
 public List<UUID> appointments(Connection c,UUID tenant,String authority,UUID after,int limit)throws SQLException{
  if(limit<1||limit>100||!Set.of("TRANSFER_SUBMIT","TRANSFER_REVIEW","TRANSFER_ACCEPT","MATTER_CLASSIFY","MATTER_RECEIVE","PAYMENT_CONFIRM","PAYMENT_SUBMIT","CONTRACT_PREPARE","CONTRACT_PREPARATION_DECIDE","CONTRACT_REVIEW","CONTRACT_APPROVE","CONTRACT_SIGNATURE_VERIFY","CONTRACT_EXECUTION_VERIFY","CONTRACT_READ","CONTRACT_TERMINATION_REVIEW").contains(authority))throw new IllegalArgumentException("Invalid contract authority page");
  try(var p=c.prepareStatement("select distinct a.appointment_id from identity.appointment a join identity.principal p on p.tenant_id=a.tenant_id and p.principal_id=a.principal_id join identity.authority_grant g on g.tenant_id=a.tenant_id and g.grantee_appointment_id=a.appointment_id where a.tenant_id=? and a.state='ACTIVE' and p.state='ACTIVE' and p.principal_kind='HUMAN' and g.state='ACTIVE' and g.authority_code=?"+(after==null?"":" and a.appointment_id>?")+" order by a.appointment_id limit ?")){
   p.setObject(1,tenant);p.setString(2,authority);int index=3;if(after!=null)p.setObject(index++,after);p.setInt(index,limit);
   var result=new ArrayList<UUID>();try(var rows=p.executeQuery()){while(rows.next())result.add(rows.getObject(1,UUID.class));}return List.copyOf(result);
  }
 }
}
