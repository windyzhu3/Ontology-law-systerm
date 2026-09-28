package io.github.windyzhu3.ontologylaw.transfer.internal.persistence;
import io.github.windyzhu3.ontologylaw.transfer.TransferLedgerReader;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.util.*;

public final class JdbcTransferLedgerReader implements TransferLedgerReader {
 public List<Row> scan(Connection c,UUID tenant,UUID after,String stage,int limit)throws SQLException {
  Objects.requireNonNull(tenant);
  if(limit<1||limit>100||stage!=null&&!Set.of("PREPARE","REVIEW_TRANSFER","INTAKE","SUPPLEMENT","CLASSIFY","COMPLETE","OWNER_EXCEPTION").contains(stage))throw new IllegalArgumentException("Bounded transfer query required");
  String sql="select r.transfer_request_id,r.revision request_revision,w.workflow_id,w.revision workflow_revision,r.opportunity_id,r.contract_id,w.stage_code,w.owner_appointment_id,w.task_id,w.due_at from transfer.transfer_request r join transfer.workflow w on w.tenant_id=r.tenant_id and w.transfer_request_id=r.transfer_request_id where r.tenant_id=? and not exists(select 1 from transfer.workflow n where n.tenant_id=w.tenant_id and n.previous_workflow_id=w.workflow_id)"+(after==null?"":" and r.transfer_request_id>?")+(stage==null?"":" and w.stage_code=?")+" order by r.transfer_request_id limit ?";
  try(var p=c.prepareStatement(sql)){
   int i=1;p.setObject(i++,tenant);if(after!=null)p.setObject(i++,after);if(stage!=null)p.setString(i++,stage);p.setInt(i,limit);
   try(var r=p.executeQuery()){
    var result=new ArrayList<Row>();while(r.next())result.add(new Row(new Subject("transfer.transfer_request",r.getObject("transfer_request_id",UUID.class),r.getLong("request_revision"),null),new Subject("transfer.workflow",r.getObject("workflow_id",UUID.class),r.getLong("workflow_revision"),null),r.getObject("opportunity_id",UUID.class),r.getObject("contract_id",UUID.class),r.getString("stage_code"),r.getObject("owner_appointment_id",UUID.class),r.getObject("task_id",UUID.class),r.getTimestamp("due_at").toInstant()));
    return List.copyOf(result);
   }
  }
 }
}
