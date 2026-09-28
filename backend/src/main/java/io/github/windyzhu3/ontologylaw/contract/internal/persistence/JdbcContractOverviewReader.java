package io.github.windyzhu3.ontologylaw.contract.internal.persistence;
import io.github.windyzhu3.ontologylaw.contract.ContractOverviewReader;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.time.*;
import java.util.*;
/** All joins are within Contract. First archive is selected before filtering the requested month. */
public final class JdbcContractOverviewReader implements ContractOverviewReader {
 public List<Candidate> scan(Connection c,UUID tenant,Instant from,Instant until,UUID after,int limit)throws SQLException {
  if(c.getAutoCommit())throw new SQLException("Overview requires an active read transaction","25000");
  if(tenant==null||from==null||until==null||!from.isBefore(until)||limit<1||limit>100)throw new IllegalArgumentException("Bounded overview period required");
  String sql="select root.contract_id,root.revision,root.opportunity_id,f.signature_archive_id,f.archive_revision,f.arrangement_id,f.arrangement_revision,f.contract_revision_id,f.content_digest,f.created_at from contract.contract root join lateral (select a.signature_archive_id,a.revision archive_revision,a.arrangement_id,p.revision arrangement_revision,p.contract_revision_id,v.content_digest,a.created_at from contract.signature_archive a join contract.signature_arrangement p on p.tenant_id=a.tenant_id and p.signature_arrangement_id=a.arrangement_id join contract.contract_revision v on v.tenant_id=p.tenant_id and v.contract_revision_id=p.contract_revision_id where a.tenant_id=root.tenant_id and v.contract_id=root.contract_id and a.opportunity_id=root.opportunity_id order by a.created_at,a.signature_archive_id limit 1) f on true where root.tenant_id=? and f.created_at>=? and f.created_at<?"+(after==null?"":" and root.contract_id>?")+" order by root.contract_id limit ?";
  try(var p=c.prepareStatement(sql)){int n=1;p.setObject(n++,tenant);p.setObject(n++,from.atOffset(ZoneOffset.UTC));p.setObject(n++,until.atOffset(ZoneOffset.UTC));if(after!=null)p.setObject(n++,after);p.setInt(n,limit);var out=new ArrayList<Candidate>();try(var r=p.executeQuery()){while(r.next()){
   var id=r.getObject(1,UUID.class);var facts=List.of(new Subject("contract.contract",id,r.getLong(2),null),new Subject("contract.signature_archive",r.getObject(4,UUID.class),r.getLong(5),null),new Subject("contract.signature_arrangement",r.getObject(6,UUID.class),r.getLong(7),null),new Subject("contract.contract_revision",r.getObject(8,UUID.class),null,Base64.getUrlEncoder().withoutPadding().encodeToString(r.getBytes(9))));
   out.add(new Candidate(id,r.getObject(3,UUID.class),r.getObject(10,OffsetDateTime.class).toInstant(),facts));
  }}return List.copyOf(out);}
 }
}
