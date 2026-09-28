package io.github.windyzhu3.ontologylaw.opportunity.internal.persistence;
import io.github.windyzhu3.ontologylaw.opportunity.OpportunityOverviewReader;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.time.*;
import java.util.*;
public final class JdbcOpportunityOverviewReader implements OpportunityOverviewReader {
 public List<Candidate> scan(Connection c,UUID tenant,Instant from,Instant until,UUID after,int limit)throws SQLException {
  if(c.getAutoCommit())throw new SQLException("Overview requires an active read transaction","25000");
  if(tenant==null||from==null||until==null||!from.isBefore(until)||limit<1||limit>100)throw new IllegalArgumentException("Bounded overview period required");
  String sql="select opportunity_id,revision,created_at from opportunity.opportunity where tenant_id=? and created_at>=? and created_at<?"+(after==null?"":" and opportunity_id>?")+" order by opportunity_id limit ?";
  try(var p=c.prepareStatement(sql)){int n=1;p.setObject(n++,tenant);p.setObject(n++,from.atOffset(ZoneOffset.UTC));p.setObject(n++,until.atOffset(ZoneOffset.UTC));if(after!=null)p.setObject(n++,after);p.setInt(n,limit);var out=new ArrayList<Candidate>();try(var r=p.executeQuery()){while(r.next())out.add(new Candidate(new Subject("opportunity.opportunity",r.getObject(1,UUID.class),r.getLong(2),null),r.getObject(3,OffsetDateTime.class).toInstant()));}return List.copyOf(out);}
 }
}
