package io.github.windyzhu3.ontologylaw.api;
import java.util.*;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;import io.github.windyzhu3.ontologylaw.transfer.*;
class R2TransferContextIT extends R2TransferAuthorityIT {
 int downloads;
 R2TransferReadService reads(){var objects=new io.github.windyzhu3.ontologylaw.evidence.MaterialObjectStore(){public StoredObject store(java.io.InputStream stream){throw new UnsupportedOperationException();}public byte[] read(UUID object,String sha){downloads++;return "synthetic transfer document".getBytes(java.nio.charset.StandardCharsets.UTF_8);}};return new R2TransferReadService(protectedBodies,cipher,R2ContractServices.create(protectedBodies,cipher,null),new ContractWorkflowPorts(cipher,objects),realPorts(),AuditAppender.databaseBacked("F11_CONTEXT"));}
 @Test void transfer_context_discloses_exact_review_basis_and_denies_sales_or_stale_task()throws Exception{
  var initial=beginTransfer();submitTransfer(initial);for(String code:List.of("TRANSFER_REVIEW","CONTRACT_READ"))mutate("insert into identity.authority_grant(tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values(?,?,?,?,?,?,clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),reviewer,seed.appointment(),destination,code);
  UUID task=UUID.fromString(scalar("select task_id from transfer.workflow where tenant_id=? and stage_code='REVIEW_TRANSFER'",seed.tenant()));
  try(var c=database.apiConnection()){var result=reads().task(c,reviewerActor(),task);assertEquals("REVIEW_TRANSFER",result.get("stage"));assertNotNull(result.get("submission"));assertNull(result.get("matter"));assertFalse(((List<?>)result.get("materials")).isEmpty());assertTrue(((List<?>)((Map<?,?>)result.get("reviewPreview")).get("permittedOutcomes")).contains("CLEAR"));assertFalse(((Map<?,?>)result.get("submission")).containsKey("contractContext"));}
  try(var c=database.apiConnection()){assertThrows(io.github.windyzhu3.ontologylaw.execution.R1ServiceReadRuntime.Failure.class,()->reads().task(c,actor(),task));}
  try(var c=database.apiConnection()){assertThrows(io.github.windyzhu3.ontologylaw.execution.R1ServiceReadRuntime.Failure.class,()->reads().document(c,reviewerActor(),task,UUID.randomUUID()));}assertEquals(0,downloads);
  try(var c=database.apiConnection()){assertEquals("synthetic transfer document",new String(reads().document(c,reviewerActor(),task,material),java.nio.charset.StandardCharsets.UTF_8));}assertEquals(1,downloads);
  var expected=currentTransferWorkflow();var reviewerContext=reviewerActor();try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->workflows().review(x,reviewerContext,expected,new TransferConflictReviewInput("CLEAR","检查通过",true)));}
  try(var c=database.apiConnection()){assertThrows(io.github.windyzhu3.ontologylaw.execution.R1ServiceReadRuntime.Failure.class,()->reads().task(c,reviewerActor(),task));}
 }
}
