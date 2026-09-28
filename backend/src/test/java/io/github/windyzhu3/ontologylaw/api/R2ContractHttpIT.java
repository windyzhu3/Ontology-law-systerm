package io.github.windyzhu3.ontologylaw.api;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import java.util.*;import javax.crypto.spec.SecretKeySpec;import org.junit.jupiter.api.Test;
class R2ContractHttpIT extends R2QuoteHttpIT {
 @Test void contract_request_http_accepts_exact_nullable_basis_and_replays_receipt_without_task_headers()throws Exception{
  setupQuote();contractProtection=io.github.windyzhu3.ontologylaw.contract.ContractProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES"));
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{for(String code:List.of("CONTRACT_READ","CONTRACT_PREPARE","CONTRACT_PREPARATION_DECIDE"))grant(x,code);return null;});}
  String base="/api/v1/opportunities/"+opportunity.id()+"/contracts";
  try(var http=new HttpHarness()){
   var initial=http.request("GET",base,null,Map.of());assertEquals(200,initial.statusCode(),initial.body());var context=http.body(initial);var body=new LinkedHashMap<String,Object>();body.put("expectedOpportunityRevision",((Map<?,?>)context.get("opportunity")).get("revision"));body.put("responsibilityBasis",context.get("responsibilityBasis"));body.put("customerConfirmation",context.get("customerConfirmation"));for(String field:List.of("expectedContract","expectedDraft","expectedVersion","expectedWorkflow"))body.put(field,null);
   var commercial=new LinkedHashMap<String,Object>();commercial.put("currency","CNY");commercial.put("scope","合成合同范围");commercial.put("lines",List.of(Map.of("description","服务费","amountMinor",10000,"discount",false)));commercial.put("conditionalFee",null);commercial.put("paymentTerms","依约付款");body.put("values",Map.of("commercial",commercial,"reason","合成直接准备申请"));String key=UUID.randomUUID().toString();
   var result=http.request("POST",base+"/preparation-requests",body,Map.of("Idempotency-Key",key));assertEquals(200,result.statusCode(),result.body());assertEquals("CONTRACT_PREPARATION_REQUEST",((Map<?,?>)http.body(result).get("resultFact")).get("factType"));
   var replay=http.request("POST",base+"/preparation-requests",body,Map.of("Idempotency-Key",key));assertEquals(200,replay.statusCode(),replay.body());assertEquals(http.body(result),http.body(replay));
   var receipt=http.request("GET","/api/v1/commands/"+key+"/receipt",null,Map.of());assertEquals(200,receipt.statusCode(),receipt.body());
   var invalid=new LinkedHashMap<>(body);invalid.put("values",null);assertEquals(400,http.request("POST",base+"/preparation-requests",invalid,Map.of("Idempotency-Key",UUID.randomUUID().toString())).statusCode());
  }
  assertEquals("1",scalar("select count(*) from contract.preparation_request where tenant_id=?",seed.tenant()));
 }
}
