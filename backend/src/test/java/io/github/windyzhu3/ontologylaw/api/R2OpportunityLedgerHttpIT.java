package io.github.windyzhu3.ontologylaw.api;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Real transport tests verify privacy, request closure and current entry reauthorization. */
class R2OpportunityLedgerHttpIT extends R1HttpFixture {
    private Subject opportunity;
    private void setupLedger(String... grants)throws Exception {
        setupContact();var completed=execute(prepare(contact("CONNECTED_VALID")));
        opportunityProtection=OpportunityProgressProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES"));
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{opportunity=EventOpportunityReader.databaseBacked().forContact(x,seed.tenant(),completed.resultFact().id()).selector();for(var right:grants)grant(x,right);return null;});}
    }
    @Test void manager_gets_closed_read_only_projection_and_revocation_removes_access()throws Exception {
        setupLedger("OPPORTUNITY_LEDGER_READ");
        try(var http=new HttpHarness()){
            var list=http.request("GET","/api/v1/opportunities?limit=1",null,Map.of());assertEquals(200,list.statusCode(),list.body());
            assertEquals("no-store",list.headers().firstValue("Cache-Control").orElseThrow());
            var items=(List<?>)http.body(list).get("items");assertEquals(1,items.size());
            assertEquals(Set.of("opportunity","customerLabel","ownerLabel","taskState"),((Map<?,?>)items.getFirst()).keySet());
            var detail=http.request("GET","/api/v1/opportunities/"+opportunity.id(),null,Map.of());assertEquals(200,detail.statusCode(),detail.body());
            var body=http.body(detail);assertEquals(false,body.get("canHandle"));assertFalse(body.containsKey("task"));assertFalse(body.containsKey("lastProgress"));assertEquals("NONE",body.get("taskState"));
            mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='TEST',revision=revision+1 where tenant_id=? and authority_code='OPPORTUNITY_LEDGER_READ'",seed.tenant());
            var denied=http.request("GET","/api/v1/opportunities/"+opportunity.id(),null,Map.of());assertEquals(403,denied.statusCode(),denied.body());assertFalse(denied.body().contains(String.valueOf(body.get("customerLabel"))));
        }
    }
    @Test void query_header_and_method_surface_is_closed()throws Exception {
        setupLedger("OPPORTUNITY_LEDGER_READ");
        try(var http=new HttpHarness()){
            for(String query:List.of("limit=101","limit=0","limit=1&limit=2","state=UNKNOWN","tenantId="+seed.tenant(),"cursor=forged","search="+"a".repeat(201))){
                var response=http.request("GET","/api/v1/opportunities?"+query,null,Map.of());assertEquals(400,response.statusCode(),query+response.body());
            }
            var response=http.request("GET","/api/v1/opportunities/"+opportunity.id()+"?search=x",null,Map.of());assertEquals(400,response.statusCode(),response.body());
            response=http.request("GET","/api/v1/opportunities",null,Map.of("X-Tenant-Id",seed.tenant().toString()));assertEquals(400,response.statusCode(),response.body());
            response=http.request("POST","/api/v1/opportunities",Map.of(),Map.of());assertNotEquals(200,response.statusCode());assertNotEquals(201,response.statusCode());
        }
    }
    @Test void exception_read_authority_does_not_grant_normal_ledger()throws Exception {
        setupLedger("OPPORTUNITY_OWNER_EXCEPTION_READ");
        try(var http=new HttpHarness()){var response=http.request("GET","/api/v1/opportunities",null,Map.of());assertEquals(403,response.statusCode(),response.body());}
    }
}
