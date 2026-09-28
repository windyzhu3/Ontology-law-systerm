package io.github.windyzhu3.ontologylaw.lead.internal.persistence;
import io.github.windyzhu3.ontologylaw.lead.LeadOverviewReader;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.time.*;
import java.util.*;
public final class JdbcLeadOverviewReader implements LeadOverviewReader {
 public List<Candidate> scan(Connection c,UUID tenant,Instant from,Instant until,UUID after,int limit)throws SQLException {
  if(c.getAutoCommit())throw new SQLException("Overview requires an active read transaction","25000");
  if(tenant==null||from==null||until==null||!from.isBefore(until)||limit<1||limit>100)throw new IllegalArgumentException("Bounded overview period required");
  String sql="select lead_id,revision,created_at from lead.lead where tenant_id=? and created_at>=? and created_at<?"+(after==null?"":" and lead_id>?")+" order by lead_id limit ?";
  try(var p=c.prepareStatement(sql)){int n=1;p.setObject(n++,tenant);p.setObject(n++,from.atOffset(ZoneOffset.UTC));p.setObject(n++,until.atOffset(ZoneOffset.UTC));if(after!=null)p.setObject(n++,after);p.setInt(n,limit);var out=new ArrayList<Candidate>();try(var r=p.executeQuery()){while(r.next())out.add(new Candidate(new Subject("lead.lead",r.getObject(1,UUID.class),r.getLong(2),null),r.getObject(3,OffsetDateTime.class).toInstant()));}return List.copyOf(out);}
 }
}
