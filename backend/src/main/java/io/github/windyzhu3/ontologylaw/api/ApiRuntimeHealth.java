package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.execution.RuntimeDatabase;
import java.util.concurrent.*;
import org.springframework.context.*;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.availability.*;

/** Typed role health; no public HTTP endpoint or business authorization cache. */
public final class ApiRuntimeHealth implements SmartLifecycle {
    private final RuntimeDatabase database;private final ApplicationContext context;
    private volatile boolean running;private Boolean published;private ScheduledExecutorService scheduler;
    public ApiRuntimeHealth(RuntimeDatabase database,ApplicationContext context){this.database=database;this.context=context;}
    public boolean healthy(){return running&&database.healthy();}
    public synchronized void start(){if(running)return;isolation();if(!database.healthy())throw new IllegalStateException("R1_API_GATE_UNAVAILABLE");running=true;scheduler=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("r1-api-health").factory());scheduler.scheduleWithFixedDelay(this::refresh,0,1,TimeUnit.SECONDS);}
    private void isolation(){
        if(!context.getClass().getName().contains("WebServerApplicationContext"))throw new IllegalStateException("R1_API_ASSEMBLY_INVALID");
        for(String name:context.getBeanDefinitionNames()){var type=context.getType(name,false);if(type!=null&&type.getPackageName().startsWith("io.github.windyzhu3.ontologylaw.worker"))throw new IllegalStateException("R1_API_ASSEMBLY_INVALID");}
        org.slf4j.LoggerFactory.getLogger(ApiRuntimeHealth.class).info("R1_API_ASSEMBLY_ISOLATED");
    }
    @EventListener(ApplicationReadyEvent.class) public void applicationReady(){refresh();}
    private synchronized void refresh(){boolean healthy=healthy();AvailabilityChangeEvent.publish(context,healthy?ReadinessState.ACCEPTING_TRAFFIC:ReadinessState.REFUSING_TRAFFIC);if(published==null||published!=healthy){published=healthy;org.slf4j.LoggerFactory.getLogger(ApiRuntimeHealth.class).info(healthy?"R1_API_READY":"R1_API_UNAVAILABLE");}}
    public synchronized void stop(){running=false;if(scheduler!=null)scheduler.shutdownNow();AvailabilityChangeEvent.publish(context,ReadinessState.REFUSING_TRAFFIC);}
    public boolean isRunning(){return running;}
}
