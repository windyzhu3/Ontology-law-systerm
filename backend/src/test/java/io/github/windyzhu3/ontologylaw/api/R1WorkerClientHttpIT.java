package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.testing.TlsFixture;
import io.github.windyzhu3.ontologylaw.worker.InternalApiClient;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class R1WorkerClientHttpIT extends R1HttpFixture {
    @TempDir Path directory;
    @Test void all_background_callers_share_a_bounded_request_budget()throws Exception {
        setupContact();var actor=service("R1_PROJECTION_CONSUME");
        var firstFour=new java.util.concurrent.CountDownLatch(4);var fifth=new java.util.concurrent.CountDownLatch(5);var release=new java.util.concurrent.CountDownLatch(1);
        var rejected=new java.util.concurrent.atomic.AtomicBoolean();
        var active=new java.util.concurrent.atomic.AtomicInteger();var peak=new java.util.concurrent.atomic.AtomicInteger();
        try(var http=new HttpHarness(actor,new TlsFixture(directory));var worker=http.workerClient(()->{
            int n=active.incrementAndGet();peak.accumulateAndGet(n,Math::max);firstFour.countDown();fifth.countDown();
            try{return !rejected.get()&&release.await(5,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();return false;}finally{active.decrementAndGet();}
        });var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            var requests=new java.util.ArrayList<java.util.concurrent.Future<InternalApiClient.Result>>();
            for(int i=0;i<8;i++)requests.add(pool.submit(()->worker.readiness(http.workerBinding)));
            try{assertTrue(firstFour.await(3,java.util.concurrent.TimeUnit.SECONDS));assertFalse(fifth.await(300,java.util.concurrent.TimeUnit.MILLISECONDS),"Different schedulers must not independently exhaust the API pool");}
            finally{release.countDown();}
            for(var request:requests)assertEquals(204,request.get(15,java.util.concurrent.TimeUnit.SECONDS).status());
            assertTrue(peak.get()<=4);
            rejected.set(true);for(int i=0;i<8;i++)assertEquals(503,worker.readiness(http.workerBinding).status());
            rejected.set(false);assertEquals(204,worker.readiness(http.workerBinding).status(),"Failed gates must release their request slots");
        }
    }
    @Test void every_outbound_request_rechecks_actual_worker_deployment_gate_before_any_http_dispatch()throws Exception {
        setupContact();var actor=service("R1_PROJECTION_CONSUME");var gate=RuntimeDatabase.databaseBacked(database::workerConnection,RuntimeDatabase.Role.WORKER,new RuntimeDatabase.Expected("52-plus-2-r2-v19",java.util.HexFormat.of().parseHex("11".repeat(32)),java.util.HexFormat.of().parseHex("22".repeat(32))));
        try(var http=new HttpHarness(actor,new TlsFixture(directory));var worker=http.workerClient(gate::healthy)) {
            assertEquals(204,worker.readiness(http.workerBinding).status());int received=http.received.get();var before=counts();
            try(var c=database.migratorConnection()){io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql(c,"update platform_meta.deployment_state set operating_mode='BLOCKED',revision=revision+1,changed_at=clock_timestamp() where deployment_state_key='PRIMARY'");}
            assertEquals(503,worker.readiness(http.workerBinding).status());assertEquals(503,worker.due(http.workerBinding,InternalApiClient.RecoveryType.CONTACT_TASK,null).status());assertEquals(received,http.received.get());assertEquals(before,counts());
        }
    }
    @Test void existing_production_client_accepts_real_tls_readiness_and_consume_and_preserves_business_error_status()throws Exception {
        setupContact();assertEquals(CommandOutcome.Status.SUCCEEDED,execute(prepare(contact("NOT_CONNECTED"))).status());var actor=service("R1_PROJECTION_CONSUME");
        var claim=R1ProjectionOutboxPort.databaseBacked(database::workerConnection).claim(seed.tenant(),"REAL_CLIENT_IT",1).getFirst();
        try(var http=new HttpHarness(actor,new TlsFixture(directory));var worker=http.workerClient()) {
            var before=counts();assertEquals(204,worker.readiness(http.workerBinding).status());assertEquals(204,worker.consume(http.workerBinding,claim).status());assertEquals(before,counts());
            var stale=new R1ProjectionOutboxPort.Claim(claim.tenantId(),claim.outboxId(),claim.eventId(),claim.revision()+1,claim.leaseOwner(),claim.fencingToken(),claim.attempt(),claim.leaseUntil());
            assertEquals(409,worker.consume(http.workerBinding,stale).status());assertEquals(before,counts());
        }
    }
    @Test void existing_production_client_accepts_empty_due_page_without_a_null_cursor()throws Exception {
        setupContact();var actor=service("CONTACT_TASK_RECOVER");
        try(var http=new HttpHarness(actor,new TlsFixture(directory));var worker=http.workerClient()) {
            var before=counts();var page=worker.due(http.workerBinding,InternalApiClient.RecoveryType.CONTACT_TASK,null);assertEquals(200,page.status());assertTrue(page.candidates().isEmpty());assertNull(page.nextCursor());assertEquals(before,counts());
            var raw=http.request("GET","/internal/v1/tasks/due?recoveryType=CONTACT_TASK",null,java.util.Map.of());assertEquals(200,raw.statusCode());assertEquals(java.util.Set.of("candidates"),http.body(raw).keySet());assertEquals(before,counts());
        }
    }
}
