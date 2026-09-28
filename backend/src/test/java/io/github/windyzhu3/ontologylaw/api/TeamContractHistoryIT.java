package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;
class TeamContractHistoryIT extends R2ContractWorkflowPersistenceIT {
 @Test void direct_decision_history_keeps_its_original_confirmed_reason()throws Exception {
  initialize();command("REQUEST_CONTRACT_PREPARATION",payload(context(),Map.of("commercial",terms.canonical(),"reason","申请准确合同范围")));
  var completion=command("RECORD_CONTRACT_PREPARATION_DECISION",payload(context(),Map.of("decision","APPROVED","reason","原确认依据：批准本次准确准备范围")));
  UUID task=UUID.fromString(scalar("select task_occurrence_id from responsibility.task_occurrence where tenant_id=? and completion_fact_id=?",seed.tenant(),completion.id()));
  try(var c=database.apiConnection()){
   inTransaction(c,Capability.COMMAND,x->{grant(x,"TEAM_TASK_READ");return null;});
   var detail=new R2TeamManagementReadService(new byte[32],protection,cipher,protectedBodies,AuditAppender.databaseBacked("TEAM_CONTRACT_HISTORY")).detail(c,actor(),"history",task);
   assertNull(detail.get("action"));assertTrue(((List<?>)detail.get("facts")).contains(List.of("原因或说明","原确认依据：批准本次准确准备范围")),"Original authorized contract decision reason must be present");
   var failing=new R2TeamManagementReadService(new byte[32],protection,cipher,protectedBodies,(tx,entry)->{});
   assertThrows(java.sql.SQLException.class,()->failing.detail(c,actor(),"history",task));
   var bomb=new io.github.windyzhu3.ontologylaw.contract.ContractProtection(){
    public byte[] seal(UUID t,UUID o,UUID f,Kind k,String text){throw new AssertionError();}
    public String open(UUID t,UUID o,UUID f,Kind k,byte[] bytes){throw new AssertionError("Denied historical body was decrypted");}
   };
   inTransaction(c,Capability.QUERY,x->{
    assertThrows(java.sql.SQLException.class,()->io.github.windyzhu3.ontologylaw.contract.ContractWorkflowService.confirmedReason(x,UUID.randomUUID(),opportunity.id(),completion,bomb,R2TeamTaskResolver.HISTORY_CODEC));
    assertThrows(java.sql.SQLException.class,()->io.github.windyzhu3.ontologylaw.contract.ContractWorkflowService.confirmedReason(x,seed.tenant(),UUID.randomUUID(),completion,bomb,R2TeamTaskResolver.HISTORY_CODEC));return null;
   });
   mutate("insert into identity.object_access_grant(tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision,object_subject_hash) values(?,?,?,?,'TEAM_TASK_READ','DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),?,?,?,?)",seed.tenant(),UUID.randomUUID(),seed.request().actor().principalId(),seed.appointment(),completion.type(),completion.id(),completion.revision(),completion.hash()==null?null:Base64.getUrlDecoder().decode(completion.hash()));
   var denied=new R2TeamManagementReadService(new byte[32],protection,cipher,bomb,AuditAppender.databaseBacked("TEAM_HISTORY_DENY"));
   var failure=assertThrows(io.github.windyzhu3.ontologylaw.execution.R1ServiceReadRuntime.Failure.class,()->denied.detail(c,actor(),"history",task));assertEquals(403,failure.status());
  }
 }
}
