package io.github.windyzhu3.ontologylaw.worker;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.sql.SQLException;
import java.io.IOException;
import io.github.windyzhu3.ontologylaw.execution.R2OpportunityCheckpointPort;

/** Opt-in dispatcher with an optional durable technical checkpoint. Does not own the HTTP client. */
public final class R2OpportunityTaskScheduler implements AutoCloseable {
    public enum Status { NOT_STARTED, PROCESSING, READY, RETRY_WAIT, AUTHORIZATION_REQUIRED, DEGRADED, STORAGE_UNAVAILABLE, CLOSED }
    public record Snapshot(Status status, int pending, long acknowledged, long rejected, int lastHttpStatus) {}
    public interface Gateway {
        InternalApiClient.OpportunityPage discover(R1WorkerTenantBindings.Binding binding, InternalApiClient.OpportunityKind kind, String cursor);
        InternalApiClient.Result execute(R1WorkerTenantBindings.Binding binding, InternalApiClient.OpportunityCandidate candidate);
    }
    private record Key(R1WorkerTenantBindings.Binding binding, InternalApiClient.OpportunityKind kind) {}
    static final class State {
        final ReentrantLock lock = new ReentrantLock();
        String cursor;
        InternalApiClient.OpportunityPage page;
        int index, failures, lastStatus;
        long acknowledged, rejected;
        boolean blocked, active, uncertain, probe;
        Instant retryAt = Instant.MIN;
        volatile Snapshot snapshot = new Snapshot(Status.NOT_STARTED, 0, 0, 0, 0);
        void publish(Status status) { snapshot = new Snapshot(status, page == null ? 0 : page.candidates().size()-index, acknowledged, rejected, lastStatus); }
    }
    private static final long[] DELAYS = {1,5,30,120,600,1800,7200};
    private final Map<Key,State> states = new LinkedHashMap<>();
    private final Gateway gateway;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private final R2OpportunityCheckpointPort checkpoints;
    private volatile boolean closed;
    private ScheduledExecutorService executor;

    public R2OpportunityTaskScheduler(R1WorkerTenantBindings bindings, InternalApiClient client, Clock clock) {
        this(bindings, clientGateway(client), clock);
    }
    public R2OpportunityTaskScheduler(R1WorkerTenantBindings bindings, InternalApiClient client, Clock clock, R2OpportunityCheckpointPort checkpoints) {
        this(bindings, clientGateway(client), clock, checkpoints);
    }
    /** Explicit production scan set; persistent checkpoints are mandatory for this entry point. */
    public R2OpportunityTaskScheduler(R1WorkerTenantBindings bindings, InternalApiClient client, Clock clock,
                                     R2OpportunityCheckpointPort checkpoints, Set<InternalApiClient.OpportunityKind> kinds) {
        this(bindings,clientGateway(client),clock,System::nanoTime,Objects.requireNonNull(checkpoints),kinds);
    }
    R2OpportunityTaskScheduler(R1WorkerTenantBindings bindings, Gateway gateway, Clock clock,
                              R2OpportunityCheckpointPort checkpoints, Set<InternalApiClient.OpportunityKind> kinds) {
        this(bindings,gateway,clock,System::nanoTime,Objects.requireNonNull(checkpoints),kinds);
    }
    public boolean healthy(R1WorkerTenantBindings.Binding binding, InternalApiClient.OpportunityKind kind) {
        var status=snapshot(binding,kind).status();
        return status==Status.READY||status==Status.PROCESSING;
    }
    private static Gateway clientGateway(InternalApiClient client) {
        return new Gateway() {
            public InternalApiClient.OpportunityPage discover(R1WorkerTenantBindings.Binding b, InternalApiClient.OpportunityKind k, String c) { return client.opportunityCandidates(b,k,c); }
            public InternalApiClient.Result execute(R1WorkerTenantBindings.Binding b, InternalApiClient.OpportunityCandidate c) { return client.maintainOpportunity(b,c); }
        };
    }
    public R2OpportunityTaskScheduler(R1WorkerTenantBindings bindings, Gateway gateway, Clock clock) { this(bindings,gateway,clock,System::nanoTime); }
    R2OpportunityTaskScheduler(R1WorkerTenantBindings bindings, Gateway gateway, Clock clock, LongSupplier nanoTime) {
        this(bindings,gateway,clock,nanoTime,null);
    }
    public R2OpportunityTaskScheduler(R1WorkerTenantBindings bindings, Gateway gateway, Clock clock, R2OpportunityCheckpointPort checkpoints) {
        this(bindings,gateway,clock,System::nanoTime,Objects.requireNonNull(checkpoints));
    }
    private R2OpportunityTaskScheduler(R1WorkerTenantBindings bindings, Gateway gateway, Clock clock, LongSupplier nanoTime, R2OpportunityCheckpointPort checkpoints) {
        this(bindings,gateway,clock,nanoTime,checkpoints,EnumSet.allOf(InternalApiClient.OpportunityKind.class));
    }
    private R2OpportunityTaskScheduler(R1WorkerTenantBindings bindings, Gateway gateway, Clock clock, LongSupplier nanoTime, R2OpportunityCheckpointPort checkpoints, Set<InternalApiClient.OpportunityKind> kinds) {
        var enabled=Set.copyOf(Objects.requireNonNull(kinds));
        if(enabled.isEmpty())throw new IllegalArgumentException("R2_WORKER_SCAN_KINDS_REQUIRED" );
        this.gateway=Objects.requireNonNull(gateway); this.clock=Objects.requireNonNull(clock); this.nanoTime=Objects.requireNonNull(nanoTime);
        this.checkpoints=checkpoints;
        for(var b:bindings.bindings()) for(var k:enabled) states.put(new Key(b,k),new State());
    }
    private State state(R1WorkerTenantBindings.Binding b, InternalApiClient.OpportunityKind k) {
        var s=states.get(new Key(b,k)); if(s==null) throw new IllegalArgumentException("R2_WORKER_BINDING_UNKNOWN"); return s;
    }
    public Snapshot snapshot(R1WorkerTenantBindings.Binding b, InternalApiClient.OpportunityKind k) {
        var v=state(b,k).snapshot;
        return closed ? new Snapshot(Status.CLOSED,v.pending(),v.acknowledged(),v.rejected(),v.lastHttpStatus()) : v;
    }
    private void retry(State s, int status) {
        s.lastStatus=status;
        long delay=DELAYS[Math.min(s.failures,DELAYS.length-1)];
        // Temporary service failures must notice recovery promptly. Retrying a
        // pending command retains its exact key and durable page position.
        if(status==408||status>=500)delay=Math.min(delay,60);
        s.retryAt=clock.instant().plusSeconds(delay);
        s.failures=Math.min(s.failures+1,DELAYS.length);
        s.publish(status==401||status==403 ? Status.AUTHORIZATION_REQUIRED : Status.RETRY_WAIT);
    }
    /** Returns acknowledged receipts, including idempotent NO_CHANGE, not newly created tasks. */
    public int poll(R1WorkerTenantBindings.Binding binding, InternalApiClient.OpportunityKind kind) {
        var s=state(binding,kind);
        if(closed || !s.lock.tryLock()) return 0;
        try {
            if(checkpoints==null)return process(binding,kind,s,null);
            var key=new R2OpportunityCheckpointPort.Key(binding.tenantId(),binding.principalId(),binding.appointmentId(),kind.name());
            try(var session=checkpoints.tryOpen(key)) {
                if(closed)return 0;
                if(session==null){s.publish(Status.RETRY_WAIT);return 0;}
                R2OpportunityCheckpointCodec.restore(s,session.load(),kind);
                int acknowledged=process(binding,kind,s,session);
                session.save(R2OpportunityCheckpointCodec.encode(s,kind));
                return acknowledged;
            }
        } catch(SQLException|IOException failure) { s.publish(Status.STORAGE_UNAVAILABLE);return 0; }
        finally { s.lock.unlock(); }
    }
    private int process(R1WorkerTenantBindings.Binding binding, InternalApiClient.OpportunityKind kind, State s, R2OpportunityCheckpointPort.Session session)throws SQLException,IOException {
        int acknowledged=0;
        long started=nanoTime.getAsLong();
        try {
            // Bound older persisted service outages too; never discard a pending key.
            if((s.lastStatus==408||s.lastStatus>=500) && s.retryAt.isAfter(clock.instant().plusSeconds(60)))
                s.retryAt=clock.instant().plusSeconds(60);
            if(closed || clock.instant().isBefore(s.retryAt)) return 0;
            if(s.probe) {
                var authorization=gateway.discover(binding,kind,null);
                if(closed) return 0;
                if(authorization.status()!=200) { retry(s,authorization.status()); return 0; }
                s.probe=false;
            }
            if(s.page==null) {
                var page=gateway.discover(binding,kind,s.cursor);
                if(closed) return 0;
                if(page.status()!=200) {
                    if(page.status()==400 && s.cursor!=null) { s.cursor=null; s.active=false; s.failures=0; }
                    retry(s,page.status()); return 0;
                }
                if(page.candidates().size()>50 || page.candidates().stream().anyMatch(c->c.kind()!=kind)) { retry(s,503); return 0; }
                if(!s.active) { s.blocked=false; s.active=true; }
                if(page.diagnostics()>0)s.blocked=true;
                s.page=page; s.index=0; s.failures=0; s.publish(Status.PROCESSING);
            }
            for(int calls=0; s.index<s.page.candidates().size() && calls<10 && !closed && nanoTime.getAsLong()-started<5_000_000_000L; calls++) {
                boolean previouslyUncertain=s.uncertain;
                if(session!=null) {
                    s.uncertain=true;
                    session.save(R2OpportunityCheckpointCodec.encode(s,kind));
                }
                if(closed)return acknowledged;
                int status=gateway.execute(binding,s.page.candidates().get(s.index)).status();
                s.uncertain=previouslyUncertain;
                s.lastStatus=status;
                if(status==200) { s.index++; s.acknowledged++; acknowledged++; s.failures=0; s.uncertain=false; }
                else if(status==400||status==404||status==409||status==412||status==422) { s.index++; s.rejected++; s.blocked=true; s.uncertain=false; }
                else if(status==401||status==403) {
                    // Preserve uncertain delivery; otherwise advance past this denied object, not its siblings.
                    s.blocked=true;
                    if(!s.uncertain) { s.index++; s.rejected++; }
                    if(s.index==s.page.candidates().size()) { s.cursor=s.page.nextCursor(); s.page=null; s.index=0; }
                    s.probe=s.page!=null;
                    retry(s,status); return acknowledged;
                } else { s.uncertain=true; retry(s,status); return acknowledged; }
                s.publish(s.blocked ? Status.DEGRADED : Status.PROCESSING);
            }
            if(s.index==s.page.candidates().size()) {
                s.cursor=s.page.nextCursor(); s.page=null; s.index=0;
                if(s.cursor==null) {
                    s.active=false;
                    // A completed scan must yield to interactive work. In particular,
                    // repeated owner-validation NO_CHANGE commands still take tenant locks.
                    s.retryAt=clock.instant().plusSeconds(s.blocked||kind==InternalApiClient.OpportunityKind.OWNER_EXCEPTION?60:5);
                    s.publish(s.blocked ? Status.DEGRADED : Status.READY);
                } else s.publish(s.blocked ? Status.DEGRADED : Status.PROCESSING);
            }
            return acknowledged;
        } catch(RuntimeException failure) { if(s.page!=null && s.index<s.page.candidates().size()) s.uncertain=true; retry(s,503); return acknowledged; }
    }
    public synchronized void start() {
        if(closed || executor!=null) throw new IllegalStateException("R2_WORKER_ALREADY_STARTED_OR_CLOSED");
        // Background discovery shares the API pool with login, user actions and R1 recovery.
        // Keep it bounded independently of the number of enabled business scan kinds.
        executor=Executors.newScheduledThreadPool(Math.min(2,states.size()));
        for(var key:states.keySet()) executor.scheduleWithFixedDelay(()->poll(key.binding(),key.kind()),0,1,TimeUnit.SECONDS);
    }
    @Override public synchronized void close() { closed=true; if(executor!=null) executor.shutdownNow(); }
}
