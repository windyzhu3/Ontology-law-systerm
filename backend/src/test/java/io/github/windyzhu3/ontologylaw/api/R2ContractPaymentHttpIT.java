package io.github.windyzhu3.ontologylaw.api;
import java.util.*;import java.time.*;import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.payment.*;
/** Same imported contract; finance and sales use distinct real HTTP credentials. */
class R2ContractPaymentHttpIT extends R2ContractExecutionHttpIT {
 Actor finance;
 @Override void registerActors(Actor entryApprover){super.registerActors(entryApprover);try{
  finance=credentialActor(PrincipalKind.HUMAN,"F10 finance","PAYMENT_CONFIRM");
  mutate("insert into identity.authority_grant(tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values(?,?,?,?,?,'CONTRACT_READ',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),finance.appointmentId(),seed.appointment(),seed.org());
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"PAYMENT_SUBMIT");return null;});}
  var registry=new ArrayList<io.github.windyzhu3.ontologylaw.api.security.ActorContextResolver.Registration>();for(var actor:List.of(seed.request().actor(),entryApprover,conflictReviewer,contractApprover,signatureVerifier,finance))registry.add(new io.github.windyzhu3.ontologylaw.api.security.ActorContextResolver.Registration(ISSUER,AUDIENCE,"FIXTURE",actor));
  realHumanResolver=new io.github.windyzhu3.ontologylaw.api.security.ActorContextResolver(database::apiConnection,new ExternalSubjectProtection(credentialKeys::get),List.of(new io.github.windyzhu3.ontologylaw.api.security.ActorContextResolver.Trust(ISSUER,AUDIENCE,(java.security.interfaces.RSAPublicKey)signing.getPublic())),registry);
 }catch(Exception e){throw new IllegalStateException(e);}}
 @Override PaymentWorkflowService paymentWorkflow(){return contractProtection==null?null:PaymentWorkflowService.databaseBacked(PaymentTransactionProtection.hmac(t->new javax.crypto.spec.SecretKeySpec(new byte[32],"HmacSHA256")),new PaymentWorkflowPorts(contractProtection,opportunityProtection,materialStore,t->new PaymentWorkflowService.Account("SYNTHETIC_BANK","Synthetic receipt account")));}
 Map<?,?> payment(Map<String,Object> context){return (Map<?,?>)((List<?>)context.get("payments")).getFirst();}
 Map<String,Object> paymentStep(RoleHttp role,String action,Map<String,Object> values)throws Exception{var ctx=context(role);var input=new LinkedHashMap<>(values);input.put("expectedPaymentWorkflow",payment(ctx).get("selector"));return step(role,action,input,false);}
 void afterInitialPayment(HttpHarness http)throws Exception{}
 @Override void continueContractPreparation(HttpHarness http)throws Exception{
  super.continueContractPreparation(http);var composed=R2ContractServices.create(contractProtection,opportunityProtection,materialStore,paymentWorkflow());var worker=service("CONTRACT_TASK_RECOVER");Map<String,Object> input;
  try(var c=database.apiConnection()){input=inTransaction(c,Capability.QUERY,x->{var candidate=composed.recoveryPage(x,worker,100,null).candidates().stream().filter(v->v.source().type().equals("contract.signature_archive")).findFirst().orElseThrow();var p=new LinkedHashMap<String,Object>();p.put("opportunityId",opportunity.id().toString());p.put("expectedOpportunityRevision",candidate.opportunity().revision());p.put("responsibilityBasis",Map.of("id",candidate.basis().id().toString(),"revision",candidate.basis().revision()));p.put("sourceKind","PAYMENT_HANDOFF");p.put("source",Map.of("id",candidate.source().id().toString(),"revision",0L));p.put("expectedWorkflow",null);return p;});}
  var runtime=R2OpportunityCommandRuntime.fromSourcePolicy(policies,protection,opportunityProtection,null,"F10_HTTP_PAYMENT",composed);try(var c=database.apiConnection()){var result=assertInstanceOf(CommandOutcome.class,runtime.execute(c,new CommandEnvelope(CommandEnvelope.Type.RECONCILE_CONTRACT_PREPARATION,UUID.randomUUID(),UUID.randomUUID(),worker,input)));assertEquals(CommandOutcome.Status.SUCCEEDED,result.status(),result.rejectionCode());}
  var financial=new RoleHttp(http,"F10 finance");var sales=new RoleHttp(http,SUBJECT);var ctx=context(financial);var task=(Map<?,?>)payment(ctx).get("task");var card=financial.request("GET","/api/v1/workcards/current?taskId="+task.get("id"),null,Map.of());assertEquals(200,card.statusCode(),card.body());
  var returned=Map.<String,Object>of("expectedPaymentWorkflow",payment(ctx).get("selector"),"decision","RETURN","explanation","Please supply the bank receipt");var denied=sales.request("POST",base+"/receipt-reviews",contractBody(context(sales),returned),Map.of("Idempotency-Key",UUID.randomUUID().toString()));assertEquals(403,denied.statusCode(),denied.body());
  paymentStep(financial,"receipt-reviews",Map.of("decision","RETURN","explanation","Please supply the bank receipt"));assertEquals("SUPPLEMENT_RECEIPT",payment(context(sales)).get("stage"));
  byte[] pdf=Files.readAllBytes(java.nio.file.Path.of(System.getProperty("ols.contract.fixtureTemplate")));var proof=acceptedMaterial(context(sales),pdf,"F10-synthetic-receipt.pdf");
  paymentStep(sales,"receipt-supplements",Map.of("materialVersionId",proof.id().toString(),"materialSha256",sha(pdf),"explanation","Attached bank evidence"));
  var confirmation=new LinkedHashMap<String,Object>();confirmation.put("decision","CONFIRM");confirmation.put("explanation","Manually verified receipt attribution");confirmation.put("materialVersionId",proof.id().toString());confirmation.put("materialSha256",sha(pdf));confirmation.put("transactionReference","F10-HTTP-RECEIPT-1");confirmation.put("amountMinor",100L);confirmation.put("currency","CNY");confirmation.put("receivedAt",Instant.now().minusSeconds(60).toString());confirmation.put("attributionChecked",true);
  paymentStep(financial,"receipt-reviews",confirmation);assertEquals("COMPLETE",payment(context(financial)).get("stage"));afterInitialPayment(http);
  var later=acceptedMaterial(context(sales),pdf,"F10-later-receipt.pdf");paymentStep(sales,"receipt-review-requests",Map.of("materialVersionId",later.id().toString(),"materialSha256",sha(pdf),"explanation","A later independent receipt"));
  confirmation.put("materialVersionId",later.id().toString());confirmation.put("transactionReference","F10-HTTP-RECEIPT-2");paymentStep(financial,"receipt-reviews",confirmation);
  assertEquals("2",scalar("select count(*) from contract.payment_confirmation where tenant_id=?",seed.tenant()));assertEquals("2",scalar("select count(*) from contract.payment_request where tenant_id=?",seed.tenant()));assertEquals("1",scalar("select count(*) from contract.contract_execution where tenant_id=?",seed.tenant()));assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code in ('CHECK_CONTRACT_RECEIPT','SUPPLEMENT_CONTRACT_RECEIPT') and state='OPEN'",seed.tenant()));
 }
}
