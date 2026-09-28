package io.github.windyzhu3.ontologylaw.api;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.util.*;
import java.time.ZoneId;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Real HTTP: strict nullable inputs, terminal receipt replay and current-grant revocation. */
class R2OpportunityClosureHttpIT extends R1HttpFixture {
    private Subject opportunity;
    private void setupClosure(boolean task,String... grants)throws Exception {
        setupContact();var completed=execute(prepare(contact("CONNECTED_VALID")));
        opportunityProtection=OpportunityProgressProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES"));
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{opportunity=EventOpportunityReader.databaseBacked().forContact(x,seed.tenant(),completed.resultFact().id()).selector();for(var right:grants)grant(x,right);if(task)current=TaskFactory.databaseBacked().createInitialOpportunity(x,seed.tenant(),seed.appointment(),opportunity,ZoneId.of("Asia/Shanghai"),businessAt);return null;});}
    }
    private String contextPath(){return "/api/v1/opportunities/"+opportunity.id()+"/closure";}
    private String closePath(){return "/api/v1/opportunities/"+opportunity.id()+"/commands/close";}
    private Map<String,Object> input(Map<String,Object> context){var body=new LinkedHashMap<String,Object>();body.put("expectedOpportunityRevision",((Map<?,?>)context.get("opportunity")).get("revision"));for(String key:List.of("expectedResponsibility","expectedTask","expectedWait"))body.put(key,context.get(key));body.put("reasonCode","CLIENT_DECLINED");body.put("summary","Private terminal client instruction");return body;}
    @Test void no_task_closure_recovers_original_receipt_and_current_revocation_denies_replay()throws Exception {
        setupClosure(false,"OPPORTUNITY_LEDGER_READ","OPPORTUNITY_CLOSE");
        try(var http=new HttpHarness()){
            var context=http.request("GET",contextPath(),null,Map.of());assertEquals(200,context.statusCode(),context.body());var value=http.body(context);assertEquals("READY",value.get("status"));assertTrue(value.containsKey("expectedTask"));assertNull(value.get("expectedTask"));assertTrue(value.containsKey("expectedWait"));assertNull(value.get("expectedWait"));
            var key=UUID.randomUUID();var body=input(value);var headers=Map.of("Idempotency-Key",key.toString());var closed=http.request("POST",closePath(),body,headers);assertEquals(200,closed.statusCode(),closed.body());assertEquals("no-store",closed.headers().firstValue("Cache-Control").orElseThrow());assertEquals("/api/v1/commands/"+key+"/receipt",closed.headers().firstValue("Location").orElseThrow());var fact=(Map<?,?>)http.body(closed).get("resultFact");assertEquals("OPPORTUNITY_CLOSURE",fact.get("factType"));assertEquals(Set.of("factType","factRef","revision"),fact.keySet());assertFalse(closed.body().contains("Private terminal"));
            var replay=http.request("POST",closePath(),body,headers);assertEquals(200,replay.statusCode(),replay.body());assertEquals(http.body(closed),http.body(replay));
            var recovered=http.request("GET","/api/v1/commands/"+key+"/receipt",null,Map.of());assertEquals(200,recovered.statusCode(),recovered.body());assertEquals(http.body(closed),http.body(recovered));
            var terminal=http.request("GET",contextPath(),null,Map.of());assertEquals(200,terminal.statusCode(),terminal.body());var finalContext=http.body(terminal);assertEquals(Set.of("opportunity","status","closure"),finalContext.keySet());assertEquals("CLOSED",finalContext.get("status"));assertEquals("Private terminal client instruction",((Map<?,?>)finalContext.get("closure")).get("summary"));
            mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='TEST',revision=revision+1 where tenant_id=? and authority_code='OPPORTUNITY_CLOSE'",seed.tenant());
            var denied=http.request("GET","/api/v1/commands/"+key+"/receipt",null,Map.of());assertEquals(403,denied.statusCode(),denied.body());assertFalse(denied.body().contains("factRef"));
            denied=http.request("POST",closePath(),body,headers);assertEquals(403,denied.statusCode(),denied.body());assertFalse(denied.body().contains("factRef"));
        }
    }
    @Test void open_task_exact_closure_cancels_and_rejects_absent_null_and_header_preconditions()throws Exception {
        setupClosure(true,"SALES_OPPORTUNITY_OWNER","OPPORTUNITY_CLOSE");
        try(var http=new HttpHarness()){
            var context=http.request("GET",contextPath(),null,Map.of());assertEquals(200,context.statusCode(),context.body());var body=input(http.body(context));assertNotNull(body.get("expectedTask"));
            var key=Map.of("Idempotency-Key",UUID.randomUUID().toString());var missing=new LinkedHashMap<>(body);missing.remove("expectedWait");assertEquals(400,http.request("POST",closePath(),missing,key).statusCode());
            var extra=new LinkedHashMap<>(body);extra.put("tenantId",seed.tenant().toString());assertEquals(400,http.request("POST",closePath(),extra,key).statusCode());
            assertEquals(400,http.request("POST",closePath()+"?tenantId="+seed.tenant(),body,key).statusCode());assertEquals(400,http.request("POST",closePath(),body,Map.of("Idempotency-Key",UUID.randomUUID().toString(),"If-Match","\"task.forbidden\"")).statusCode());
            assertEquals(400,http.request("POST",closePath(),body,Map.of()).statusCode());
            var closed=http.request("POST",closePath(),body,key);assertEquals(200,closed.statusCode(),closed.body());assertEquals("CANCELLED",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),current.selector().id()));
        }
    }
    @Test void ledger_permission_does_not_grant_close_or_expose_action_selectors()throws Exception {
        setupClosure(false,"OPPORTUNITY_LEDGER_READ");
        try(var http=new HttpHarness()){
            var response=http.request("GET",contextPath(),null,Map.of());assertEquals(200,response.statusCode(),response.body());assertEquals(Set.of("opportunity","status"),http.body(response).keySet());assertEquals("READ_ONLY",http.body(response).get("status"));
            assertEquals(400,http.request("GET",contextPath()+"?expectedRevision=0",null,Map.of()).statusCode());
            assertEquals(400,http.request("GET",contextPath(),null,Map.of("X-Tenant-Id",seed.tenant().toString())).statusCode());
        }
    }
}
