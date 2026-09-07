package io.github.windyzhu3.ontologylaw.api;

import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.worker.*;
import io.github.windyzhu3.ontologylaw.lead.ContactFlowFixture;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor;
import io.github.windyzhu3.ontologylaw.api.adapter.generated.model.ConsumeR1ProjectionV1;
import java.util.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import javax.net.ssl.*;
import com.sun.net.httpserver.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;
import tools.jackson.databind.json.JsonMapper;

class R1WorkerTransportIT extends ContactFlowFixture {
    static final class MutableClock extends java.time.Clock {final java.time.Instant began=java.time.Instant.now();final java.util.concurrent.atomic.AtomicLong seconds=new java.util.concurrent.atomic.AtomicLong();public java.time.Instant instant(){return began.plusSeconds(seconds.get());}public java.time.ZoneId getZone(){return java.time.ZoneOffset.UTC;}public java.time.Clock withZone(java.time.ZoneId zone){return this;}void advance(long amount){seconds.addAndGet(amount);}}
    @TempDir Path directory;
    final char[] password="only-test-fixture".toCharArray();
    KeyStore key(String alias)throws Exception{
        var path=directory.resolve(alias+"-"+UUID.randomUUID()+".p12");
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool.exe").toString(),"-genkeypair","-alias",alias,"-keystore",path.toString(),"-storepass",new String(password),"-keypass",new String(password),"-dname","CN="+alias,"-keyalg","RSA","-keysize","2048","-validity","2","-ext","SAN=dns:localhost,ip:127.0.0.1","-noprompt").redirectErrorStream(true).start();
        try(var output=process.getInputStream()){output.transferTo(java.io.OutputStream.nullOutputStream());}assertEquals(0,process.waitFor());
        var store=KeyStore.getInstance("PKCS12");try(var in=Files.newInputStream(path)){store.load(in,password);}return store;
    }
    KeyStore trust(KeyStore keys,String alias)throws Exception{var store=KeyStore.getInstance("PKCS12");store.load(null,password);store.setCertificateEntry(alias,keys.getCertificate(alias));return store;}
    SSLContext ssl(KeyStore keys,KeyStore trust)throws Exception{var km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,password);var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(trust);var result=SSLContext.getInstance("TLS");result.init(km.getKeyManagers(),tm.getTrustManagers(),null);return result;}
    String fingerprint(KeyStore keys,String alias)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(keys.getCertificate(alias).getEncoded()));}
    R1WorkerTenantBindings.Binding binding(Actor actor,KeyStore keys,String alias)throws Exception{return new R1WorkerTenantBindings.Binding(actor.tenantId(),actor.principalId(),actor.appointmentId(),alias,fingerprint(keys,alias));}
    void emit(int number)throws Exception {try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{for(int n=0;n<number;n++){var event=UUID.randomUUID();sql(x,"insert into execution.domain_event (tenant_id,domain_event_id,event_type,event_schema_version,event_payload,payload_digest,command_id,correlation_id,occurred_at,source_fact_type,source_fact_id,source_fact_revision) values (?,?,'LeadCapturedV1',1,'{}',decode('44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a','hex'),?,?,clock_timestamp(),'lead.lead',?,?)",seed.tenant(),event,UUID.randomUUID(),UUID.randomUUID(),current.lead().id(),current.lead().revision());sql(x,"insert into execution.domain_event_outbox (tenant_id,domain_event_outbox_id,domain_event_id,queue_owner,status,available_at) values (?,?,?,'R1_PROJECTION','PENDING',clock_timestamp())",seed.tenant(),UUID.randomUUID(),event);}return null;});}}
    final class Harness implements AutoCloseable {
        final HttpsServer server;final InternalApiClient client;final R1WorkerTenantBindings registry;final R1WorkerTenantBindings.Binding binding;final Actor actor;final R1ProjectionOutboxPort outbox=R1ProjectionOutboxPort.databaseBacked(database::workerConnection);
        final AtomicInteger readinessCalls=new AtomicInteger(),consumeCalls=new AtomicInteger();final AtomicInteger readinessStatus=new AtomicInteger(204),consumeStatus=new AtomicInteger(204);volatile boolean noStore=true;volatile long consumeDelay;
        final List<UUID> consumedEvents=new CopyOnWriteArrayList<>();
        final CountDownLatch fourConsumers=new CountDownLatch(4);
        volatile CountDownLatch readyEntered,readyRelease,consumeEntered,consumeRelease;volatile boolean loseConsumeResponse;
        final List<String> recoveryKeys=new CopyOnWriteArrayList<>(),recoveryBodies=new CopyOnWriteArrayList<>();final AtomicInteger dueStatus=new AtomicInteger(200),recoveryStatus=new AtomicInteger(200);volatile String firstDuePage;
        volatile byte[] dueCursorKey=new byte[32];final List<String> dueCursors=new CopyOnWriteArrayList<>();
        Harness()throws Exception {
            actor=service("R1_PROJECTION_CONSUME");var serverKeys=key("SERVER");var clientKeys=key("CLIENT");binding=binding(actor,clientKeys,"CLIENT");registry=new R1WorkerTenantBindings("MVP-2026-09-07.1",List.of(binding));
            server=HttpsServer.create(new InetSocketAddress("localhost",0),0);var context=ssl(serverKeys,trust(clientKeys,"CLIENT"));server.setHttpsConfigurator(new HttpsConfigurator(context){public void configure(HttpsParameters parameters){var p=context.getDefaultSSLParameters();p.setNeedClientAuth(true);parameters.setSSLParameters(p);}});
            server.createContext("/internal/v1/projections/r1/readiness",exchange->{try{readinessCalls.incrementAndGet();int status=readinessStatus.get();if(status==204)try(var c=database.apiConnection()){status=new R1ProjectionReadinessService(policies).check(c,actor).status();}var readyGate=readyEntered;var readyContinue=readyRelease;if(readyGate!=null){readyGate.countDown();if(!readyContinue.await(15,TimeUnit.SECONDS))throw new AssertionError("Readiness transport latch");}if(noStore)exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.sendResponseHeaders(status,-1);}catch(Exception failure){throw new RuntimeException(failure);}finally{exchange.close();}});
            server.createContext("/internal/v1/projections/r1/consume",exchange->{try{consumeCalls.incrementAndGet();fourConsumers.countDown();var request=new JsonMapper().readValue(exchange.getRequestBody(),ConsumeR1ProjectionV1.class);consumedEvents.add(request.getDomainEventId());var consumeGate=consumeEntered;var consumeContinue=consumeRelease;if(consumeGate!=null){consumeGate.countDown();if(!consumeContinue.await(15,TimeUnit.SECONDS))throw new AssertionError("Consume transport latch");}if(consumeDelay>0)Thread.sleep(consumeDelay);int status=consumeStatus.get();if(status==204)try(var c=database.apiConnection()){status=new R1ProjectionConsumer(policies).consume(c,actor,request).status();}if(!loseConsumeResponse)exchange.sendResponseHeaders(status,-1);}catch(Exception failure){throw new RuntimeException(failure);}finally{exchange.close();}});
            for(String code:List.of("CONTACT_TASK_RECOVER","ROUTING_REVIEW_TASK_RECOVER"))mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,?,clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),actor.appointmentId(),seed.appointment(),seed.org(),code);
            server.createContext("/internal/v1/tasks/due",exchange->{try{int status=dueStatus.get();String body="";if(status==200){var query=exchange.getRequestURI().getRawQuery();assertFalse(query.contains("tenant"));var parameters=new HashMap<String,String>();for(var field:query.split("&")){var pair=field.split("=",2);parameters.put(pair[0],URLDecoder.decode(pair[1],java.nio.charset.StandardCharsets.UTF_8));}dueCursors.add(parameters.get("cursor"));try(var c=database.apiConnection()){var response=new DueR1TaskDiscoveryService(dueCursorKey).list(c,actor,io.github.windyzhu3.ontologylaw.api.adapter.generated.model.RecoveryTypeV1.valueOf(parameters.get("recoveryType")),Integer.valueOf(parameters.get("limit")),parameters.get("cursor"));status=response.status();if(status==200){var page=new TreeMap<String,Object>();page.put("candidates",response.page().getCandidates().stream().map(v->Map.of("recoveryType",v.getRecoveryType().toString(),"taskId",v.getTaskId().toString(),"expectedTaskRevision",v.getExpectedTaskRevision(),"waitReceiptId",v.getWaitReceiptId().toString(),"waitReceiptHash",v.getWaitReceiptHash(),"dueCutoff",java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(v.getDueCutoff()),"idempotencyKey",v.getIdempotencyKey().toString())).toList());if(response.page().getNextCursor()!=null)page.put("nextCursor",response.page().getNextCursor());body=CanonicalJson.encode(page);if(firstDuePage==null)firstDuePage=body;}}}byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length==0?-1:bytes.length);if(bytes.length>0)exchange.getResponseBody().write(bytes);}catch(Exception failure){throw new RuntimeException(failure);}finally{exchange.close();}});
            server.createContext("/internal/v1/tasks/commands/",exchange->{try{String body=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);String key=exchange.getRequestHeaders().getFirst("Idempotency-Key");recoveryKeys.add(key);recoveryBodies.add(body);var type=exchange.getRequestURI().getPath().endsWith("reopen-due-contact-tasks")?CommandEnvelope.Type.REOPEN_DUE_CONTACT_TASKS:CommandEnvelope.Type.REOPEN_DUE_ROUTING_REVIEW_TASKS;var values=new JsonMapper().readValue(body,new tools.jackson.core.type.TypeReference<Map<String,Object>>() {});values.put("expectedTaskRevision",((Number)values.get("expectedTaskRevision")).longValue());var outcome=execute(new CommandEnvelope(type,UUID.fromString(key),UUID.randomUUID(),actor,values));assertEquals(CommandOutcome.Status.SUCCEEDED,outcome.status());exchange.sendResponseHeaders(recoveryStatus.get(),-1);}catch(Exception failure){throw new RuntimeException(failure);}finally{exchange.close();}});
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());server.start();client=new InternalApiClient(URI.create("https://localhost:"+server.getAddress().getPort()),registry,Map.of("CLIENT",new InternalApiClient.Credentials(clientKeys,password,trust(serverKeys,"SERVER"))));
        }
        public void close(){client.close();server.stop(0);}
    }
    void awaitIdle(R1ProjectionDispatcher dispatcher)throws Exception {long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(dispatcher.availablePermits()!=4&&System.nanoTime()<deadline)Thread.sleep(10);assertEquals(4,dispatcher.availablePermits());}
    @Test void two_real_workers_interleave_current_fact_consumption_without_duplicate_valid_delivery()throws Exception{
        setupFlow(io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.Type.CONTACT_LEAD);emit(8);var before=counts();
        try(var h=new Harness();var first=new R1ProjectionDispatcher(h.registry,h.client,h.outbox,"WORKER_A",java.time.Clock.systemUTC());var second=new R1ProjectionDispatcher(h.registry,h.client,h.outbox,"WORKER_B",java.time.Clock.systemUTC());var executor=Executors.newVirtualThreadPerTaskExecutor()){
            h.consumeDelay=500;var gate=new CountDownLatch(1);var a=executor.submit(()->{gate.await();return first.poll(h.binding);});var b=executor.submit(()->{gate.await();return second.poll(h.binding);});gate.countDown();assertEquals(4,a.get(10,TimeUnit.SECONDS));assertEquals(4,b.get(10,TimeUnit.SECONDS));awaitIdle(first);awaitIdle(second);assertEquals(8,h.outbox.counts(seed.tenant(),100).delivered());assertEquals(8,h.consumedEvents.size());assertEquals(8,new HashSet<>(h.consumedEvents).size());assertEquals(before,counts());assertEquals(2,h.readinessCalls.get());
        }
    }
    void pastOutbox(String column)throws Exception {assertTrue(Set.of("lease_until","available_at").contains(column));try(var c=database.adminConnection()){c.setAutoCommit(false);sql(c,"set local session_replication_role=replica");sql(c,"update execution.domain_event_outbox set "+column+"=clock_timestamp()-interval '1 second' where tenant_id=?",seed.tenant());c.commit();}}
    void revokeProjection(Harness h)throws Exception {try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{io.github.windyzhu3.ontologylaw.identity.AuthorizationService.databaseBacked().lockForMutation(x,seed.tenant());sql(x,"update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and grantee_appointment_id=? and authority_code='R1_PROJECTION_CONSUME'",seed.tenant(),h.actor.appointmentId());return null;});}}
    @Test void readiness_retry_uses_bounded_backoff_without_probing_a_claim_batch()throws Exception{
        setupFlow(io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.Type.CONTACT_LEAD);emit(1);var clock=new MutableClock();
        try(var h=new Harness();var dispatcher=new R1ProjectionDispatcher(h.registry,h.client,h.outbox,"BACKOFF_IT",clock)){
            h.readinessStatus.set(503);assertEquals(0,dispatcher.poll(h.binding));assertEquals(1,h.readinessCalls.get());assertEquals(0,dispatcher.poll(h.binding));assertEquals(1,h.readinessCalls.get());clock.advance(1);assertEquals(0,dispatcher.poll(h.binding));assertEquals(2,h.readinessCalls.get());clock.advance(4);assertEquals(0,dispatcher.poll(h.binding));assertEquals(2,h.readinessCalls.get());clock.advance(1);assertEquals(0,dispatcher.poll(h.binding));assertEquals(3,h.readinessCalls.get());h.readinessStatus.set(204);clock.advance(30);assertEquals(1,dispatcher.poll(h.binding));awaitIdle(dispatcher);assertEquals(1,h.outbox.counts(seed.tenant(),100).delivered());
        }
    }
    @Test void consume_status_classification_and_expired_permanent_result_use_real_outbox_cas()throws Exception{
        for(int status:List.of(400,404,422,408,429,500,503,409,401)){
            setupFlow(io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.Type.CONTACT_LEAD);emit(1);
            try(var h=new Harness();var dispatcher=new R1ProjectionDispatcher(h.registry,h.client,h.outbox,"STATUS_IT",java.time.Clock.systemUTC())){h.consumeStatus.set(status);assertEquals(1,dispatcher.poll(h.binding));awaitIdle(dispatcher);var counts=h.outbox.counts(seed.tenant(),100);assertEquals(Set.of(400,404,422).contains(status)?1:0,counts.exhausted());assertEquals(Set.of(408,429,500,503).contains(status)?1:0,counts.pending());assertEquals(Set.of(409,401).contains(status)?1:0,counts.claimed());assertEquals(0,counts.delivered());assertEquals(status==401,dispatcher.frozen(h.binding));}
        }
        setupFlow(io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.Type.CONTACT_LEAD);emit(1);
        try(var h=new Harness();var dispatcher=new R1ProjectionDispatcher(h.registry,h.client,h.outbox,"EXPIRED_STATUS_IT",java.time.Clock.systemUTC())){h.consumeEntered=new CountDownLatch(1);h.consumeRelease=new CountDownLatch(1);h.consumeStatus.set(400);assertEquals(1,dispatcher.poll(h.binding));assertTrue(h.consumeEntered.await(5,TimeUnit.SECONDS));pastOutbox("lease_until");h.consumeRelease.countDown();awaitIdle(dispatcher);assertEquals(1,h.outbox.counts(seed.tenant(),100).claimed());assertEquals(0,h.outbox.counts(seed.tenant(),100).exhausted());}
    }
    @Test void crash_before_ack_and_local_expired_claim_do_not_send_or_ack_and_restart_recomputes()throws Exception{
        setupFlow(io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.Type.CONTACT_LEAD);emit(1);
        try(var h=new Harness()){
            h.consumeEntered=new CountDownLatch(1);h.consumeRelease=new CountDownLatch(1);var dispatcher=new R1ProjectionDispatcher(h.registry,h.client,h.outbox,"CRASH_IT",java.time.Clock.systemUTC());assertEquals(1,dispatcher.poll(h.binding));assertTrue(h.consumeEntered.await(5,TimeUnit.SECONDS));dispatcher.close();h.consumeRelease.countDown();awaitIdle(dispatcher);assertEquals(1,h.outbox.counts(seed.tenant(),100).claimed());pastOutbox("lease_until");assertEquals(1,h.outbox.reap(seed.tenant(),100));pastOutbox("available_at");h.consumeEntered=null;
            var clock=new MutableClock();clock.advance(120);try(var expired=new R1ProjectionDispatcher(h.registry,h.client,h.outbox,"LOCAL_EXPIRED_IT",clock)){assertEquals(1,expired.poll(h.binding));awaitIdle(expired);assertEquals(1,h.consumeCalls.get());assertEquals(1,h.outbox.counts(seed.tenant(),100).claimed());}
            pastOutbox("lease_until");h.outbox.reap(seed.tenant(),100);pastOutbox("available_at");try(var restarted=new R1ProjectionDispatcher(h.registry,h.client,h.outbox,"AFTER_CRASH_IT",java.time.Clock.systemUTC())){assertEquals(1,restarted.poll(h.binding));awaitIdle(restarted);assertEquals(1,h.outbox.counts(seed.tenant(),100).delivered());}
        }
    }
    @Test void older_readiness_success_cannot_unfreeze_newer_consume_403()throws Exception{
        setupFlow(io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.Type.CONTACT_LEAD);emit(1);
        try(var h=new Harness();var dispatcher=new R1ProjectionDispatcher(h.registry,h.client,h.outbox,"GENERATION_IT",java.time.Clock.systemUTC());var executor=Executors.newVirtualThreadPerTaskExecutor()){
            h.consumeEntered=new CountDownLatch(1);h.consumeRelease=new CountDownLatch(1);h.consumeStatus.set(403);assertEquals(1,dispatcher.poll(h.binding));assertTrue(h.consumeEntered.await(5,TimeUnit.SECONDS));emit(1);
            h.readyEntered=new CountDownLatch(1);h.readyRelease=new CountDownLatch(1);var old=executor.submit(()->dispatcher.poll(h.binding));assertTrue(h.readyEntered.await(5,TimeUnit.SECONDS));h.consumeRelease.countDown();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!dispatcher.frozen(h.binding)&&System.nanoTime()<deadline)Thread.sleep(10);assertTrue(dispatcher.frozen(h.binding));h.readyRelease.countDown();assertEquals(0,old.get(5,TimeUnit.SECONDS));assertEquals(1,h.outbox.counts(seed.tenant(),100).pending());
            h.readyEntered=null;h.consumeEntered=null;h.consumeStatus.set(204);assertEquals(1,dispatcher.poll(h.binding));awaitIdle(dispatcher);assertFalse(dispatcher.frozen(h.binding));assertEquals(1,h.outbox.counts(seed.tenant(),100).delivered());assertEquals(3,h.readinessCalls.get());
        }
    }
    @Test void accepted_postdecision_revocation_race_consumes_current_authorization_and_preserves_attempt()throws Exception{
        setupFlow(io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.Type.CONTACT_LEAD);emit(1);
        try(var h=new Harness();var dispatcher=new R1ProjectionDispatcher(h.registry,h.client,h.outbox,"RACE_IT",java.time.Clock.systemUTC());var executor=Executors.newVirtualThreadPerTaskExecutor()){
            h.readyEntered=new CountDownLatch(1);h.readyRelease=new CountDownLatch(1);var poll=executor.submit(()->dispatcher.poll(h.binding));assertTrue(h.readyEntered.await(5,TimeUnit.SECONDS));revokeProjection(h);h.readyRelease.countDown();assertEquals(1,poll.get(5,TimeUnit.SECONDS));awaitIdle(dispatcher);assertTrue(dispatcher.frozen(h.binding));assertEquals(1,h.outbox.counts(seed.tenant(),100).claimed());assertEquals("1",scalar("select attempt_count::text from execution.domain_event_outbox where tenant_id=?",seed.tenant()));h.readyEntered=null;assertEquals(0,dispatcher.poll(h.binding));
        }
    }
    @Test void seventh_and_eighth_auth_failures_reap_normally_and_restart_never_redrives_exhausted()throws Exception{
        for(int target:List.of(7,8)){
            setupFlow(io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.Type.CONTACT_LEAD);emit(1);
            try(var h=new Harness()){
                for(int n=1;n<target;n++){var claim=h.outbox.claim(seed.tenant(),"PRIOR_WORKER",1).getFirst();assertTrue(h.outbox.retry(claim,"NETWORK_ERROR"));pastOutbox("available_at");}
                try(var dispatcher=new R1ProjectionDispatcher(h.registry,h.client,h.outbox,"AUTH_IT",java.time.Clock.systemUTC())){h.consumeStatus.set(403);assertEquals(1,dispatcher.poll(h.binding));awaitIdle(dispatcher);assertTrue(dispatcher.frozen(h.binding));assertEquals(Integer.toString(target),scalar("select attempt_count::text from execution.domain_event_outbox where tenant_id=?",seed.tenant()));assertEquals(Integer.toString(target*2-1),scalar("select revision::text from execution.domain_event_outbox where tenant_id=?",seed.tenant()));revokeProjection(h);pastOutbox("lease_until");assertEquals(0,dispatcher.poll(h.binding));assertEquals(target==8?1:0,h.outbox.counts(seed.tenant(),100).exhausted());}
                try(var restarted=new R1ProjectionDispatcher(h.registry,h.client,h.outbox,"RESTART_IT",java.time.Clock.systemUTC())){assertEquals(0,restarted.poll(h.binding));mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,'R1_PROJECTION_CONSUME',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),h.actor.appointmentId(),seed.appointment(),seed.org());h.consumeStatus.set(204);pastOutbox("available_at");assertEquals(target==8?0:1,restarted.poll(h.binding));awaitIdle(restarted);assertEquals(target==8?0:1,h.outbox.counts(seed.tenant(),100).delivered());assertEquals(target==8?1:0,h.outbox.counts(seed.tenant(),100).exhausted());}
            }
        }
    }
    @Test void real_timeout_discards_late_readiness_without_claim_and_response_loss_retries_readonly_consume()throws Exception{
        setupFlow(io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.Type.CONTACT_LEAD);emit(1);var clock=new MutableClock();
        try(var h=new Harness();var dispatcher=new R1ProjectionDispatcher(h.registry,h.client,h.outbox,"TIMEOUT_IT",clock);var executor=Executors.newVirtualThreadPerTaskExecutor()){
            h.readyEntered=new CountDownLatch(1);h.readyRelease=new CountDownLatch(1);var poll=executor.submit(()->dispatcher.poll(h.binding));assertTrue(h.readyEntered.await(5,TimeUnit.SECONDS));assertEquals(0,poll.get(12,TimeUnit.SECONDS));h.readyRelease.countDown();assertEquals(1,h.outbox.counts(seed.tenant(),100).pending());assertEquals("0",scalar("select attempt_count::text from execution.domain_event_outbox where tenant_id=?",seed.tenant()));h.readyEntered=null;
            clock.advance(1);h.loseConsumeResponse=true;assertEquals(1,dispatcher.poll(h.binding));awaitIdle(dispatcher);assertEquals(1,h.outbox.counts(seed.tenant(),100).pending());h.loseConsumeResponse=false;pastOutbox("available_at");assertEquals(1,dispatcher.poll(h.binding));awaitIdle(dispatcher);assertEquals(1,h.outbox.counts(seed.tenant(),100).delivered());
        }
    }
    @Test void dispatcher_acquires_four_permits_before_fresh_readiness_and_sends_four_near_timeout_requests_without_queue()throws Exception{
        setupFlow(io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.Type.CONTACT_LEAD);emit(8);
        try(var h=new Harness();var dispatcher=new R1ProjectionDispatcher(h.registry,h.client,h.outbox,"DISPATCH_IT",java.time.Clock.systemUTC())){
            h.consumeDelay=8500;assertEquals(4,dispatcher.poll(h.binding));assertTrue(h.fourConsumers.await(3,TimeUnit.SECONDS));assertEquals(0,dispatcher.availablePermits());assertEquals(0,dispatcher.poll(h.binding));assertEquals(1,h.readinessCalls.get());awaitIdle(dispatcher);assertEquals(4,h.outbox.counts(seed.tenant(),100).delivered());
            h.consumeDelay=0;assertEquals(4,dispatcher.poll(h.binding));awaitIdle(dispatcher);assertEquals(2,h.readinessCalls.get());assertEquals(8,h.outbox.counts(seed.tenant(),100).delivered());assertEquals(0,dispatcher.poll(h.binding));assertEquals(3,h.readinessCalls.get());assertEquals(0,dispatcher.poll(h.binding));assertEquals(4,h.readinessCalls.get());
        }
    }
    @Test void readiness_failure_never_claims_and_consume_authorization_freezes_without_ack_or_failure_cas()throws Exception{
        setupFlow(io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.Type.CONTACT_LEAD);emit(1);var clock=new MutableClock();
        try(var h=new Harness();var dispatcher=new R1ProjectionDispatcher(h.registry,h.client,h.outbox,"DISPATCH_IT",clock)){
            h.noStore=false;assertEquals(0,dispatcher.poll(h.binding));assertEquals(1,h.outbox.counts(seed.tenant(),100).pending());
            clock.advance(1);h.noStore=true;h.readinessStatus.set(403);assertEquals(0,dispatcher.poll(h.binding));assertTrue(dispatcher.frozen(h.binding));assertEquals(1,h.outbox.counts(seed.tenant(),100).pending());
            h.readinessStatus.set(204);h.consumeStatus.set(403);assertEquals(1,dispatcher.poll(h.binding));awaitIdle(dispatcher);assertTrue(dispatcher.frozen(h.binding));assertEquals(1,h.outbox.counts(seed.tenant(),100).claimed());assertEquals("1",scalar("select attempt_count::text from execution.domain_event_outbox where tenant_id=?",seed.tenant()));assertEquals("1",scalar("select revision::text from execution.domain_event_outbox where tenant_id=?",seed.tenant()));
        }
    }
    @Test void due_scheduler_uses_exact_single_task_http_request_and_stable_key_for_both_types()throws Exception{
        for(var type:InternalApiClient.RecoveryType.values()){
            setupFlow(type==InternalApiClient.RecoveryType.CONTACT_TASK?io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.Type.CONTACT_LEAD:io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP);
            try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.databaseBacked().waitUntil(x,seed.tenant(),current,seed.appointment(),businessAt.plusSeconds(3600),businessAt);return null;});}
            try(var h=new Harness();var scheduler=new DueTaskScheduler(h.registry,h.client,java.time.Clock.systemUTC())){
                assertEquals(1,scheduler.poll(h.binding,type));assertEquals("OPEN",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),current.selector().id()));assertEquals(1,h.recoveryKeys.size());
                var payload=new JsonMapper().readValue(h.recoveryBodies.getFirst(),new tools.jackson.core.type.TypeReference<Map<String,Object>>() {});assertEquals(Set.of("taskId","expectedTaskRevision","waitReceiptId","waitReceiptHash","dueCutoff"),payload.keySet());assertEquals(businessAt.plusSeconds(3600),java.time.OffsetDateTime.parse((String)payload.get("dueCutoff")).toInstant());
                var candidate=h.client.due(h.binding,type,null);assertEquals(200,candidate.status());assertTrue(candidate.candidates().isEmpty());assertEquals(0,scheduler.poll(h.binding,type));
            }
        }
    }
    @Test void recovery_authorities_freeze_independently_and_projection_readiness_cannot_clear_recovery_fault()throws Exception{
        setupFlow(io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.Type.CONTACT_LEAD);try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.databaseBacked().waitUntil(x,seed.tenant(),current,seed.appointment(),businessAt.plusSeconds(3600),businessAt);return null;});}var clock=new MutableClock();
        try(var h=new Harness();var scheduler=new DueTaskScheduler(h.registry,h.client,clock);var dispatcher=new R1ProjectionDispatcher(h.registry,h.client,h.outbox,"ISOLATED_AUTH_IT",clock)){
            mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and grantee_appointment_id=? and authority_code='CONTACT_TASK_RECOVER'",seed.tenant(),h.actor.appointmentId());
            assertEquals(0,scheduler.poll(h.binding,InternalApiClient.RecoveryType.CONTACT_TASK));assertTrue(scheduler.frozen(h.binding,InternalApiClient.RecoveryType.CONTACT_TASK));assertEquals(0,scheduler.poll(h.binding,InternalApiClient.RecoveryType.ROUTING_REVIEW_TASK));assertFalse(scheduler.frozen(h.binding,InternalApiClient.RecoveryType.ROUTING_REVIEW_TASK));assertEquals(0,dispatcher.poll(h.binding));assertFalse(dispatcher.frozen(h.binding));assertTrue(scheduler.frozen(h.binding,InternalApiClient.RecoveryType.CONTACT_TASK));
            mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,'CONTACT_TASK_RECOVER',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),h.actor.appointmentId(),seed.appointment(),seed.org());clock.advance(1);assertEquals(1,scheduler.poll(h.binding,InternalApiClient.RecoveryType.CONTACT_TASK));assertFalse(scheduler.frozen(h.binding));
        }
    }
    @Test void recovery_response_loss_and_second_worker_replay_keep_exact_key_payload_and_single_receipt()throws Exception{
        setupFlow(io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.Type.CONTACT_LEAD);try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.databaseBacked().waitUntil(x,seed.tenant(),current,seed.appointment(),businessAt.plusSeconds(3600),businessAt);return null;});}var clock=new MutableClock();
        try(var h=new Harness()){
            var secondWorkerCandidate=h.client.due(h.binding,InternalApiClient.RecoveryType.CONTACT_TASK,null).candidates().getFirst();h.recoveryStatus.set(503);
            try(var scheduler=new DueTaskScheduler(h.registry,h.client,clock)){assertEquals(0,scheduler.poll(h.binding,InternalApiClient.RecoveryType.CONTACT_TASK));assertEquals(1,h.recoveryKeys.size());assertEquals(0,scheduler.poll(h.binding,InternalApiClient.RecoveryType.CONTACT_TASK));assertEquals(1,h.recoveryKeys.size());}
            var committed=counts();h.recoveryStatus.set(200);assertEquals(200,h.client.recover(h.binding,secondWorkerCandidate).status());assertEquals(committed,counts());assertEquals(List.of(secondWorkerCandidate.idempotencyKey().toString(),secondWorkerCandidate.idempotencyKey().toString()),h.recoveryKeys);assertEquals(h.recoveryBodies.getFirst(),h.recoveryBodies.getLast());
            try(var restarted=new DueTaskScheduler(h.registry,h.client,clock)){assertEquals(0,restarted.poll(h.binding,InternalApiClient.RecoveryType.CONTACT_TASK));assertEquals(committed,counts());}
        }
    }
    @Test void default_fifty_row_page_progresses_past_denies_and_invalidated_cursor_restarts_first_page()throws Exception{
        setupFlow(io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP);var ids=new ArrayList<UUID>();for(int n=0;n<50;n++){var id=UUID.randomUUID();ids.add(id);addTask(id,businessAt.minusSeconds(7200),businessAt.minusSeconds(3600),"WAITING");}var clock=new MutableClock();
        try(var h=new Harness();var scheduler=new DueTaskScheduler(h.registry,h.client,clock)){
            for(var id:ids)mutate("insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values (?,?,?,?,'ROUTING_REVIEW_TASK_RECOVER','DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),'responsibility.task_occurrence',?,1)",seed.tenant(),UUID.randomUUID(),h.actor.principalId(),seed.appointment(),id);
            var before=counts();assertEquals(0,scheduler.poll(h.binding,InternalApiClient.RecoveryType.ROUTING_REVIEW_TASK));assertNull(h.dueCursors.getFirst());assertTrue(h.firstDuePage.contains("nextCursor"));var changed=new byte[32];Arrays.fill(changed,(byte)1);h.dueCursorKey=changed;assertEquals(0,scheduler.poll(h.binding,InternalApiClient.RecoveryType.ROUTING_REVIEW_TASK));assertNotNull(h.dueCursors.get(1));clock.advance(1);assertEquals(0,scheduler.poll(h.binding,InternalApiClient.RecoveryType.ROUTING_REVIEW_TASK));assertNull(h.dueCursors.get(2));assertEquals(before,counts());assertTrue(h.recoveryKeys.isEmpty());
        }
    }
    @Test void natural_expiry_after_readiness_decision_before_claim_is_not_mistaken_for_remote_invalidation()throws Exception{
        setupFlow(io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.Type.CONTACT_LEAD);emit(1);
        try(var h=new Harness();var dispatcher=new R1ProjectionDispatcher(h.registry,h.client,h.outbox,"EXPIRY_RACE_IT",java.time.Clock.systemUTC());var executor=Executors.newVirtualThreadPerTaskExecutor()){
            revokeProjection(h);mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,valid_until,state,created_at) values (?,?,?,?,?,'R1_PROJECTION_CONSUME',clock_timestamp()-interval '1 day',clock_timestamp()+interval '2 seconds','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),h.actor.appointmentId(),seed.appointment(),seed.org());
            h.readyEntered=new CountDownLatch(1);h.readyRelease=new CountDownLatch(1);var poll=executor.submit(()->dispatcher.poll(h.binding));assertTrue(h.readyEntered.await(5,TimeUnit.SECONDS));Thread.sleep(2200);h.readyRelease.countDown();assertEquals(1,poll.get(5,TimeUnit.SECONDS));awaitIdle(dispatcher);assertTrue(dispatcher.frozen(h.binding));assertEquals(1,h.outbox.counts(seed.tenant(),100).claimed());assertEquals("1",scalar("select attempt_count::text from execution.domain_event_outbox where tenant_id=?",seed.tenant()));
        }
    }
    @Test void registry_rejects_empty_duplicate_tenant_certificate_actor_and_wrong_release()throws Exception{
        var b=new R1WorkerTenantBindings.Binding(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"CLIENT","a".repeat(64));
        assertThrows(IllegalArgumentException.class,()->new R1WorkerTenantBindings("MVP-2026-09-07.1",List.of()));
        assertThrows(IllegalArgumentException.class,()->new R1WorkerTenantBindings("MVP-2026-09-06.3",List.of(b)));
        assertThrows(IllegalArgumentException.class,()->new R1WorkerTenantBindings("MVP-2026-09-07.1",List.of(b,b)));
        assertThrows(IllegalArgumentException.class,()->new R1WorkerTenantBindings("MVP-2026-09-07.1",List.of(b,new R1WorkerTenantBindings.Binding(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"SECOND",b.certificateSha256()))));
    }
    @Test void real_mtls_readiness_calls_query_service_and_rejects_untrusted_or_mismatched_credentials()throws Exception{
        setupFlow(io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.Type.CONTACT_LEAD);var actor=service("R1_PROJECTION_CONSUME");
        var serverKeys=key("SERVER");var clientKeys=key("CLIENT");var rogueKeys=key("ROGUE");var binding=binding(actor,clientKeys,"CLIENT");var registry=new R1WorkerTenantBindings("MVP-2026-09-07.1",List.of(binding));var calls=new AtomicInteger();
        var server=HttpsServer.create(new InetSocketAddress("localhost",0),0);var context=ssl(serverKeys,trust(clientKeys,"CLIENT"));server.setHttpsConfigurator(new HttpsConfigurator(context){public void configure(HttpsParameters parameters){var p=context.getDefaultSSLParameters();p.setNeedClientAuth(true);parameters.setSSLParameters(p);}});
        server.createContext("/internal/v1/projections/r1/readiness",exchange->{try{assertEquals("GET",exchange.getRequestMethod());assertNull(exchange.getRequestURI().getQuery());assertEquals(0,exchange.getRequestBody().readAllBytes().length);assertEquals(binding.certificateSha256(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(((HttpsExchange)exchange).getSSLSession().getPeerCertificates()[0].getEncoded())));calls.incrementAndGet();try(var c=database.apiConnection()){var response=new R1ProjectionReadinessService(policies).check(c,actor);exchange.getResponseHeaders().set("Cache-Control",response.cacheControl());exchange.sendResponseHeaders(response.status(),-1);}}catch(Exception failure){throw new RuntimeException(failure);}finally{exchange.close();}});server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());server.start();
        try {var origin=URI.create("https://localhost:"+server.getAddress().getPort());
        try(var client=new InternalApiClient(origin,registry,Map.of("CLIENT",new InternalApiClient.Credentials(clientKeys,password,trust(serverKeys,"SERVER"))))){assertEquals(204,client.readiness(binding).status());assertEquals(204,client.readiness(binding).status());assertEquals(2,calls.get());}
        try(var client=new InternalApiClient(origin,registry,Map.of("CLIENT",new InternalApiClient.Credentials(clientKeys,password,trust(rogueKeys,"ROGUE"))))){assertEquals(503,client.readiness(binding).status());assertEquals(2,calls.get());}
        var rogueBinding=binding(actor,rogueKeys,"ROGUE");var rogueRegistry=new R1WorkerTenantBindings("MVP-2026-09-07.1",List.of(rogueBinding));
        try(var client=new InternalApiClient(origin,rogueRegistry,Map.of("ROGUE",new InternalApiClient.Credentials(rogueKeys,password,trust(serverKeys,"SERVER"))))){assertEquals(503,client.readiness(rogueBinding).status());assertEquals(2,calls.get());}
        assertThrows(IllegalArgumentException.class,()->new InternalApiClient(origin,registry,Map.of("CLIENT",new InternalApiClient.Credentials(rogueKeys,password,trust(serverKeys,"SERVER")))));
        }finally{server.stop(0);}
    }
}
