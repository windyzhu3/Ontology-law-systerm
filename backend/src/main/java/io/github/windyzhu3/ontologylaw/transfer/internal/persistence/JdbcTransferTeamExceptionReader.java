package io.github.windyzhu3.ontologylaw.transfer.internal.persistence;
import io.github.windyzhu3.ontologylaw.transfer.TransferTeamExceptionReader;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;import java.time.Instant;import java.util.*;
public final class JdbcTransferTeamExceptionReader implements TransferTeamExceptionReader {
 private static final String[] QUERIES={"select w.workflow_id id,w.revision,'transfer.workflow'::text source_type,w.opportunity_id,w.owner_appointment_id owner,(select p.task_id from transfer.workflow p where p.tenant_id=w.tenant_id and p.workflow_id=w.previous_workflow_id) original_task,w.created_at observed_at,w.due_at,'TRANSFER'::text kind,'OWNER_EXCEPTION'::text state,w.target_stage_code resume_stage,case when w.target_stage_code in ('PREPARE','SUPPLEMENT') then request.from_organization_unit_id else request.to_organization_unit_id end organization,'transfer.transfer_request'::text parent_type,request.transfer_request_id parent_id,request.revision parent_revision from transfer.workflow w join transfer.transfer_request request on request.tenant_id=w.tenant_id and request.transfer_request_id=w.transfer_request_id where w.tenant_id=? and w.stage_code='OWNER_EXCEPTION' and not exists(select 1 from transfer.workflow n where n.tenant_id=w.tenant_id and n.previous_workflow_id=w.workflow_id)"};
 public List<Row> scan(Connection c,UUID tenant,UUID after,int limit)throws SQLException{return read(c,tenant,after,null,limit);}
 public Row current(Connection c,UUID tenant,UUID id)throws SQLException{var rows=read(c,tenant,null,Objects.requireNonNull(id),2);if(rows.size()>1)throw new SQLException("Ambiguous team exception identity","21000");return rows.isEmpty()?null:rows.getFirst();}
 private List<Row> read(Connection c,UUID tenant,UUID after,UUID exact,int limit)throws SQLException{
  if(limit<1||limit>100)throw new IllegalArgumentException("Bounded exception read required");Objects.requireNonNull(tenant);
  var rows=new ArrayList<Row>();
  for(var branch:QUERIES){
   String sql="select * from ("+branch+") exception_candidates where "+(exact!=null?"id=?":after!=null?"id>?":"true")+" order by id limit ?";
   try(var p=c.prepareStatement(sql)){int n=1;p.setObject(n++,tenant);if(exact!=null)p.setObject(n++,exact);else if(after!=null)p.setObject(n++,after);p.setInt(n,limit);
    try(var r=p.executeQuery()){while(r.next()){
     var source=new Subject(r.getString("source_type"),r.getObject("id",UUID.class),r.getLong("revision"),null);
     var sources=new ArrayList<Subject>();sources.add(source);
     if(r.getString("parent_type")!=null)sources.add(new Subject(r.getString("parent_type"),r.getObject("parent_id",UUID.class),r.getLong("parent_revision"),null));
     rows.add(new Row(source,r.getObject("opportunity_id",UUID.class),r.getObject("owner",UUID.class),r.getObject("original_task",UUID.class),instant(r,"observed_at"),instant(r,"due_at"),r.getString("kind"),r.getString("state"),r.getString("resume_stage"),r.getObject("organization",UUID.class),sources));
    }}
   }
  }
  rows.sort(Comparator.comparing(v->v.selector().id().toString()));return List.copyOf(rows.subList(0,Math.min(limit,rows.size())));
 }
 private static Instant instant(ResultSet r,String column)throws SQLException{var v=r.getTimestamp(column);return v==null?null:v.toInstant();}
}
