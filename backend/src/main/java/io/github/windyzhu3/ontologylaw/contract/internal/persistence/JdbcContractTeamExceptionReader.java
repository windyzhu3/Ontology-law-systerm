package io.github.windyzhu3.ontologylaw.contract.internal.persistence;
import io.github.windyzhu3.ontologylaw.contract.ContractTeamExceptionReader;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;import java.time.Instant;import java.util.*;
public final class JdbcContractTeamExceptionReader implements ContractTeamExceptionReader {
 private static final String[] QUERIES={"select w.preparation_workflow_id id,w.revision,'contract.preparation_workflow'::text source_type,w.opportunity_id,w.owner_appointment_id owner,w.prior_task_id original_task,w.created_at observed_at,null::timestamptz due_at,'CONTRACT_PREPARATION'::text kind,'OWNER_EXCEPTION'::text state,w.recovery_resume_stage resume_stage,null::uuid organization,null::text parent_type,null::uuid parent_id,null::bigint parent_revision from contract.preparation_workflow w where w.tenant_id=? and w.stage_code='OWNER_EXCEPTION' and not exists(select 1 from contract.preparation_workflow n where n.tenant_id=w.tenant_id and n.previous_workflow_id=w.preparation_workflow_id)",
"select w.signature_workflow_id id,w.revision,'contract.signature_workflow'::text source_type,w.opportunity_id,w.owner_appointment_id owner,w.prior_task_id original_task,w.created_at observed_at,w.due_at due_at,'CONTRACT_SIGNATURE'::text kind,'OWNER_EXCEPTION'::text state,w.recovery_resume_stage resume_stage,null::uuid organization,null::text parent_type,null::uuid parent_id,null::bigint parent_revision from contract.signature_workflow w where w.tenant_id=? and w.stage_code='OWNER_EXCEPTION' and not exists(select 1 from contract.signature_workflow n where n.tenant_id=w.tenant_id and n.previous_workflow_id=w.signature_workflow_id)",
"select w.execution_workflow_id id,w.revision,'contract.execution_workflow'::text source_type,w.opportunity_id,w.owner_appointment_id owner,(select p.task_id from contract.execution_workflow p where p.tenant_id=w.tenant_id and p.execution_workflow_id=w.previous_workflow_id) original_task,w.created_at observed_at,w.due_at due_at,'CONTRACT_EXECUTION'::text kind,'OWNER_EXCEPTION'::text state,'CHECK_EXECUTION'::text resume_stage,null::uuid organization,null::text parent_type,null::uuid parent_id,null::bigint parent_revision from contract.execution_workflow w where w.tenant_id=? and w.stage_code='OWNER_EXCEPTION' and not exists(select 1 from contract.execution_workflow n where n.tenant_id=w.tenant_id and n.previous_workflow_id=w.execution_workflow_id)",
"select w.payment_workflow_id id,w.revision,'contract.payment_workflow'::text source_type,w.opportunity_id,w.owner_appointment_id owner,(select p.task_id from contract.payment_workflow p where p.tenant_id=w.tenant_id and p.payment_workflow_id=w.previous_workflow_id) original_task,w.created_at observed_at,w.due_at due_at,'CONTRACT_PAYMENT'::text kind,'OWNER_EXCEPTION'::text state,w.target_stage_code resume_stage,null::uuid organization,'contract.payment_request'::text parent_type,request.payment_request_id parent_id,request.revision parent_revision from contract.payment_workflow w join contract.payment_request request on request.tenant_id=w.tenant_id and request.payment_request_id=w.request_id where w.tenant_id=? and w.stage_code='OWNER_EXCEPTION' and not exists(select 1 from contract.payment_workflow n where n.tenant_id=w.tenant_id and n.previous_workflow_id=w.payment_workflow_id)"};
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
