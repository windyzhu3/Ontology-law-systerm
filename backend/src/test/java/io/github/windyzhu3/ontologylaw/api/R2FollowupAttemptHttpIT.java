package io.github.windyzhu3.ontologylaw.api;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.responsibility.TaskFactory;
import java.util.*;import java.time.*;import org.junit.jupiter.api.Test;
class R2FollowupAttemptHttpIT extends R2QuoteHttpIT {
    @Test void attempt_http_write_history_and_original_receipt_remain_connected()throws Exception {
        setupQuote();try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{TaskFactory.databaseBacked().createInitialOpportunity(x,seed.tenant(),seed.appointment(),opportunity,ZoneId.of("Asia/Shanghai"),TaskFactory.databaseBacked().now(x));return null;});}
        String base="/api/v1/opportunities/"+opportunity.id()+"/followup-attempts";
        try(var http=new HttpHarness()){
            var response=http.request("GET",base,null,Map.of());assertEquals(200,response.statusCode(),response.body());var ctx=http.body(response);assertEquals("RECORD_OPPORTUNITY_FOLLOWUP_ATTEMPT",ctx.get("command"));
            var body=new LinkedHashMap<String,Object>();body.put("expectedOpportunityRevision",((Map<?,?>)ctx.get("opportunity")).get("revision"));for(String field:List.of("responsibilityBasis","task","waitReceipt","expectedWorkflow"))body.put(field,ctx.get(field));body.put("values",Map.of("type","NOT_CONNECTED","summary","未接通，约定明日联系","occurredAt",Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString(),"nextCheckAt",Instant.now().plusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString()));
            String key=UUID.randomUUID().toString();assertEquals(400,http.request("POST",base,body,Map.of()).statusCode());var saved=http.request("POST",base,body,Map.of("Idempotency-Key",key));assertEquals(200,saved.statusCode(),saved.body());assertEquals("FOLLOWUP_ATTEMPT",((Map<?,?>)http.body(saved).get("resultFact")).get("factType"));
            assertSameReceipt(http.body(saved),http.body(http.request("POST",base,body,Map.of("Idempotency-Key",key))));var receipt=http.request("GET","/api/v1/commands/"+key+"/receipt",null,Map.of());assertEquals(200,receipt.statusCode(),receipt.body());assertSameReceipt(http.body(saved),http.body(receipt));
            response=http.request("GET",base,null,Map.of());assertEquals(200,response.statusCode(),response.body());ctx=http.body(response);assertEquals("WAITING",ctx.get("taskState"));assertEquals(1,((List<?>)ctx.get("history")).size());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElse(""));
        }
    }
}
