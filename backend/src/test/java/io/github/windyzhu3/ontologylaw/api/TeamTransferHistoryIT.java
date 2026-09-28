package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import io.github.windyzhu3.ontologylaw.transfer.*;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;
class TeamTransferHistoryIT extends R2TransferWorkflowIT {
 @Test void completed_submission_keeps_its_original_task_and_from_organization()throws Exception {
  var initial=beginTransfer();UUID original;
  try(var c=database.apiConnection()){original=inTransaction(c,Capability.QUERY,x->TransferWorkflowReader.databaseBacked().workflow(x,seed.tenant(),initial.id()).taskId());}
  submitTransfer(initial);
  try(var c=database.apiConnection()){
   inTransaction(c,Capability.COMMAND,x->{grant(x,"TEAM_TASK_READ");return null;});
   inTransaction(c,Capability.QUERY,x->{var task=CurrentTaskReader.databaseBacked().read(x,seed.tenant(),original);var basis=R2TeamTaskResolver.basis(x,seed.tenant(),task);assertEquals(initial,basis.transfer().workflow());assertEquals(original,basis.transfer().taskId());assertEquals(basis.transfer().fromOrganization(),basis.organization());assertNotEquals(reviewer,task.owner());return null;});
   var detail=new R2TeamManagementReadService(new byte[32],protection,cipher,protectedBodies,AuditAppender.databaseBacked("TEAM_TRANSFER_HISTORY")).detail(c,actor(),"history",original);
   assertNull(detail.get("action"));assertNull(detail.get("taskId"));assertEquals(2,((List<?>)detail.get("history")).size());assertTrue(((List<?>)detail.get("facts")).contains(List.of("原因或说明",input().explanation())),"Original confirmed handover explanation must be readable in history");
   var failing=new R2TeamManagementReadService(new byte[32],protection,cipher,protectedBodies,(tx,entry)->{});
   assertThrows(java.sql.SQLException.class,()->failing.detail(c,actor(),"history",original));
   var completion=inTransaction(c,Capability.QUERY,x->CurrentTaskReader.databaseBacked().read(x,seed.tenant(),original).completion());
   mutate("insert into identity.object_access_grant(tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision,object_subject_hash) values(?,?,?,?,'TEAM_TASK_READ','DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),?,?,?,?)",seed.tenant(),UUID.randomUUID(),seed.request().actor().principalId(),seed.appointment(),completion.type(),completion.id(),completion.revision(),completion.hash()==null?null:Base64.getUrlDecoder().decode(completion.hash()));
   var bomb=new io.github.windyzhu3.ontologylaw.contract.ContractProtection(){
    public byte[] seal(UUID t,UUID o,UUID f,Kind k,String text){throw new AssertionError();}
    public String open(UUID t,UUID o,UUID f,Kind k,byte[] bytes){throw new AssertionError("Denied historical body was decrypted");}
   };
   var denied=new R2TeamManagementReadService(new byte[32],protection,cipher,bomb,AuditAppender.databaseBacked("TEAM_HISTORY_DENY"));
   var failure=assertThrows(io.github.windyzhu3.ontologylaw.execution.R1ServiceReadRuntime.Failure.class,()->denied.detail(c,actor(),"history",original));assertEquals(403,failure.status());
  }
 }
 @Test void cancelled_review_retains_destination_organization_and_original_workflow()throws Exception {
  var initial=beginTransfer();submitTransfer(initial);
  var reader=TransferWorkflowReader.databaseBacked();
  try(var c=database.apiConnection()){
   var before=inTransaction(c,Capability.QUERY,x->reader.forOpportunity(x,seed.tenant(),opportunity.id()).getFirst());
   assertEquals("REVIEW_TRANSFER",before.stage());ownerAvailable=false;
   var worker=service("TRANSFER_TASK_RECOVER");inTransaction(c,Capability.COMMAND,x->workflows().recover(x,worker,before.workflow()));
   inTransaction(c,Capability.QUERY,x->{var task=CurrentTaskReader.databaseBacked().read(x,seed.tenant(),before.taskId());assertEquals("CANCELLED",task.state());var basis=R2TeamTaskResolver.basis(x,seed.tenant(),task);assertNotNull(basis.transfer(),"Cancelled history must retain original transfer lineage");assertEquals(before.workflow(),basis.transfer().workflow());assertEquals(destination,basis.organization());return null;});
  }
 }
}
