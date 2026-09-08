package io.github.windyzhu3.ontologylaw.worker;

import java.util.concurrent.*;
import org.springframework.context.*;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.availability.*;

/** Three independent required-loop states, plus the live read-only deployment/capability gate. */
public final class WorkerRuntimeHealth implements SmartLifecycle {
    public record Snapshot(boolean database,boolean projection,boolean contactRecovery,boolean routingRecovery,int exhausted) {
        public boolean healthy(){return database&&projection&&contactRecovery&&routingRecovery&&exhausted==0;}
    }
    private final R1WorkerDeployment deployment;private final ApplicationContext context;
    private volatile boolean running;private Boolean published;private ScheduledExecutorService scheduler;
    WorkerRuntimeHealth(R1WorkerDeployment deployment,ApplicationContext context){this.deployment=deployment;this.context=context;}
    public Snapshot snapshot() {
        if(!running||!deployment.database.healthy())return new Snapshot(false,false,false,false,0);
        boolean projection=true,contact=true,routing=true;int exhausted=0;
        try{for(var binding:deployment.registry.bindings()) {
            projection&=deployment.projection.healthy(binding);contact&=deployment.due.healthy(binding,InternalApiClient.RecoveryType.CONTACT_TASK);routing&=deployment.due.healthy(binding,InternalApiClient.RecoveryType.ROUTING_REVIEW_TASK);
            exhausted=Math.min(Integer.MAX_VALUE-100,exhausted)+deployment.outbox.counts(binding.tenantId(),100).exhausted();
        }}catch(Exception unavailable){return new Snapshot(false,false,false,false,0);}
        return new Snapshot(true,projection,contact,routing,exhausted);
    }
    public boolean healthy(){return snapshot().healthy();}
    public synchronized void start(){if(running)return;isolation();if(!deployment.database.healthy())throw new IllegalStateException("R1_WORKER_GATE_UNAVAILABLE");running=true;deployment.projection.start();deployment.due.start();scheduler=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(false).name("r1-worker-health").factory());scheduler.scheduleWithFixedDelay(this::refresh,0,1,TimeUnit.SECONDS);}
    private void isolation(){
        if(context.getClass().getName().contains("WebServerApplicationContext"))throw new IllegalStateException("R1_WORKER_ASSEMBLY_INVALID");
        for(String name:context.getBeanDefinitionNames()){var type=context.getType(name,false);if(type==null)continue;String location=type.getPackageName();for(String forbidden:java.util.List.of("api","identity","audit","lead","responsibility","query","opportunity","party","evidence"))if(location.startsWith("io.github.windyzhu3.ontologylaw."+forbidden))throw new IllegalStateException("R1_WORKER_ASSEMBLY_INVALID");}
        org.slf4j.LoggerFactory.getLogger(WorkerRuntimeHealth.class).info("R1_WORKER_ASSEMBLY_ISOLATED");
    }
    @EventListener(ApplicationReadyEvent.class) public void applicationReady(){refresh();}
    private synchronized void refresh(){var health=snapshot();AvailabilityChangeEvent.publish(context,health.healthy()?ReadinessState.ACCEPTING_TRAFFIC:ReadinessState.REFUSING_TRAFFIC);if(published==null||published!=health.healthy()){published=health.healthy();org.slf4j.LoggerFactory.getLogger(WorkerRuntimeHealth.class).info(health.healthy()?"R1_WORKER_READY":"R1_WORKER_UNAVAILABLE");}}
    public synchronized void stop(){running=false;if(scheduler!=null)scheduler.shutdownNow();deployment.projection.close();deployment.due.close();AvailabilityChangeEvent.publish(context,ReadinessState.REFUSING_TRAFFIC);}
    public boolean isRunning(){return running;}
}
