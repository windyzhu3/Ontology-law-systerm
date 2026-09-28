package io.github.windyzhu3.ontologylaw.api;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import java.util.*;import javax.crypto.spec.SecretKeySpec;import org.junit.jupiter.api.Test;
class R2ContractTerminationHttpIT extends R2QuoteHttpIT {
 @Test void termination_http_has_exact_original_receipt_and_stopped_context()throws Exception {
  setupQuote();contractProtection=io.github.windyzhu3.ontologylaw.contract.ContractProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES"));
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{for(String code:List.of("CONTRACT_READ","CONTRACT_PREPARE","CONTRACT_PREPARATION_DECIDE","OPPORTUNITY_CLOSE"))grant(x,code);return null;});}
  String base="/api/v1/opportunities/"+opportunity.id()+"/contracts";
  try(var http=new HttpHarness()){
   var initial=http.request("GET",base,null,Map.of());assertEquals(200,initial.statusCode(),initial.body());var context=http.body(initial);var body=new LinkedHashMap<String,Object>();body.put("expectedOpportunityRevision",((Map<?,?>)context.get("opportunity")).get("revision"));body.put("responsibilityBasis",context.get("responsibilityBasis"));body.put("customerConfirmation",context.get("customerConfirmation"));for(String field:List.of("expectedContract","expectedDraft","expectedVersion","expectedWorkflow"))body.put(field,null);
   var commercial=new LinkedHashMap<String,Object>();commercial.put("currency","CNY");commercial.put("scope","合成合同范围");commercial.put("lines",List.of(Map.of("description","服务费","amountMinor",10000,"discount",false)));commercial.put("conditionalFee",null);commercial.put("paymentTerms","依约付款");body.put("values",Map.of("commercial",commercial,"reason","合成直接准备申请"));
   var prepared=http.request("POST",base+"/preparation-requests",body,Map.of("Idempotency-Key",UUID.randomUUID().toString()));assertEquals(200,prepared.statusCode(),prepared.body());
   context=http.body(http.request("GET",base,null,Map.of()));assertTrue(((List<?>)context.get("allowedActions")).contains("END_CONTRACT_NEGOTIATION"));body.put("expectedWorkflow",((Map<?,?>)context.get("workflow")).get("selector"));var values=new LinkedHashMap<String,Object>();values.put("summary","未签本次销售办理结束，批准及历史保留");values.put("humanConfirmed",true);values.put("expectedTermination",null);values.put("expectedSignatureWorkflow",null);body.put("values",values);String key=UUID.randomUUID().toString();
   var result=http.request("POST",base+"/negotiation-end",body,Map.of("Idempotency-Key",key));assertEquals(200,result.statusCode(),result.body());assertEquals("CONTRACT_NEGOTIATION_DISPOSITION",((Map<?,?>)http.body(result).get("resultFact")).get("factType"));
   assertSameReceipt(http.body(result),http.body(http.request("POST",base+"/negotiation-end",body,Map.of("Idempotency-Key",key))));assertSameReceipt(http.body(result),http.body(http.request("GET","/api/v1/commands/"+key+"/receipt",null,Map.of())));
   var stopped=http.request("GET",base,null,Map.of());assertEquals(200,stopped.statusCode(),stopped.body());context=http.body(stopped);assertEquals(true,context.get("readonly"));assertEquals(List.of(),context.get("allowedActions"));assertEquals("STOPPED",((Map<?,?>)context.get("termination")).get("state"));
   var pausedValues=new LinkedHashMap<String,Object>();pausedValues.put("expectedTermination",((Map<?,?>)context.get("termination")).get("selector"));pausedValues.put("commercial",commercial);pausedValues.put("reason","暂停后不得重新申请");body.put("values",pausedValues);String pausedKey=UUID.randomUUID().toString();
   var paused=http.request("POST",base+"/preparation-requests",body,Map.of("Idempotency-Key",pausedKey));assertEquals(409,paused.statusCode(),paused.body());assertEquals("CONTRACT_HANDLING_PAUSED",http.body(paused).get("code"));assertEquals("NEW_KEY_AFTER_REFRESH",http.body(paused).get("retryPolicy"));
   var rejectedReceipt=http.request("GET","/api/v1/commands/"+pausedKey+"/receipt",null,Map.of());assertEquals(200,rejectedReceipt.statusCode(),rejectedReceipt.body());assertEquals("CONTRACT_HANDLING_PAUSED",http.body(rejectedReceipt).get("rejectionCode"));
   mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_code='OPPORTUNITY_CLOSE'",seed.tenant());assertEquals(403,http.request("GET","/api/v1/commands/"+key+"/receipt",null,Map.of()).statusCode());
  }
  assertEquals("1",scalar("select count(*) from contract.negotiation_disposition where tenant_id=?",seed.tenant()));
 }
}
