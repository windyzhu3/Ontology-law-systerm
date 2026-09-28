package io.github.windyzhu3.ontologylaw.api;
import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.api.adapter.generated.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class R2OpportunityClosureWireTest {
    private final JsonMapper json=JsonMapper.builder().addModule(R1JsonConfiguration.oneOfModule()).build();
    private Map<String,Object> input(){var map=new LinkedHashMap<String,Object>();map.put("expectedOpportunityRevision",0L);map.put("expectedResponsibility",Map.of("type","opportunity.opportunity","id",UUID.randomUUID().toString(),"revision",0L));map.put("expectedTask",null);map.put("expectedWait",null);map.put("reasonCode","CLIENT_DECLINED");map.put("summary","Client chose to end negotiations");return map;}
    @Test void required_explicit_null_tokens_survive_generated_binding(){var bound=json.convertValue(input(),CloseOpportunityV1.class);assertNull(bound.getExpectedTask());assertNull(bound.getExpectedWait());assertNotNull(bound.getExpectedResponsibility());}
    @Test void omitted_null_tokens_and_unregistered_body_members_are_rejected(){var missing=input();missing.remove("expectedTask");assertThrows(RuntimeException.class,()->json.convertValue(missing,CloseOpportunityV1.class));var extra=input();extra.put("opportunityId",UUID.randomUUID().toString());assertThrows(RuntimeException.class,()->json.convertValue(extra,CloseOpportunityV1.class));}
    @Test void only_ready_response_contains_explicit_null_preconditions(){
        var value=input();value.remove("expectedOpportunityRevision");value.remove("reasonCode");value.remove("summary");value.put("status","READY");value.put("opportunity",Map.of("id",UUID.randomUUID().toString(),"revision",0L));
        var ready=json.convertValue(json.convertValue(value,OpportunityCloseContextV1.class),Map.class);assertEquals(value.keySet(),ready.keySet());assertEquals("READY",ready.get("status"));assertNull(ready.get("expectedTask"));assertNull(ready.get("expectedWait"));
        for(String status:List.of("READ_ONLY","BLOCKED","CLOSED")){var unavailable=Map.of("opportunity",value.get("opportunity"),"status",status);var wire=json.convertValue(json.convertValue(unavailable,OpportunityCloseContextV1.class),Map.class);assertEquals(unavailable.keySet(),wire.keySet());assertEquals(status,wire.get("status"));}
    }
    @Test void receipt_has_exact_revision_reference_and_shared_recovery_binding(){var fact=Map.of("factType","OPPORTUNITY_CLOSURE","factRef","opaque-closure-reference-123456789","revision",0L);assertInstanceOf(OpportunityClosureFactRefV1.class,R1WireModels.fact(fact));var value=Map.of("commandId",UUID.randomUUID().toString(),"receiptId",UUID.randomUUID().toString(),"outcome","SUCCEEDED","completedAt","2026-09-15T00:00:00Z","resultFact",fact);assertInstanceOf(OpportunityClosureFactRefV1.class,R1WireModels.model(value,OpportunityCloseCommandReceiptV1.class).getResultFact());assertInstanceOf(SuccessfulCommandReceipt.class,R1WireModels.receipt(value));}
}
