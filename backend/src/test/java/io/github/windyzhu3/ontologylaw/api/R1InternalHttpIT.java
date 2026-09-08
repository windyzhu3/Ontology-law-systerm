package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import io.github.windyzhu3.ontologylaw.testing.TlsFixture;
import java.util.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import static org.junit.jupiter.api.Assertions.*;

class R1InternalHttpIT extends R1HttpFixture {
    @TempDir Path directory;
    @Test void authenticated_readiness_rejects_undeclared_query_and_body_with_exact_existing_400_and_zero_delta()throws Exception {
        setupContact();var actor=service("R1_PROJECTION_CONSUME");
        try(var http=new HttpHarness(actor,new TlsFixture(directory))) {
            var before=counts();var query=http.request("GET","/internal/v1/projections/r1/readiness?tenantId="+UUID.randomUUID(),null,Map.of());assertEquals(400,query.statusCode(),query.body());assertEquals("VALIDATION_FAILED",http.body(query).get("code"));assertEquals("/query",((Map<?,?>)((List<?>)http.body(query).get("fieldErrors")).getFirst()).get("pointer"));assertTrue(query.headers().firstValue("WWW-Authenticate").isEmpty());assertEquals(before,counts());
            var body=http.request("GET","/internal/v1/projections/r1/readiness",Map.of("tenantId",UUID.randomUUID().toString()),Map.of());assertEquals(400,body.statusCode(),body.body());assertEquals("VALIDATION_FAILED",http.body(body).get("code"));assertEquals("/body",((Map<?,?>)((List<?>)http.body(body).get("fieldErrors")).getFirst()).get("pointer"));assertEquals("no-store",body.headers().firstValue("Cache-Control").orElseThrow());assertEquals(before,counts());
            var ready=http.request("GET","/internal/v1/projections/r1/readiness",null,Map.of("If-None-Match","*"));assertEquals(204,ready.statusCode());assertEquals("",ready.body());assertTrue(ready.headers().firstValue("ETag").isEmpty());assertEquals(before,counts());
            var selectors=http.request("GET","/internal/v1/projections/r1/readiness",null,Map.of("X-Appointment-Id","malformed-public-selector","X-On-Behalf-Appointment-Id",UUID.randomUUID().toString()));assertEquals(204,selectors.statusCode(),"Internal mTLS does not consume public selectors or change its static Actor");assertEquals(before,counts());
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void certificate_actor_discovers_and_recovers_each_exact_wait_once_with_internal_v1_and_original_request_replay(boolean contact)throws Exception {
        setupFlow(contact?TaskFactory.Type.CONTACT_LEAD:TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP);
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{TaskFactory.databaseBacked().waitUntil(x,seed.tenant(),current,seed.appointment(),businessAt.plusSeconds(3600),businessAt);return null;});}
        var actor=service(contact?"CONTACT_TASK_RECOVER":"ROUTING_REVIEW_TASK_RECOVER");
        try(var http=new HttpHarness(actor,new TlsFixture(directory))) {
            var before=counts();var response=http.request("GET","/internal/v1/tasks/due?recoveryType="+(contact?"CONTACT_TASK":"ROUTING_REVIEW_TASK")+"&limit=1",null,Map.of());assertEquals(200,response.statusCode(),response.body());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());assertEquals(before,counts());
            var rows=(List<?>)http.body(response).get("candidates");assertEquals(1,rows.size());var candidate=(Map<String,Object>)rows.getFirst();assertEquals(current.selector().id().toString(),candidate.get("taskId"));assertEquals(5,UUID.fromString((String)candidate.get("idempotencyKey")).version());
            var body=new TreeMap<>(candidate);String key=(String)body.remove("idempotencyKey");body.remove("recoveryType");String path="/internal/v1/tasks/commands/reopen-due-"+(contact?"contact-tasks":"routing-review-tasks");
            response=http.request("POST",path,body,Map.of("Idempotency-Key",key));assertEquals(200,response.statusCode(),response.body());var receipt=http.body(response);assertEquals("SUCCEEDED",receipt.get("outcome"));assertEquals("TASK_OCCURRENCE",((Map<?,?>)receipt.get("resultFact")).get("factType"));assertTrue(response.headers().firstValue("ETag").orElseThrow().startsWith("\"task."));assertTrue(response.headers().firstValue("WWW-Authenticate").isEmpty());
            assertEquals("1",scalar("select summary_schema_version::text from audit.audit_entry_classified_v where tenant_id=? and command_id=?",seed.tenant(),UUID.fromString(key)));
            before=counts();var replay=http.request("POST",path,body,Map.of("Idempotency-Key",key));assertEquals(200,replay.statusCode(),replay.body());assertEquals(receipt,http.body(replay));assertEquals(before,counts());
            var hidden=http.request("GET","/api/v1/commands/"+key+"/receipt",null,Map.of());assertEquals(404,hidden.statusCode());assertEquals(before,counts());
        }
    }
    @Test void readiness_and_actual_fenced_projection_consume_are_204_bodyless_no_store_zero_delta()throws Exception {
        setupContact();assertEquals(CommandOutcome.Status.SUCCEEDED,execute(prepare(contact("NOT_CONNECTED"))).status());var actor=service("R1_PROJECTION_CONSUME");
        var outbox=R1ProjectionOutboxPort.databaseBacked(database::workerConnection);var claim=outbox.claim(seed.tenant(),"HTTP_INTERNAL_IT",1).getFirst();
        try(var http=new HttpHarness(actor,new TlsFixture(directory))) {
            var before=counts();var ready=http.request("GET","/internal/v1/projections/r1/readiness",null,Map.of());assertEquals(204,ready.statusCode(),ready.body());assertEquals("",ready.body());assertEquals("no-store",ready.headers().firstValue("Cache-Control").orElseThrow());assertTrue(ready.headers().firstValue("ETag").isEmpty());assertEquals(before,counts());
            var body=Map.of("domainEventOutboxId",claim.outboxId().toString(),"domainEventId",claim.eventId().toString(),"expectedOutboxRevision",claim.revision(),"leaseOwner",claim.leaseOwner(),"fencingToken",claim.fencingToken());
            var consumed=http.request("POST","/internal/v1/projections/r1/consume",body,Map.of());assertEquals(204,consumed.statusCode(),consumed.body());assertEquals("",consumed.body());assertEquals("no-store",consumed.headers().firstValue("Cache-Control").orElseThrow());assertTrue(consumed.headers().firstValue("WWW-Authenticate").isEmpty());assertEquals(before,counts());
        }
    }
}
