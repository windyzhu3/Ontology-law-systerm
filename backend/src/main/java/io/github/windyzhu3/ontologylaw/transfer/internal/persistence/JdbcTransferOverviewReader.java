package io.github.windyzhu3.ontologylaw.transfer.internal.persistence;
import io.github.windyzhu3.ontologylaw.transfer.TransferOverviewReader;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.time.*;
import java.util.*;
/** Own intake ACCEPT is the case-creation fact. Classification rows cannot multiply it. */
public final class JdbcTransferOverviewReader implements TransferOverviewReader {
 public List<Candidate> scan(Connection c,UUID tenant,Instant from,Instant until,UUID after,int limit)throws SQLException {
  if(c.getAutoCommit())throw new SQLException("Overview requires an active read transaction","25000");
  if(tenant==null||from==null||until==null||!from.isBefore(until)||limit<1||limit>100)throw new IllegalArgumentException("Bounded overview period required");
  String sql="select f.matter_id,f.opportunity_id,f.created_at,f.intake_id,f.intake_revision,f.transfer_request_id,r.revision request_revision,r.from_organization_unit_id,r.to_organization_unit_id,f.workflow_id,f.submission_id,f.review_id,f.snapshot_id,s.snapshot_digest from (select distinct on (matter_id) matter_id,opportunity_id,created_at,intake_id,revision intake_revision,transfer_request_id,workflow_id,submission_id,review_id,snapshot_id from transfer.intake where tenant_id=? and outcome_code='ACCEPT' order by matter_id,created_at,intake_id) f join transfer.transfer_request r on r.tenant_id=? and r.transfer_request_id=f.transfer_request_id join transfer.transfer_snapshot s on s.tenant_id=r.tenant_id and s.transfer_snapshot_id=f.snapshot_id where f.created_at>=? and f.created_at<?"+(after==null?"":" and f.matter_id>?")+" order by f.matter_id limit ?";
  try(var p=c.prepareStatement(sql)){int n=1;p.setObject(n++,tenant);p.setObject(n++,tenant);p.setObject(n++,from.atOffset(ZoneOffset.UTC));p.setObject(n++,until.atOffset(ZoneOffset.UTC));if(after!=null)p.setObject(n++,after);p.setInt(n,limit);var out=new ArrayList<Candidate>();try(var r=p.executeQuery()){while(r.next()){
   var request=r.getObject(6,UUID.class);var facts=List.of(new Subject("transfer.intake",r.getObject(4,UUID.class),r.getLong(5),null),new Subject("transfer.transfer_request",request,r.getLong(7),null),new Subject("transfer.workflow",r.getObject(10,UUID.class),0L,null),new Subject("transfer.submission",r.getObject(11,UUID.class),0L,null),new Subject("transfer.review",r.getObject(12,UUID.class),0L,null),new Subject("transfer.transfer_snapshot",r.getObject(13,UUID.class),null,Base64.getUrlEncoder().withoutPadding().encodeToString(r.getBytes(14))));
   out.add(new Candidate(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,OffsetDateTime.class).toInstant(),facts,request,r.getObject(8,UUID.class),r.getObject(9,UUID.class)));
  }}return List.copyOf(out);}
 }
}
