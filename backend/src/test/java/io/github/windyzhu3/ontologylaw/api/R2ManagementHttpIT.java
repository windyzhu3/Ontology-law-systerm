package io.github.windyzhu3.ontologylaw.api;
import java.util.*;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
class R2ManagementHttpIT extends R1HttpFixture {
 @Test void http_management_reads_reject_ungranted_and_unknown_query_inputs()throws Exception{
  setupContact();
  try(var http=new HttpHarness()){
   var denied=http.request("GET","/api/v1/business-management/payments?limit=20",null,Map.of());assertEquals(403,denied.statusCode(),denied.body());
   try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"PAYMENT_LEDGER_READ");return null;});}
   var allowed=http.request("GET","/api/v1/business-management/payments?limit=20",null,Map.of());assertEquals(200,allowed.statusCode(),allowed.body());assertEquals("no-store",allowed.headers().firstValue("Cache-Control").orElse(""));
   assertTrue(((List<?>)http.body(allowed).get("items")).isEmpty());
   var detail=http.request("GET","/api/v1/business-management/payments/"+UUID.randomUUID(),null,Map.of());assertEquals(404,detail.statusCode(),detail.body());
   for(String query:List.of("?limit=0","?limit=1&limit=2","?tenant=other","?cursor=invalid","?state=INVALID")){var invalid=http.request("GET","/api/v1/business-management/payments"+query,null,Map.of());assertEquals(400,invalid.statusCode(),invalid.body());}
  }
 }
}
