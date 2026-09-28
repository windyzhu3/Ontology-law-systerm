package io.github.windyzhu3.ontologylaw.api;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
class R25OverviewHttpIT extends R1HttpFixture {
 @Test void overview_reads_bound_month_metric_query_and_current_authority()throws Exception {
  setupContact();try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"LEAD_MANAGEMENT_READ");return null;});}
  try(var http=new HttpHarness()){
   var headers=Map.of("X-Appointment-Id",seed.appointment().toString());
   for(var path:List.of("/api/v1/business-overview?month=2026-08","/api/v1/business-overview/leads?month=2026-08&limit=20")){
    var response=http.request("GET",path,null,headers);assertEquals(200,response.statusCode(),response.body());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElse(null));
   }
   for(var suffix:List.of("?month=2026-13","?month=2026-08&month=2026-09","?tenantId="+seed.tenant(),"?limit=20"))assertEquals(400,http.request("GET","/api/v1/business-overview"+suffix,null,headers).statusCode(),suffix);
   assertEquals(400,http.request("GET","/api/v1/business-overview/leads?limit=101",null,headers).statusCode());
   assertEquals(400,http.request("GET","/api/v1/business-overview/forged",null,headers).statusCode());
   assertEquals(400,http.request("GET","/api/v1/business-overview",Map.of("month","2026-08"),headers).statusCode());
   assertEquals(403,http.request("GET","/api/v1/business-overview/signedContracts",null,headers).statusCode());
   mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_code='LEAD_MANAGEMENT_READ'",seed.tenant());
   assertEquals(403,http.request("GET","/api/v1/business-overview",null,headers).statusCode());
  }
 }
}
