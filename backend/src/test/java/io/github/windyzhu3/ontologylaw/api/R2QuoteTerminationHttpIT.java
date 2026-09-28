package io.github.windyzhu3.ontologylaw.api;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class R2QuoteTerminationHttpIT extends R2QuoteHttpIT {
 @Test void termination_http_checks_permission_and_recovers_original_receipt()throws Exception {
  setupQuote();try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"OPPORTUNITY_CLOSE");return null;});}
  String base="/api/v1/opportunities/"+opportunity.id()+"/quotes";
  try(var http=new HttpHarness()){
   var ctx=http.body(http.request("GET",base,null,Map.of()));var prep=http.request("POST",base+"/preparation-intents",commandBody(ctx,Map.of()),Map.of("Idempotency-Key",UUID.randomUUID().toString()));assertEquals(200,prep.statusCode(),prep.body());
   ctx=http.body(http.request("GET",base,null,Map.of()));assertTrue(((List<?>)ctx.get("allowedActions")).contains("END_QUOTE_NEGOTIATION"));
   var body=commandBody(ctx,Map.of("reasonCode","OTHER","summary","本次报价不再继续，保留历史"));String key=UUID.randomUUID().toString();
   assertEquals(400,http.request("POST",base+"/terminations",body,Map.of()).statusCode());
   var result=http.request("POST",base+"/terminations",body,Map.of("Idempotency-Key",key));assertEquals(200,result.statusCode(),result.body());assertEquals("QUOTE_TERMINATION",((Map<?,?>)http.body(result).get("resultFact")).get("factType"));
   assertSameReceipt(http.body(result),http.body(http.request("POST",base+"/terminations",body,Map.of("Idempotency-Key",key))));
   assertSameReceipt(http.body(result),http.body(http.request("GET","/api/v1/commands/"+key+"/receipt",null,Map.of())));
   var closed=http.request("GET","/api/v1/opportunities/"+opportunity.id()+"/closure",null,Map.of());assertEquals(200,closed.statusCode(),closed.body());assertEquals("CLOSED",http.body(closed).get("status"));assertEquals("本次报价不再继续，保留历史",((Map<?,?>)http.body(closed).get("closure")).get("summary"));
   ctx=http.body(http.request("GET",base,null,Map.of()));assertEquals(true,ctx.get("readonly"));assertEquals(List.of(),ctx.get("allowedActions"));
   mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_code='OPPORTUNITY_CLOSE'",seed.tenant());
   assertEquals(403,http.request("GET","/api/v1/commands/"+key+"/receipt",null,Map.of()).statusCode());
  }
  assertEquals("1",scalar("select count(*) from opportunity.quote_termination where tenant_id=?",seed.tenant()));
 }
}
