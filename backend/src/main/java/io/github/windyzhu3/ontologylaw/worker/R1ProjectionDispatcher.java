package io.github.windyzhu3.ontologylaw.worker;
import io.github.windyzhu3.ontologylaw.execution.R1ProjectionOutboxPort;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
public final class R1ProjectionDispatcher implements AutoCloseable {
    private static final class State {final ReentrantLock check=new ReentrantLock();long generation;long reapedExhausted;boolean frozen;int failures;java.time.Instant retryAt=java.time.Instant.MIN;}
    private final Map<R1WorkerTenantBindings.Binding,State> states;private final InternalApiClient api;private final R1ProjectionOutboxPort outbox;private final String workerId;private final Clock clock;
    private final Semaphore permits=new Semaphore(4);private final ExecutorService requests=Executors.newVirtualThreadPerTaskExecutor();private ScheduledExecutorService scheduler;private volatile boolean closed;
    public R1ProjectionDispatcher(R1WorkerTenantBindings registry,InternalApiClient api,R1ProjectionOutboxPort outbox,String workerId,Clock clock){
        this.api=Objects.requireNonNull(api);this.outbox=Objects.requireNonNull(outbox);this.clock=Objects.requireNonNull(clock);if(workerId==null||!workerId.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}"))throw new IllegalArgumentException("R1_WORKER_ID_INVALID");this.workerId=workerId;
        var states=new HashMap<R1WorkerTenantBindings.Binding,State>();registry.bindings().forEach(b->states.put(b,new State()));this.states=Map.copyOf(states);
    }
    /** One synchronous readiness decision and one immediate claim, never a preclaimed work queue. */
    public int poll(R1WorkerTenantBindings.Binding binding){
        var state=state(binding);if(closed||!state.check.tryLock())return 0;int reserved=0;
        try{
            // A frozen binding still reaps leases using the same immutable attempt budget.
            var reaped=outbox.reap(binding.tenantId(),100);
            if(reaped.exhausted()>0){
                synchronized(state){state.reapedExhausted=Math.min(state.reapedExhausted,Long.MAX_VALUE-reaped.exhausted())+reaped.exhausted();}
                warn("R1_PROJECTION_EXHAUSTED");
            }
            if(clock.instant().isBefore(state.retryAt))return 0;
            while(reserved<4&&permits.tryAcquire())reserved++;if(reserved==0)return 0;
            long generation;synchronized(state){generation=state.generation;}
            var result=api.readiness(binding);
            if(result.status()==401||result.status()==403){freeze(state);return 0;}
            if(result.status()!=204){backoff(state);return 0;}if(closed)return 0;
            List<R1ProjectionOutboxPort.Claim> claims;
            synchronized(state){
                // A consume failure may arrive while this HTTP request is in flight.
                if(state.generation!=generation)return 0;state.frozen=false;state.failures=0;state.retryAt=java.time.Instant.MIN;
                claims=outbox.claim(binding.tenantId(),workerId,reserved);
            }
            for(var claim:claims){
                if(closed)break;
                // Virtual threads begin bounded HTTP work immediately; permits are already owned.
                requests.submit(()->{try{deliver(binding,state,claim);}finally{permits.release();}});reserved--;
            }
            return claims.size();
        }catch(Exception failure){backoff(state);warn("R1_WORKER_UNAVAILABLE");return 0;}
        finally{if(reserved>0)permits.release(reserved);state.check.unlock();}
    }
    private void deliver(R1WorkerTenantBindings.Binding binding,State state,R1ProjectionOutboxPort.Claim claim){
        if(closed||!clock.instant().isBefore(claim.leaseUntil()))return;
        var result=api.consume(binding,claim);if(closed)return;
        try{switch(result.status()){
            case 204 -> outbox.ack(claim);
            case 409 -> {} // Old results never mutate the current claim.
            case 401,403 -> freeze(state);
            case 400 -> {if(outbox.exhaust(claim,"VALIDATION_FAILED"))warn("R1_PROJECTION_EXHAUSTED");}
            case 404 -> {if(outbox.exhaust(claim,"NOT_FOUND"))warn("R1_PROJECTION_EXHAUSTED");}
            case 422 -> {if(outbox.exhaust(claim,"PROJECTION_EVENT_INVALID"))warn("R1_PROJECTION_EXHAUSTED");}
            default -> {boolean changed=outbox.retry(claim,switch(result.status()){case 408 -> "HTTP_TIMEOUT";case 429 -> "RATE_LIMITED";case 503 -> "SERVICE_UNAVAILABLE";default -> "INTERNAL_ERROR";});if(changed&&claim.attempt()>=8)warn("R1_PROJECTION_EXHAUSTED");}
        }}catch(Exception failure){warn("R1_WORKER_UNAVAILABLE");}
    }
    private static void freeze(State state){synchronized(state){state.generation++;state.frozen=true;}warn("R1_PROJECTION_AUTHORIZATION_FROZEN");}
    private void backoff(State state){long[] delays={1,5,30,120,600,1800,7200};state.failures=Math.min(7,state.failures+1);state.retryAt=clock.instant().plusSeconds(delays[state.failures-1]);}
    private State state(R1WorkerTenantBindings.Binding binding){var state=states.get(binding);if(state==null)throw new IllegalArgumentException("R1_WORKER_BINDING_INVALID");return state;}
    public boolean frozen(R1WorkerTenantBindings.Binding binding){var state=state(binding);synchronized(state){return state.frozen;}}
    public int availablePermits(){return permits.availablePermits();}
    /** Process-local committed lease-exhaustion metric, not backlog, durable state, or authorization. */
    public long reapedExhausted(R1WorkerTenantBindings.Binding binding){var state=state(binding);synchronized(state){return state.reapedExhausted;}}
    public synchronized void start(){if(closed||scheduler!=null)throw new IllegalStateException("R1_WORKER_ALREADY_STARTED");scheduler=Executors.newScheduledThreadPool(states.size(),Thread.ofPlatform().daemon(true).factory());for(var binding:states.keySet())scheduler.scheduleWithFixedDelay(()->poll(binding),0,1,TimeUnit.SECONDS);}
    private static void warn(String code){org.slf4j.LoggerFactory.getLogger(R1ProjectionDispatcher.class).warn(code);}
    public synchronized void close(){closed=true;if(scheduler!=null)scheduler.shutdownNow();requests.shutdownNow();}
}
