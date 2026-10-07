package io.github.windyzhu3.ontologylaw.api;
import java.util.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.payment.*;
import javax.crypto.spec.SecretKeySpec;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP quote/direct paths with two fully qualified competing directors. */
class BusinessResponsibilityRoutingChainHttpIT extends R2TransferHttpIT {
 @Override void registerActors(Actor entryApprover){
  super.registerActors(entryApprover);
  try{
   for(String name:List.of("Routing Ding director","Routing Huang director")){
    var actor=credentialActor(PrincipalKind.HUMAN,name,"CONTRACT_REVIEW");
    for(String code:List.of("CONTRACT_PREPARATION_DECIDE","CONTRACT_APPROVE","CONTRACT_SIGNATURE_VERIFY","CONTRACT_READ","PAYMENT_CONFIRM","TRANSFER_REVIEW","TRANSFER_ACCEPT","MATTER_CLASSIFY"))grantActor(actor.appointmentId(),seed.org(),code);
    UUID caseAppointment=UUID.randomUUID();
    mutate("insert into identity.appointment(tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values(?,?,?,?,'CONTACT_OPERATOR',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),caseAppointment,actor.principalId(),intakeOrganization);
    for(String code:List.of("CONTRACT_READ","TRANSFER_REVIEW","TRANSFER_ACCEPT","MATTER_CLASSIFY","MATTER_RECEIVE"))grantActor(caseAppointment,intakeOrganization,code);
   }
   // The isolated direct-entry fixture registers its current department policy
   // before requesting a decision; later version formation uses the next version.
   if(!scalar("select count(*) from identity.authority_grant where tenant_id=? and grantee_appointment_id=? and authority_code='CONTRACT_PREPARATION_DECIDE'",seed.tenant(),entryApprover.appointmentId()).equals("0")){
    grantActor(entryApprover.appointmentId(),seed.org(),"CONTRACT_APPROVE");var policy=UUID.randomUUID();
    try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
     sql(x,"insert into contract.approval_policy(tenant_id,approval_policy_id,organization_unit_id,policy_code,policy_version,mode,policy_digest,created_at) values(?,?,?,'R2_CONTRACT_APPROVAL_V1',1,'REQUIRE_APPROVAL',decode(repeat('19',32),'hex'),clock_timestamp())",seed.tenant(),policy,seed.org());
     sql(x,"insert into contract.approval_policy_member(tenant_id,approval_policy_member_id,policy_id,requirement_code,appointment_id,created_at) values(?,?,?,'LEGAL',?,clock_timestamp())",seed.tenant(),UUID.randomUUID(),policy,entryApprover.appointmentId());return null;
    });}
   }
   responsibilityRouting=new BusinessResponsibilityRouting(List.of(
    entry("AWAIT_REVIEW",conflictReviewer),entry("AWAIT_VERIFICATION",signatureVerifier),entry("ARCHIVE",signatureVerifier),entry("CHECK_RECEIPT",finance),entry("REVIEW_TRANSFER",intakeActor),entry("INTAKE",intakeActor),entry("CLASSIFY",intakeActor)));
  }catch(Exception failed){throw new IllegalStateException(failed);}
 }
 void grantActor(UUID appointment,UUID organization,String code)throws Exception{mutate("insert into identity.authority_grant(tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values(?,?,?,?,?,?,clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),appointment,seed.appointment(),organization,code);}
 BusinessResponsibilityRouting.Entry entry(String stage,Actor owner){return new BusinessResponsibilityRouting.Entry(seed.tenant(),seed.org(),stage,owner.appointmentId());}
 @Override PaymentWorkflowService paymentWorkflow(){return contractProtection==null?null:PaymentWorkflowService.databaseBacked(PaymentTransactionProtection.hmac(t->new SecretKeySpec(new byte[32],"HmacSHA256")),new PaymentWorkflowPorts(contractProtection,opportunityProtection,materialStore,t->new PaymentWorkflowService.Account("ROUTING_FIXTURE","Synthetic routing receipt account"),responsibilityRouting));}
 @Override void continueContractPreparation(HttpHarness http)throws Exception{
  super.continueContractPreparation(http);
  assertEquals("0",scalar("select count(*) from contract.preparation_workflow where tenant_id=? and stage_code='AWAIT_REVIEW' and owner_appointment_id<>?",seed.tenant(),conflictReviewer.appointmentId()));
  assertEquals("0",scalar("select count(*) from contract.signature_workflow where tenant_id=? and stage_code in ('AWAIT_VERIFICATION','ARCHIVE') and owner_appointment_id<>?",seed.tenant(),signatureVerifier.appointmentId()));
  assertEquals("0",scalar("select count(*) from contract.payment_workflow where tenant_id=? and stage_code='CHECK_RECEIPT' and owner_appointment_id<>?",seed.tenant(),finance.appointmentId()));
  assertEquals("0",scalar("select count(*) from transfer.workflow where tenant_id=? and stage_code in ('REVIEW_TRANSFER','INTAKE','CLASSIFY') and owner_appointment_id<>?",seed.tenant(),intakeActor.appointmentId()));
  assertEquals("0",scalar("select count(*) from contract.revision_approval_requirement where tenant_id=? and approver_appointment_id<>?",seed.tenant(),contractApprover.appointmentId()));
 }
}
