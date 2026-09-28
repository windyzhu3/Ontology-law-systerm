package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.lead.LeadIntakeSources;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
class R25LeadManagementHttpIT extends R1HttpFixture {
 @Test void authorized_http_reads_are_no_store_and_reject_query_body_and_identity_injection()throws Exception {
  setupContact();intakeSources=List.of(new LeadIntakeSources.Source("FIXTURE","合成受控来源","TEST","CONSULTATION","CN","NORMAL"));
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"LEAD_MANAGEMENT_READ");return null;});}
  try(var http=new HttpHarness()){
   var headers=Map.of("X-Appointment-Id",seed.appointment().toString());
   for(var path:List.of("/api/v1/lead-management/leads?limit=20","/api/v1/lead-management/sources","/api/v1/lead-management/leads/"+current.lead().id())){
    var response=http.request("GET",path,null,headers);assertEquals(200,response.statusCode(),response.body());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElse(null));
   }
   for(var suffix:List.of("?limit=101","?limit=1&limit=2","?state=FORGED","?tenantId="+seed.tenant(),"?owner=not-a-uuid"))assertEquals(400,http.request("GET","/api/v1/lead-management/leads"+suffix,null,headers).statusCode(),suffix);
   assertEquals(400,http.request("GET","/api/v1/lead-management/leads",Map.of("source","FORGED"),headers).statusCode());
   assertEquals(400,http.request("GET","/api/v1/lead-management/leads",null,Map.of("X-Appointment-Id",seed.appointment().toString(),"X-Tenant-Id",seed.tenant().toString())).statusCode());
   mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_code='LEAD_MANAGEMENT_READ'",seed.tenant());
   assertEquals(403,http.request("GET","/api/v1/lead-management/leads",null,headers).statusCode());
  }
 }
}
