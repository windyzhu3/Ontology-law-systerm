package io.github.windyzhu3.ontologylaw.opportunity.internal.persistence;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.time.*;
import java.util.*;
public final class JooqOpportunityLedgerReader implements OpportunityLedgerReader {
 private final OpportunityCommandReader bodies;
 public JooqOpportunityLedgerReader(OpportunityProgressProtection p){bodies=OpportunityCommandReader.databaseBacked(p);}
 public List<Position> scan(Connection c,UUID tenant,Instant observed,Position after,int limit)throws SQLException {
  if(limit<1||limit>100)throw new IllegalArgumentException("Invalid bound");
  try(var p=c.prepareStatement("select created_at,opportunity_id from opportunity.opportunity where tenant_id=? and created_at<=?"+(after==null?"":" and (created_at,opportunity_id)>(?,?)")+" order by created_at,opportunity_id limit ?")){
   p.setObject(1,tenant);p.setObject(2,OffsetDateTime.ofInstant(observed,ZoneOffset.UTC));int n=3;
   if(after!=null){p.setObject(n++,OffsetDateTime.ofInstant(after.at(),ZoneOffset.UTC));p.setObject(n++,after.id());}p.setInt(n,limit);
   var out=new ArrayList<Position>();try(var r=p.executeQuery()){while(r.next())out.add(new Position(r.getObject(1,OffsetDateTime.class).toInstant(),r.getObject(2,UUID.class)));}return List.copyOf(out);
  }
 }
 public Header header(Connection c,UUID t,UUID id)throws SQLException{var h=bodies.header(c,t,id);return h==null?null:new Header(h.selector(),h.closed());}
 public Progress latestProgress(Connection c,UUID t,UUID id)throws SQLException {
  try(var p=c.prepareStatement("select opportunity_progress_id,progress_digest,occurred_at from opportunity.opportunity_progress where tenant_id=? and opportunity_id=? order by progress_no desc limit 1")){
   p.setObject(1,t);p.setObject(2,id);try(var r=p.executeQuery()){return r.next()?new Progress(new Subject("opportunity.opportunity_progress",r.getObject(1,UUID.class),null,Base64.getUrlEncoder().withoutPadding().encodeToString(r.getBytes(2))),r.getObject(3,OffsetDateTime.class).toInstant()):null;}
  }
 }
 public String progressBody(Connection c,UUID t,UUID opportunity,Subject exact)throws SQLException {
  var p=bodies.progress(c,t,exact.id());if(p==null||!p.opportunity().equals(opportunity)||!p.selector().equals(exact))throw new SQLException("Progress source changed","40001");return p.canonicalBody();
 }
 public List<Progress> progressHistory(Connection c,UUID tenant,UUID opportunity,int limit)throws SQLException {
  if(limit<1||limit>51)throw new IllegalArgumentException("Invalid bound");
  try(var p=c.prepareStatement("select opportunity_progress_id,progress_digest,occurred_at from opportunity.opportunity_progress where tenant_id=? and opportunity_id=? order by progress_no desc limit ?")){
   p.setObject(1,tenant);p.setObject(2,opportunity);p.setInt(3,limit);var result=new ArrayList<Progress>();
   try(var r=p.executeQuery()){while(r.next())result.add(new Progress(new Subject("opportunity.opportunity_progress",r.getObject(1,UUID.class),null,Base64.getUrlEncoder().withoutPadding().encodeToString(r.getBytes(2))),r.getObject(3,OffsetDateTime.class).toInstant()));}return List.copyOf(result);
  }
 }
}
