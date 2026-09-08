package io.github.windyzhu3.ontologylaw.worker;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
public final class DueTaskScheduler implements AutoCloseable {
    private record Key(R1WorkerTenantBindings.Binding binding,InternalApiClient.RecoveryType type){}
    private static final class State {final ReentrantLock lock=new ReentrantLock();String cursor;int failures;Instant retryAt=Instant.MIN;volatile boolean frozen,healthy;}
    private final Map<Key,State> states;private final InternalApiClient api;private final Clock clock;private volatile boolean closed;private ScheduledExecutorService scheduler;
    public DueTaskScheduler(R1WorkerTenantBindings registry,InternalApiClient api,Clock clock){this.api=Objects.requireNonNull(api);this.clock=Objects.requireNonNull(clock);var states=new HashMap<Key,State>();for(var binding:registry.bindings())for(var type:InternalApiClient.RecoveryType.values())states.put(new Key(binding,type),new State());this.states=Map.copyOf(states);}
    /** One bounded page per invocation. Failed work is rediscovered, never stored as an in-memory queue. */
    public int poll(R1WorkerTenantBindings.Binding binding,InternalApiClient.RecoveryType type){
        var state=states.get(new Key(binding,type));if(state==null)throw new IllegalArgumentException("R1_WORKER_BINDING_INVALID");if(closed||!state.lock.tryLock())return 0;
        try{if(clock.instant().isBefore(state.retryAt))return 0;var page=api.due(binding,type,state.cursor);
            if(page.status()!=200){failure(state,page.status());return 0;}state.frozen=false;int recovered=0;
            for(var candidate:page.candidates()){
                if(closed)return recovered;var response=api.recover(binding,candidate);if(response.status()==200)recovered++;
                else if(response.status()!=400&&response.status()!=409){failure(state,response.status());return recovered;}
                else warn("R1_DUE_CANDIDATE_REJECTED");
            }
            state.cursor=page.nextCursor();state.failures=0;state.retryAt=Instant.MIN;state.healthy=true;return recovered;
        }catch(RuntimeException failure){failure(state,503);return 0;}finally{state.lock.unlock();}
    }
    private void failure(State state,int status){state.healthy=false;state.cursor=null;if(status==400){state.retryAt=clock.instant().plusSeconds(1);return;}if(status==401||status==403){state.frozen=true;warn("R1_DUE_AUTHORIZATION_FROZEN");}else warn("R1_DUE_API_UNAVAILABLE");long[] delays={1,5,30,120,600,1800,7200};state.failures=Math.min(7,state.failures+1);state.retryAt=clock.instant().plusSeconds(delays[state.failures-1]);}
    public boolean frozen(R1WorkerTenantBindings.Binding binding){return states.entrySet().stream().filter(e->e.getKey().binding().equals(binding)).anyMatch(e->e.getValue().frozen);}
    public boolean healthy(R1WorkerTenantBindings.Binding binding,InternalApiClient.RecoveryType type){var state=states.get(new Key(binding,type));if(state==null)throw new IllegalArgumentException("R1_WORKER_BINDING_INVALID");return !closed&&state.healthy&&!state.frozen;}
    public boolean frozen(R1WorkerTenantBindings.Binding binding,InternalApiClient.RecoveryType type){var state=states.get(new Key(binding,type));if(state==null)throw new IllegalArgumentException("R1_WORKER_BINDING_INVALID");return state.frozen;}
    public synchronized void start(){if(closed||scheduler!=null)throw new IllegalStateException("R1_WORKER_ALREADY_STARTED");scheduler=Executors.newScheduledThreadPool(states.size(),Thread.ofPlatform().daemon(true).name("r1-due-",0).factory());for(var key:states.keySet())scheduler.scheduleWithFixedDelay(()->poll(key.binding(),key.type()),0,1,TimeUnit.SECONDS);}
    private static void warn(String code){org.slf4j.LoggerFactory.getLogger(DueTaskScheduler.class).warn(code);}
    public synchronized void close(){closed=true;if(scheduler!=null)scheduler.shutdownNow();}
}
