package io.github.windyzhu3.ontologylaw.api;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import io.github.windyzhu3.ontologylaw.testing.TlsFixture;
import java.time.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class R2OpportunityInternalHttpIT extends R1HttpFixture {
    private static final String ROOT="/internal/v1/opportunity-tasks";
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp;
    private Subject opportunity;
    private Actor prepareOpening()throws Exception {
        setupContact();var contact=execute(prepare(contact("CONNECTED_VALID")));
        opportunityProtection=OpportunityProgressProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES"));
        try(var c=database.apiConnection()){opportunity=inTransaction(c,Capability.COMMAND,x->{grant(x,"SALES_OPPORTUNITY_OWNER");return EventOpportunityReader.databaseBacked().forContact(x,seed.tenant(),contact.resultFact().id()).selector();});}
        return credentialActor(PrincipalKind.SERVICE,"r2-worker","OPPORTUNITY_TASK_ACTIVATE");
    }
    private Map<String,Object> activation(){return Map.of("opportunityId",opportunity.id().toString(),"expectedOpportunityRevision",opportunity.revision());}
    @Test void scheduled_activation_reaches_human_card_without_manual_dispatch()throws Exception {
        var service=prepareOpening();
        try(var http=new HttpHarness(service,new TlsFixture(temp));var client=http.workerClient()) {
            var bindings=new io.github.windyzhu3.ontologylaw.worker.R1WorkerTenantBindings("MVP-2026-09-08.3",List.of(http.workerBinding));
            try(var scheduler=new io.github.windyzhu3.ontologylaw.worker.R2OpportunityTaskScheduler(bindings,client,Clock.systemUTC(),io.github.windyzhu3.ontologylaw.execution.R2OpportunityCheckpointPort.databaseBacked(database::workerConnection))) {
                scheduler.start();
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
                while(scheduler.snapshot(http.workerBinding,io.github.windyzhu3.ontologylaw.worker.InternalApiClient.OpportunityKind.INITIAL).acknowledged()==0 && System.nanoTime()<deadline) Thread.sleep(50);
                assertEquals(1,scheduler.snapshot(http.workerBinding,io.github.windyzhu3.ontologylaw.worker.InternalApiClient.OpportunityKind.INITIAL).acknowledged());
                var card=http.request("GET","/api/v1/workcards/current",null,Map.of());
                assertEquals("PROGRESS_OPPORTUNITY",((Map<?,?>)http.body(card).get("currentCard")).get("taskType"));
            }
            assertEquals(200,client.opportunityCandidates(http.workerBinding,io.github.windyzhu3.ontologylaw.worker.InternalApiClient.OpportunityKind.INITIAL,null).status());
        }
    }
    @Test void committed_activation_with_lost_response_is_confirmed_after_scheduler_restart()throws Exception {
        var service=prepareOpening();
        try(var http=new HttpHarness(service,new TlsFixture(temp));var client=http.workerClient()) {
            var bindings=new io.github.windyzhu3.ontologylaw.worker.R1WorkerTenantBindings("MVP-2026-09-08.3",List.of(http.workerBinding));
            var port=io.github.windyzhu3.ontologylaw.execution.R2OpportunityCheckpointPort.databaseBacked(database::workerConnection);
            var kind=io.github.windyzhu3.ontologylaw.worker.InternalApiClient.OpportunityKind.INITIAL;
            var commands=new ArrayList<UUID>();
            var dropping=new io.github.windyzhu3.ontologylaw.worker.R2OpportunityTaskScheduler.Gateway(){
                public io.github.windyzhu3.ontologylaw.worker.InternalApiClient.OpportunityPage discover(io.github.windyzhu3.ontologylaw.worker.R1WorkerTenantBindings.Binding b,io.github.windyzhu3.ontologylaw.worker.InternalApiClient.OpportunityKind k,String cursor){return client.opportunityCandidates(b,k,cursor);}
                public io.github.windyzhu3.ontologylaw.worker.InternalApiClient.Result execute(io.github.windyzhu3.ontologylaw.worker.R1WorkerTenantBindings.Binding b,io.github.windyzhu3.ontologylaw.worker.InternalApiClient.OpportunityCandidate c){commands.add(c.idempotencyKey());assertEquals(200,client.maintainOpportunity(b,c).status());return new io.github.windyzhu3.ontologylaw.worker.InternalApiClient.Result(408,"");}
            };
            try(var first=new io.github.windyzhu3.ontologylaw.worker.R2OpportunityTaskScheduler(bindings,dropping,Clock.systemUTC(),port)){assertEquals(0,first.poll(http.workerBinding,kind));}
            assertTrue(client.opportunityCandidates(http.workerBinding,kind,null).candidates().isEmpty());
            try(var next=new io.github.windyzhu3.ontologylaw.worker.R2OpportunityTaskScheduler(bindings,client,Clock.offset(Clock.systemUTC(),Duration.ofMinutes(1)),port)){
                assertEquals(1,next.poll(http.workerBinding,kind));assertEquals(0,next.snapshot(http.workerBinding,kind).pending());
            }
            assertEquals(1,commands.size());
            assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=?",seed.tenant(),opportunity.id()));
        }
    }
    @Test void mtls_candidate_activation_and_replay_preserve_original_card_flow()throws Exception {
        var service=prepareOpening();
        var tls=new TlsFixture(temp);try(var http=new HttpHarness(service,tls)){
            assertEquals(403,http.request("GET",ROOT+"/candidates?kind=DUE",null,Map.of()).statusCode());
            var page=http.request("GET",ROOT+"/candidates?kind=INITIAL",null,Map.of());assertEquals(200,page.statusCode(),page.body());
            var candidate=(Map<?,?>)((List<?>)http.body(page).get("candidates")).getFirst();assertEquals("INITIAL",candidate.get("kind"));
            io.github.windyzhu3.ontologylaw.worker.InternalApiClient.OpportunityCandidate workerCandidate;
            try(var worker=http.workerClient()){
                var workerPage=worker.opportunityCandidates(http.workerBinding,io.github.windyzhu3.ontologylaw.worker.InternalApiClient.OpportunityKind.INITIAL,null);
                assertEquals(200,workerPage.status());workerCandidate=workerPage.candidates().getFirst();
            }
            var headers=Map.of("Idempotency-Key",(String)candidate.get("idempotencyKey"));
            var created=http.request("POST",ROOT+"/commands/activate-initial",activation(),headers);assertEquals(200,created.statusCode(),created.body());
            assertTrue(created.headers().firstValue("Location").isEmpty());assertEquals("no-store",created.headers().firstValue("Cache-Control").orElseThrow());
            var repeated=http.request("POST",ROOT+"/commands/activate-initial",activation(),headers);assertEquals(200,repeated.statusCode(),repeated.body());assertEquals(http.body(created),http.body(repeated));
            try(var worker=http.workerClient()){var result=worker.maintainOpportunity(http.workerBinding,workerCandidate);assertEquals(200,result.status());assertEquals(http.body(created),mapper.readValue(result.body(),Map.class));}
            var after=http.request("GET",ROOT+"/candidates?kind=INITIAL",null,Map.of());assertEquals(List.of(),http.body(after).get("candidates"));
            var card=http.request("GET","/api/v1/workcards/current",null,Map.of());assertEquals(200,card.statusCode(),card.body());assertEquals("PROGRESS_OPPORTUNITY",((Map<?,?>)http.body(card).get("currentCard")).get("taskType"));
        }
    }
    @Test void mtls_body_and_query_are_closed_and_missing_key_cannot_create_work()throws Exception {
        var service=prepareOpening();var tls=new TlsFixture(temp);try(var http=new HttpHarness(service,tls)){
            var missing=http.request("POST",ROOT+"/commands/activate-initial",activation(),Map.of());assertEquals(400,missing.statusCode(),missing.body());assertEquals("IDEMPOTENCY_KEY_REQUIRED",http.body(missing).get("code"));
            var extra=new TreeMap<>(activation());extra.put("businessCategory","EXECUTION");assertEquals(400,http.request("POST",ROOT+"/commands/activate-initial",extra,Map.of("Idempotency-Key",UUID.randomUUID().toString())).statusCode());
            for(String invalid:List.of("{\"opportunityId\":\""+opportunity.id()+"\",\"expectedOpportunityRevision\":null}","{\"opportunityId\":\""+opportunity.id()+"\",\"expectedOpportunityRevision\":0.5}","{\"opportunityId\":\""+opportunity.id()+"\",\"expectedOpportunityRevision\":0,\"expectedOpportunityRevision\":0}"))assertEquals(400,http.request("POST",ROOT+"/commands/activate-initial",invalid,Map.of("Idempotency-Key",UUID.randomUUID().toString())).statusCode());
            for(String query:List.of("kind=INITIAL&tenantId="+seed.tenant(),"kind=INITIAL&kind=DUE","kind=INITIAL&limit=101","kind=UNKNOWN"))assertEquals(400,http.request("GET",ROOT+"/candidates?"+query,null,Map.of()).statusCode(),query);
            assertEquals(400,http.request("POST",ROOT+"/commands/activate-initial",activation(),Map.of("Idempotency-Key",UUID.randomUUID().toString(),"X-Appointment-Id",seed.appointment().toString())).statusCode());
            assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=?",seed.tenant(),opportunity.id()));
        }
    }
    @Test void bearer_without_certificate_cannot_enter_internal_routes()throws Exception {
        prepareOpening();try(var http=new HttpHarness()){
            assertEquals(401,http.request("GET",ROOT+"/candidates?kind=INITIAL",null,Map.of()).statusCode());
            assertEquals(401,http.request("POST",ROOT+"/commands/activate-initial",activation(),Map.of("Idempotency-Key",UUID.randomUUID().toString())).statusCode());
        }
    }
    @Test void due_candidate_recovers_exact_task_and_stale_input_returns_named_problem()throws Exception {
        var service=prepareOpening();
        mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,'OPPORTUNITY_TASK_RECOVER',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),service.appointmentId(),seed.appointment(),seed.org());
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            var task=TaskFactory.databaseBacked().createInitialOpportunity(x,seed.tenant(),seed.appointment(),opportunity,ZoneId.of("Asia/Shanghai"),businessAt);
            R2OpportunityProgressServices.create(opportunityProtection).record(x,seed.request().actor(),opportunity,task.selector(),new OpportunityProgressInput("MEETING","约定再次跟进",businessAt,businessAt.plusSeconds(86400)),ZoneId.of("Asia/Shanghai"),businessAt.plusSeconds(60));return null;
        });}
        var tls=new TlsFixture(temp);try(var http=new HttpHarness(service,tls)){
            var page=http.request("GET",ROOT+"/candidates?kind=DUE",null,Map.of());assertEquals(200,page.statusCode(),page.body());
            var candidate=(Map<String,Object>)((List<?>)http.body(page).get("candidates")).getFirst();assertEquals("DUE",candidate.get("kind"));
            var payload=new TreeMap<>(candidate);payload.remove("kind");var key=(String)payload.remove("idempotencyKey");
            try(var client=http.workerClient();var scheduler=new io.github.windyzhu3.ontologylaw.worker.R2OpportunityTaskScheduler(new io.github.windyzhu3.ontologylaw.worker.R1WorkerTenantBindings("MVP-2026-09-08.3",List.of(http.workerBinding)),client,Clock.systemUTC())) {
                assertEquals(1,scheduler.poll(http.workerBinding,io.github.windyzhu3.ontologylaw.worker.InternalApiClient.OpportunityKind.DUE));
            }
            var headers=Map.of("Idempotency-Key",key);var first=http.request("POST",ROOT+"/commands/reopen-due",payload,headers);assertEquals(200,first.statusCode(),first.body());
            var replay=http.request("POST",ROOT+"/commands/reopen-due",payload,headers);assertEquals(200,replay.statusCode(),replay.body());assertEquals(http.body(first),http.body(replay));
            var stale=http.request("POST",ROOT+"/commands/reopen-due",payload,Map.of("Idempotency-Key",UUID.randomUUID().toString()));assertEquals(412,stale.statusCode(),stale.body());assertEquals("STALE_TASK",http.body(stale).get("code"));assertFalse(http.body(stale).containsKey("receiptRef"));
            assertEquals(List.of(),http.body(http.request("GET",ROOT+"/candidates?kind=DUE",null,Map.of())).get("candidates"));
            mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and grantee_appointment_id=? and authority_code='OPPORTUNITY_TASK_RECOVER'",seed.tenant(),service.appointmentId());
            assertEquals(403,http.request("POST",ROOT+"/commands/reopen-due",payload,headers).statusCode());
        }
    }
}
