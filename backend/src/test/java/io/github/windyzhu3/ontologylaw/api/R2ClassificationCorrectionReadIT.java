package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.execution.R1ServiceReadRuntime;import java.util.*;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;import io.github.windyzhu3.ontologylaw.transfer.*;import io.github.windyzhu3.ontologylaw.contract.*;
class R2ClassificationCorrectionReadIT extends R2TransferAuthorityIT {
 @Test void correction_read_is_authorized_current_and_does_not_reopen_completed_duties()throws Exception{
  real_transfer_authority_closes_submit_review_accept_classify_chain();
  var contracts=R2ContractServices.create(protectedBodies,cipher,null);var reads=new R2TransferReadService(protectedBodies,cipher,contracts,new ContractWorkflowPorts(cipher,null),realPorts(),AuditAppender.databaseBacked("Q1_READ"));
  String before=scalar("select count(*) from transfer.workflow where tenant_id=?",seed.tenant());
  try(var c=database.apiConnection()){var result=reads.classificationCorrection(c,reviewerActor(),opportunity.id());assertEquals("GENERAL",result.get("category"));assertEquals(reviewer.toString(),((Map<?,?>)result.get("recipient")).get("id"));assertEquals(currentTransferWorkflow().id().toString(),((Map<?,?>)result.get("expectedWorkflow")).get("id"));assertNotNull(((Map<?,?>)result.get("matter")).get("number"));}
  assertEquals(before,scalar("select count(*) from transfer.workflow where tenant_id=?",seed.tenant()));
  try(var c=database.apiConnection()){assertThrows(R1ServiceReadRuntime.Failure.class,()->reads.classificationCorrection(c,actor(),opportunity.id()));}
  mutate("update identity.authority_grant set state='REVOKED',revision=revision+1,revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE' where tenant_id=? and grantee_appointment_id=? and authority_code='MATTER_RECEIVE'",seed.tenant(),reviewer);
  try(var c=database.apiConnection()){var result=reads.classificationCorrection(c,reviewerActor(),opportunity.id());assertNull(result.get("recipient"));assertEquals(List.of(),result.get("receivers"));}
  mutate("update identity.authority_grant set state='REVOKED',revision=revision+1,revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE' where tenant_id=? and grantee_appointment_id=? and authority_code='MATTER_CLASSIFY'",seed.tenant(),reviewer);
  try(var c=database.apiConnection()){assertThrows(R1ServiceReadRuntime.Failure.class,()->reads.classificationCorrection(c,reviewerActor(),opportunity.id()));}
 }
}

