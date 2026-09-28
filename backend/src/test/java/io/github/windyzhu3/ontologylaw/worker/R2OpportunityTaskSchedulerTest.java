package io.github.windyzhu3.ontologylaw.worker;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class R2OpportunityTaskSchedulerTest {
    @Test void background_scans_leave_connection_capacity_for_interactive_reads_and_visit_every_kind() throws Exception {
        var firstTwo=new CountDownLatch(2);var third=new CountDownLatch(3);var release=new CountDownLatch(1);
        var seen=ConcurrentHashMap.<InternalApiClient.OpportunityKind>newKeySet();
        var all=new CountDownLatch(InternalApiClient.OpportunityKind.values().length);
        var active=new java.util.concurrent.atomic.AtomicInteger();var peak=new java.util.concurrent.atomic.AtomicInteger();
        var gateway=new R2OpportunityTaskScheduler.Gateway(){
            public InternalApiClient.OpportunityPage discover(R1WorkerTenantBindings.Binding b,InternalApiClient.OpportunityKind k,String cursor){
                int current=active.incrementAndGet();peak.accumulateAndGet(current,Math::max);firstTwo.countDown();third.countDown();
                try {release.await(5,TimeUnit.SECONDS);if(seen.add(k))all.countDown();return page(List.of(),null);}
                catch(InterruptedException e){Thread.currentThread().interrupt();return new InternalApiClient.OpportunityPage(503,List.of(),null);}
                finally {active.decrementAndGet();}
            }
            public InternalApiClient.Result execute(R1WorkerTenantBindings.Binding b,InternalApiClient.OpportunityCandidate c){throw new AssertionError("Empty scan must not execute");}
        };
        try(var scheduler=new R2OpportunityTaskScheduler(registry,gateway,clock)){
            scheduler.start();assertTrue(firstTwo.await(2,TimeUnit.SECONDS));
            try {assertFalse(third.await(300,TimeUnit.MILLISECONDS),"Background scans exhausted interactive connection capacity");}
            finally {release.countDown();}
            assertTrue(all.await(3,TimeUnit.SECONDS));assertTrue(peak.get()<=2);assertEquals(Set.of(InternalApiClient.OpportunityKind.values()),seen);
        }
    }

    private final R1WorkerTenantBindings.Binding binding=new R1WorkerTenantBindings.Binding(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"WORKER","a".repeat(64));
    private final R1WorkerTenantBindings registry=new R1WorkerTenantBindings("MVP-2026-09-08.3",List.of(binding));
    private final MutableClock clock=new MutableClock();
    private static final InternalApiClient.OpportunityKind INITIAL=InternalApiClient.OpportunityKind.INITIAL;
    private static final InternalApiClient.OpportunityKind DUE=InternalApiClient.OpportunityKind.DUE;
    private static InternalApiClient.OpportunityCandidate candidate(int n){return new InternalApiClient.InitialOpportunityCandidate(UUID.fromString(String.format("00000000-0000-5000-8000-%012d",n)),UUID.randomUUID(),0);}
    private static final class MutableClock extends Clock {
        Instant now=Instant.parse("2026-09-14T00:00:00Z");public Instant instant(){return now;}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId ignored){return this;}void advance(long seconds){now=now.plusSeconds(seconds);}
    }
    private static class Gateway implements R2OpportunityTaskScheduler.Gateway {
        final Deque<InternalApiClient.OpportunityPage> pages=new ArrayDeque<>();final Deque<Integer> responses=new ArrayDeque<>();
        final List<String> cursors=new ArrayList<>();final List<InternalApiClient.OpportunityCandidate> attempts=new ArrayList<>();
        public InternalApiClient.OpportunityPage discover(R1WorkerTenantBindings.Binding b,InternalApiClient.OpportunityKind kind,String cursor){cursors.add(cursor);return pages.isEmpty()?page(List.of(),null):pages.removeFirst();}
        public InternalApiClient.Result execute(R1WorkerTenantBindings.Binding b,InternalApiClient.OpportunityCandidate candidate){attempts.add(candidate);return new InternalApiClient.Result(responses.isEmpty()?200:responses.removeFirst(),"");}
    }
    private static InternalApiClient.OpportunityPage page(List<InternalApiClient.OpportunityCandidate> candidates,String cursor){return new InternalApiClient.OpportunityPage(200,candidates,cursor);}
    @Test void expired_discovery_cursor_restarts_promptly_after_repeated_timeouts_without_replaying_acknowledgements()throws Exception {
        var state=new R2OpportunityTaskScheduler.State();state.cursor="expired-scan";state.active=true;state.failures=6;state.acknowledged=17;
        state.retryAt=clock.instant();state.publish(R2OpportunityTaskScheduler.Status.RETRY_WAIT);
        var saved=new java.util.concurrent.atomic.AtomicReference<>(R2OpportunityCheckpointCodec.encode(state,INITIAL));
        io.github.windyzhu3.ontologylaw.execution.R2OpportunityCheckpointPort storage=key->new io.github.windyzhu3.ontologylaw.execution.R2OpportunityCheckpointPort.Session(){
            public byte[] load(){return saved.get();}public void save(byte[] body){saved.set(body.clone());}public void close(){}
        };
        var api=new Gateway();api.pages.add(new InternalApiClient.OpportunityPage(400,List.of(),null));api.pages.add(page(List.of(candidate(12)),null));
        try(var scheduler=new R2OpportunityTaskScheduler(registry,api,clock,storage)){
            assertEquals(0,scheduler.poll(binding,INITIAL));assertEquals(List.of("expired-scan"),api.cursors);
            clock.advance(1);assertEquals(1,scheduler.poll(binding,INITIAL));assertNull(api.cursors.get(1));
            assertEquals(18,scheduler.snapshot(binding,INITIAL).acknowledged());assertEquals(1,api.attempts.size());
        }
    }
    @Test void completed_scan_pauses_before_rediscovery_without_delaying_pending_pages(){
        for(var kind:List.of(INITIAL,DUE,InternalApiClient.OpportunityKind.CONTRACT_PREPARATION,InternalApiClient.OpportunityKind.OWNER_EXCEPTION)){
            var api=new Gateway();try(var scheduler=new R2OpportunityTaskScheduler(registry,api,clock)){
                scheduler.poll(binding,kind);scheduler.poll(binding,kind);assertEquals(1,api.cursors.size());
                int interval=kind==InternalApiClient.OpportunityKind.OWNER_EXCEPTION?60:5;
                clock.advance(interval-1);scheduler.poll(binding,kind);assertEquals(1,api.cursors.size());
                clock.advance(1);scheduler.poll(binding,kind);assertEquals(2,api.cursors.size());
            }
        }
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={408,503}) void restored_discovery_outage_is_rechecked_within_one_minute_without_clearing_checkpoint(int transientStatus) throws Exception {
        var state=new R2OpportunityTaskScheduler.State();state.failures=7;state.lastStatus=transientStatus;
        state.retryAt=clock.instant().plusSeconds(7200);state.acknowledged=12;state.publish(R2OpportunityTaskScheduler.Status.RETRY_WAIT);
        var saved=new java.util.concurrent.atomic.AtomicReference<>(R2OpportunityCheckpointCodec.encode(state,INITIAL));
        io.github.windyzhu3.ontologylaw.execution.R2OpportunityCheckpointPort storage=key->new io.github.windyzhu3.ontologylaw.execution.R2OpportunityCheckpointPort.Session(){
            public byte[] load(){return saved.get();}public void save(byte[] body){saved.set(body.clone());}public void close(){}
        };
        var api=new Gateway();api.pages.add(page(List.of(candidate(9)),null));
        try(var scheduler=new R2OpportunityTaskScheduler(registry,api,clock,storage)){
            scheduler.poll(binding,INITIAL);assertTrue(api.cursors.isEmpty());clock.advance(59);
            scheduler.poll(binding,INITIAL);assertTrue(api.cursors.isEmpty());clock.advance(1);
            assertEquals(1,scheduler.poll(binding,INITIAL));assertEquals(13,scheduler.snapshot(binding,INITIAL).acknowledged());
        }
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={408,503}) void pending_uncertain_command_retries_original_candidate_within_one_minute_after_service_recovery(int transientStatus) throws Exception {
        var item=candidate(8);var state=new R2OpportunityTaskScheduler.State();state.page=page(List.of(item),null);
        state.active=true;state.uncertain=true;state.failures=7;state.lastStatus=transientStatus;
        state.retryAt=clock.instant().plusSeconds(7200);state.publish(R2OpportunityTaskScheduler.Status.RETRY_WAIT);
        var saved=new java.util.concurrent.atomic.AtomicReference<>(R2OpportunityCheckpointCodec.encode(state,INITIAL));
        io.github.windyzhu3.ontologylaw.execution.R2OpportunityCheckpointPort storage=key->new io.github.windyzhu3.ontologylaw.execution.R2OpportunityCheckpointPort.Session(){
            public byte[] load(){return saved.get();}public void save(byte[] body){saved.set(body.clone());}public void close(){}
        };
        var api=new Gateway();try(var scheduler=new R2OpportunityTaskScheduler(registry,api,clock,storage)){
            scheduler.poll(binding,INITIAL);clock.advance(59);scheduler.poll(binding,INITIAL);
            assertTrue(api.attempts.isEmpty());assertTrue(api.cursors.isEmpty());clock.advance(1);
            assertEquals(1,scheduler.poll(binding,INITIAL));assertEquals(List.of(item),api.attempts);assertTrue(api.cursors.isEmpty());
        }
    }
    @Test void uncertain_delivery_retries_exact_candidate_before_rediscovery(){
        var api=new Gateway();var item=candidate(1);api.pages.add(page(List.of(item),null));api.responses.addAll(List.of(408,200));
        try(var scheduler=new R2OpportunityTaskScheduler(registry,api,clock)){
            assertEquals(0,scheduler.poll(binding,INITIAL));assertEquals(1,api.cursors.size());assertEquals(1,scheduler.snapshot(binding,INITIAL).pending());
            assertEquals(0,scheduler.poll(binding,INITIAL));assertEquals(1,api.attempts.size());clock.advance(1);
            assertEquals(1,scheduler.poll(binding,INITIAL));assertEquals(List.of(item,item),api.attempts);assertEquals(1,api.cursors.size());assertEquals(R2OpportunityTaskScheduler.Status.READY,scheduler.snapshot(binding,INITIAL).status());
        }
    }
    @Test void rejected_candidate_does_not_starve_later_candidates_or_empty_filtered_pages(){
        var api=new Gateway();api.pages.add(page(List.of(candidate(1),candidate(2),candidate(3)),"next"));api.pages.add(page(List.of(),"last"));api.pages.add(page(List.of(candidate(4)),null));api.responses.addAll(List.of(422,412,200,200));
        try(var scheduler=new R2OpportunityTaskScheduler(registry,api,clock)){
            assertEquals(1,scheduler.poll(binding,INITIAL));assertEquals(0,scheduler.poll(binding,INITIAL));assertEquals(1,scheduler.poll(binding,INITIAL));
            assertEquals(Arrays.asList(null,"next","last"),api.cursors);assertEquals(4,api.attempts.size());assertEquals(2,scheduler.snapshot(binding,INITIAL).rejected());assertEquals(R2OpportunityTaskScheduler.Status.DEGRADED,scheduler.snapshot(binding,INITIAL).status());
            scheduler.poll(binding,INITIAL);assertEquals(3,api.cursors.size());
        }
    }
    @Test void per_poll_command_budget_preserves_page_position(){
        var api=new Gateway();var rows=new ArrayList<InternalApiClient.OpportunityCandidate>();for(int n=1;n<=11;n++)rows.add(candidate(n));api.pages.add(page(rows,"next"));
        try(var scheduler=new R2OpportunityTaskScheduler(registry,api,clock)){
            assertEquals(10,scheduler.poll(binding,INITIAL));assertEquals(1,scheduler.snapshot(binding,INITIAL).pending());assertEquals(1,scheduler.poll(binding,INITIAL));assertEquals(1,api.cursors.size());
            scheduler.poll(binding,INITIAL);assertEquals(Arrays.asList(null,"next"),api.cursors);
        }
    }
    @Test void expired_cursor_resets_discovery_only_after_pending_retry_is_acknowledged(){
        var api=new Gateway();var item=candidate(1);api.pages.add(page(List.of(item),"expired"));api.pages.add(new InternalApiClient.OpportunityPage(400,List.of(),null));api.pages.add(page(List.of(),null));api.responses.addAll(List.of(503,200));
        try(var scheduler=new R2OpportunityTaskScheduler(registry,api,clock)){
            scheduler.poll(binding,INITIAL);clock.advance(600);scheduler.poll(binding,INITIAL);assertEquals(List.of(item,item),api.attempts);assertEquals(1,api.cursors.size());
            scheduler.poll(binding,INITIAL);clock.advance(1);scheduler.poll(binding,INITIAL);assertEquals(Arrays.asList(null,"expired",null),api.cursors);
        }
    }
    @Test void authorization_failure_isolated_by_kind_and_candidate_denial_rechecks_discovery(){
        var api=new Gateway();api.pages.add(new InternalApiClient.OpportunityPage(403,List.of(),null));api.pages.add(page(List.of(),null));api.pages.add(page(List.of(candidate(1)),null));api.pages.add(page(List.of(candidate(2)),null));api.responses.addAll(List.of(403,200));
        try(var scheduler=new R2OpportunityTaskScheduler(registry,api,clock)){
            scheduler.poll(binding,INITIAL);assertEquals(R2OpportunityTaskScheduler.Status.AUTHORIZATION_REQUIRED,scheduler.snapshot(binding,INITIAL).status());scheduler.poll(binding,DUE);assertEquals(R2OpportunityTaskScheduler.Status.READY,scheduler.snapshot(binding,DUE).status());
            clock.advance(1);scheduler.poll(binding,INITIAL);clock.advance(1);assertEquals(1,scheduler.poll(binding,INITIAL));assertEquals(2,api.attempts.size());assertNotEquals(api.attempts.get(0),api.attempts.get(1));
        }
    }
    @Test void monotonic_budget_and_shutdown_prevent_additional_requests(){
        var ticks=new AtomicLong();var api=new Gateway(){public InternalApiClient.Result execute(R1WorkerTenantBindings.Binding b,InternalApiClient.OpportunityCandidate c){ticks.addAndGet(6_000_000_000L);return super.execute(b,c);}};
        api.pages.add(page(List.of(candidate(1),candidate(2)),null));var scheduler=new R2OpportunityTaskScheduler(registry,api,clock,ticks::get);
        assertEquals(1,scheduler.poll(binding,INITIAL));assertEquals(1,scheduler.snapshot(binding,INITIAL).pending());scheduler.close();assertEquals(0,scheduler.poll(binding,INITIAL));assertEquals(1,api.attempts.size());assertEquals(R2OpportunityTaskScheduler.Status.CLOSED,scheduler.snapshot(binding,INITIAL).status());
    }
    @Test void uncertain_command_survives_temporary_revocation_and_empty_discovery(){
        var api=new Gateway();var item=candidate(1);api.pages.add(page(List.of(item),null));api.responses.addAll(List.of(408,403,200));
        try(var scheduler=new R2OpportunityTaskScheduler(registry,api,clock)){
            scheduler.poll(binding,INITIAL);clock.advance(1);scheduler.poll(binding,INITIAL);
            assertEquals(1,scheduler.snapshot(binding,INITIAL).pending());clock.advance(5);
            assertEquals(1,scheduler.poll(binding,INITIAL));assertEquals(List.of(item,item,item),api.attempts);
        }
    }
    @Test void persistent_object_denial_does_not_discard_other_candidates(){
        var api=new Gateway();var denied=candidate(1);var allowed=candidate(2);
        api.pages.add(page(List.of(denied,allowed),null));api.pages.add(page(List.of(denied,allowed),null));api.responses.addAll(List.of(403,200));
        try(var scheduler=new R2OpportunityTaskScheduler(registry,api,clock)){
            scheduler.poll(binding,INITIAL);clock.advance(1);assertEquals(1,scheduler.poll(binding,INITIAL));
            assertEquals(List.of(denied,allowed),api.attempts);assertEquals(1,scheduler.snapshot(binding,INITIAL).rejected());
        }
    }
    @Test void concurrent_poll_and_close_do_not_dispatch_the_same_page_twice()throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var api=new Gateway(){public InternalApiClient.Result execute(R1WorkerTenantBindings.Binding b,InternalApiClient.OpportunityCandidate c){entered.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();}return super.execute(b,c);}};
        api.pages.add(page(List.of(candidate(1),candidate(2)),null));try(var scheduler=new R2OpportunityTaskScheduler(registry,api,clock);var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var first=executor.submit(()->scheduler.poll(binding,INITIAL));assertTrue(entered.await(5,TimeUnit.SECONDS));assertEquals(0,scheduler.poll(binding,INITIAL));scheduler.close();release.countDown();first.get(5,TimeUnit.SECONDS);assertEquals(1,api.attempts.size());
        }
    }
    @Test void explicit_owner_exception_loop_requires_storage_and_does_not_enable_initial_or_due() {
        var api=new Gateway();
        var kinds=Set.of(InternalApiClient.OpportunityKind.OWNER_EXCEPTION);
        assertThrows(NullPointerException.class,()->new R2OpportunityTaskScheduler(registry,api,clock,null,kinds));
        var saved=new java.util.concurrent.atomic.AtomicReference<byte[]>();
        var opened=new ArrayList<io.github.windyzhu3.ontologylaw.execution.R2OpportunityCheckpointPort.Key>();
        io.github.windyzhu3.ontologylaw.execution.R2OpportunityCheckpointPort storage=key->{
            opened.add(key);
            return new io.github.windyzhu3.ontologylaw.execution.R2OpportunityCheckpointPort.Session(){
                public byte[] load(){return saved.get();}
                public void save(byte[] body){saved.set(body.clone());}
                public void close(){}
            };
        };
        try(var scheduler=new R2OpportunityTaskScheduler(registry,api,clock,storage,kinds)){
            assertFalse(scheduler.healthy(binding,InternalApiClient.OpportunityKind.OWNER_EXCEPTION));
            assertThrows(IllegalArgumentException.class,()->scheduler.poll(binding,INITIAL));
            assertThrows(IllegalArgumentException.class,()->scheduler.poll(binding,DUE));
            scheduler.poll(binding,InternalApiClient.OpportunityKind.OWNER_EXCEPTION);
            assertTrue(scheduler.healthy(binding,InternalApiClient.OpportunityKind.OWNER_EXCEPTION));
            assertEquals(1,opened.size());assertEquals("OWNER_EXCEPTION",opened.getFirst().kind());assertNotNull(saved.get());
        }
    }
    @Test void selected_owner_exception_loop_starts_and_closes_without_enabling_other_scans() throws Exception {
        var entered=new CountDownLatch(1);var seen=new java.util.concurrent.CopyOnWriteArrayList<InternalApiClient.OpportunityKind>();
        var api=new Gateway(){public InternalApiClient.OpportunityPage discover(R1WorkerTenantBindings.Binding b,InternalApiClient.OpportunityKind kind,String cursor){seen.add(kind);entered.countDown();return page(List.of(),null);}};
        io.github.windyzhu3.ontologylaw.execution.R2OpportunityCheckpointPort storage=key->new io.github.windyzhu3.ontologylaw.execution.R2OpportunityCheckpointPort.Session(){public byte[] load(){return null;}public void save(byte[] body){}public void close(){}};
        var scheduler=new R2OpportunityTaskScheduler(registry,api,clock,storage,Set.of(InternalApiClient.OpportunityKind.OWNER_EXCEPTION));
        try{scheduler.start();assertTrue(entered.await(5,TimeUnit.SECONDS));}finally{scheduler.close();}
        assertEquals(Set.of(InternalApiClient.OpportunityKind.OWNER_EXCEPTION),Set.copyOf(seen));
        assertEquals(R2OpportunityTaskScheduler.Status.CLOSED,scheduler.snapshot(binding,InternalApiClient.OpportunityKind.OWNER_EXCEPTION).status());
        assertFalse(scheduler.healthy(binding,InternalApiClient.OpportunityKind.OWNER_EXCEPTION));
    }
    @Test void unavailable_checkpoint_blocks_owner_exception_http_and_health() {
        var api=new Gateway();
        io.github.windyzhu3.ontologylaw.execution.R2OpportunityCheckpointPort storage=key->{throw new java.sql.SQLException("unavailable");};
        try(var scheduler=new R2OpportunityTaskScheduler(registry,api,clock,storage,Set.of(InternalApiClient.OpportunityKind.OWNER_EXCEPTION))){
            scheduler.poll(binding,InternalApiClient.OpportunityKind.OWNER_EXCEPTION);
            assertTrue(api.cursors.isEmpty());assertTrue(api.attempts.isEmpty());
            assertEquals(R2OpportunityTaskScheduler.Status.STORAGE_UNAVAILABLE,scheduler.snapshot(binding,InternalApiClient.OpportunityKind.OWNER_EXCEPTION).status());
            assertFalse(scheduler.healthy(binding,InternalApiClient.OpportunityKind.OWNER_EXCEPTION));
        }
    }    @Test void losing_checkpoint_session_does_not_keep_prior_ready_health() {
        var available=new java.util.concurrent.atomic.AtomicBoolean(true);var api=new Gateway();
        io.github.windyzhu3.ontologylaw.execution.R2OpportunityCheckpointPort storage=key->available.get()?new io.github.windyzhu3.ontologylaw.execution.R2OpportunityCheckpointPort.Session(){public byte[] load(){return null;}public void save(byte[] body){}public void close(){}}:null;
        try(var scheduler=new R2OpportunityTaskScheduler(registry,api,clock,storage,Set.of(InternalApiClient.OpportunityKind.OWNER_EXCEPTION))){
            scheduler.poll(binding,InternalApiClient.OpportunityKind.OWNER_EXCEPTION);assertTrue(scheduler.healthy(binding,InternalApiClient.OpportunityKind.OWNER_EXCEPTION));
            available.set(false);scheduler.poll(binding,InternalApiClient.OpportunityKind.OWNER_EXCEPTION);
            assertFalse(scheduler.healthy(binding,InternalApiClient.OpportunityKind.OWNER_EXCEPTION));assertEquals(1,api.cursors.size());
        }
    }}
