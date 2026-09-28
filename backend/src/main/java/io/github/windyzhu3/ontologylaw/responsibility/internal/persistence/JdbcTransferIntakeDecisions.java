package io.github.windyzhu3.ontologylaw.responsibility.internal.persistence;
import io.github.windyzhu3.ontologylaw.responsibility.TransferIntakeDecisions;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import io.github.windyzhu3.ontologylaw.execution.CanonicalJson;
import java.sql.*;import java.util.*;
public final class JdbcTransferIntakeDecisions implements TransferIntakeDecisions {
 public Subject record(Connection c,UUID tenant,UUID task,UUID actor,Subject snapshot,String outcome,String explanation)throws SQLException{
  if(c.getAutoCommit()||!Set.of("ACCEPT","RETURN").contains(outcome)||!"transfer.transfer_snapshot".equals(snapshot.type())||snapshot.hash()==null||explanation==null||explanation.isBlank())throw new IllegalArgumentException("Exact intake decision required");
  try(var p=c.prepareStatement("select 1 from responsibility.task_occurrence t join transfer.workflow w on w.tenant_id=t.tenant_id and w.task_id=t.task_occurrence_id join transfer.transfer_snapshot s on s.tenant_id=w.tenant_id and s.transfer_request_id=w.transfer_request_id where t.tenant_id=? and t.task_occurrence_id=? and t.owner_appointment_id=? and t.state='OPEN' and t.business_purpose_code='ACCEPT_TRANSFER' and w.stage_code='INTAKE' and s.transfer_snapshot_id=? and s.snapshot_digest=? and not exists(select 1 from transfer.workflow n where n.tenant_id=w.tenant_id and n.previous_workflow_id=w.workflow_id) for update of t")){Object[] values={tenant,task,actor,snapshot.id(),Base64.getUrlDecoder().decode(snapshot.hash())};for(int i=0;i<values.length;i++)p.setObject(i+1,values[i]);try(var r=p.executeQuery()){if(!r.next())throw new SQLException("Exact intake task required","23514");}}
  byte[] digest=CanonicalJson.digest(CanonicalJson.encode(Map.of("profile","R2_TRANSFER_INTAKE_V1","tenant",tenant.toString(),"task",task.toString(),"actor",actor.toString(),"snapshot",snapshot.id().toString(),"snapshotHash",snapshot.hash(),"outcome",outcome,"explanation",explanation)));
  UUID id;try(var p=c.prepareStatement("select uuidv7()");var r=p.executeQuery()){r.next();id=r.getObject(1,UUID.class);}
  try(var p=c.prepareStatement("insert into responsibility.decision_record(tenant_id,decision_record_id,task_occurrence_id,decision_version,decided_by_appointment_id,authority_slot_code,decision_contract_code,decision_contract_version,decision_code,content_digest,rationale_summary,decided_at,decision_subject_type,decision_subject_id,decision_subject_hash) values(?,?,?,1,?,'OPPORTUNITY_OWNER','R2_TRANSFER_INTAKE_V1',1,?,?,'Case intake manager confirmed the exact protected snapshot',clock_timestamp(),'transfer.transfer_snapshot',?,?)")){Object[] values={tenant,id,task,actor,outcome,digest,snapshot.id(),Base64.getUrlDecoder().decode(snapshot.hash())};for(int i=0;i<values.length;i++)p.setObject(i+1,values[i]);p.executeUpdate();}
  return new Subject("responsibility.decision_record",id,null,Base64.getUrlEncoder().withoutPadding().encodeToString(digest));
 }
}
