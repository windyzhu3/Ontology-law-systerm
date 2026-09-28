package io.github.windyzhu3.ontologylaw.responsibility.internal.persistence;
import io.github.windyzhu3.ontologylaw.responsibility.*;import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import io.github.windyzhu3.ontologylaw.execution.CanonicalJson;
import java.sql.*;import java.util.*;
public final class JdbcContractConflictDecisions implements ContractConflictDecisions {
 private final boolean transfer;
 public JdbcContractConflictDecisions(){this(false);}
 public JdbcContractConflictDecisions(boolean transfer){this.transfer=transfer;}
 public Subject block(Connection c,UUID tenant,UUID opportunity,UUID actor,Subject finding,String reason)throws SQLException{
  if(c.getAutoCommit()||!"conflict.conflict_finding".equals(finding.type())||finding.hash()==null||reason==null||reason.isBlank())throw new IllegalArgumentException("Exact blocking finding required");
  UUID task=null;String profile=transfer?"R2_TRANSFER_CONFLICT_BLOCK_V1":"R2_CONTRACT_CONFLICT_BLOCK_V1";
  if(transfer){
   try(var p=c.prepareStatement("""
    select t.task_occurrence_id from responsibility.task_occurrence t
    join transfer.workflow w on w.tenant_id=t.tenant_id and w.task_id=t.task_occurrence_id
    join conflict.conflict_review r on r.tenant_id=w.tenant_id and r.trigger_fact_type='transfer.submission' and r.trigger_fact_id=w.submission_id and r.review_type_code='PRE_TRANSFER'
    join conflict.conflict_finding f on f.tenant_id=r.tenant_id and f.conflict_review_id=r.conflict_review_id
    where t.tenant_id=? and t.subject_type='opportunity.opportunity' and t.subject_id=? and t.owner_appointment_id=? and t.business_purpose_code='REVIEW_TRANSFER' and t.state='OPEN'
     and f.conflict_finding_id=? and f.finding_digest=? and w.stage_code='REVIEW_TRANSFER'
     and not exists(select 1 from transfer.workflow n where n.tenant_id=w.tenant_id and n.previous_workflow_id=w.workflow_id) for update of t
    """)){p.setObject(1,tenant);p.setObject(2,opportunity);p.setObject(3,actor);p.setObject(4,finding.id());p.setBytes(5,Base64.getUrlDecoder().decode(finding.hash()));try(var rows=p.executeQuery()){if(rows.next())task=rows.getObject(1,UUID.class);if(rows.next())throw new SQLException("Ambiguous transfer review task","23514");}}
   if(task==null)throw new SQLException("Exact transfer reviewer task missing","23514");
  }else{
try(var p=c.prepareStatement("select task_occurrence_id from responsibility.task_occurrence where tenant_id=? and subject_type='opportunity.opportunity' and subject_id=? and owner_appointment_id=? and business_purpose_code='REVIEW_CONTRACT' and state='OPEN' for update")){p.setObject(1,tenant);p.setObject(2,opportunity);p.setObject(3,actor);try(var rows=p.executeQuery()){if(rows.next())task=rows.getObject(1,UUID.class);if(rows.next())throw new SQLException("Ambiguous contract review task","23514");}}if(task==null)throw new SQLException("Contract reviewer task missing","23514");
  }
  var values=Map.of("profile",profile,"tenantId",tenant.toString(),"taskId",task.toString(),"actor",actor.toString(),"finding",Map.of("id",finding.id().toString(),"hash",finding.hash()),"decision","BLOCKED","reason",reason);byte[] digest=CanonicalJson.digest(CanonicalJson.encode(values));UUID id;
  try(var q=c.createStatement();var rows=q.executeQuery("select uuidv7()")){rows.next();id=rows.getObject(1,UUID.class);}
  try(var p=c.prepareStatement("insert into responsibility.decision_record(tenant_id,decision_record_id,task_occurrence_id,decision_version,decided_by_appointment_id,authority_slot_code,decision_contract_code,decision_contract_version,decision_code,content_digest,rationale_summary,decided_at,decision_subject_type,decision_subject_id,decision_subject_hash) values(?,?,?,1,?,'OPPORTUNITY_OWNER',?,1,'BLOCKED',?,'Reviewer explicitly blocked an exact protected conflict finding',clock_timestamp(),'conflict.conflict_finding',?,?)")){Object[] args={tenant,id,task,actor,profile,digest,finding.id(),Base64.getUrlDecoder().decode(finding.hash())};for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);p.executeUpdate();}return new Subject("responsibility.decision_record",id,null,Base64.getUrlEncoder().withoutPadding().encodeToString(digest));
 }
}
