package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.execution.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;

/** Two real transactions, with an observed database lock wait before releasing the winner. */
class R2SalesChainConcurrencyIT extends R2ContractWorkflowPersistenceIT {
    private static void await(CountDownLatch latch)throws SQLException {
        try {if(!latch.await(20,TimeUnit.SECONDS))throw new SQLException("Test barrier timed out");}
        catch(InterruptedException error){Thread.currentThread().interrupt();throw new SQLException(error);}
    }
    private static int pid(Connection c)throws SQLException {
        try(var p=c.prepareStatement("select pg_backend_pid()");var r=p.executeQuery()){r.next();return r.getInt(1);}
    }
    private void blocked(int pid)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<end){
            if(!"0".equals(scalar("select cardinality(pg_blocking_pids(?))",pid)))return;
            Thread.sleep(20);
        }
        fail("Expected a database lock wait before winner commits");
    }
    @Test void chain_race_contract_commit_prevents_waiting_initial_activation()throws Exception {
        initialize();var worker=service("OPPORTUNITY_TASK_ACTIVATE");
        var runtime=R2OpportunityCommandRuntime.fromSourcePolicy(policies,protection,cipher,null,"F02_RACE_IT");
        var activation=new CommandEnvelope(CommandEnvelope.Type.ACTIVATE_INITIAL_OPPORTUNITY_TASK,UUID.randomUUID(),UUID.randomUUID(),worker,
                Map.of("opportunityId",opportunity.id().toString(),"expectedOpportunityRevision",opportunity.revision()));
        var request=payload(context(),Map.of("commercial",terms.canonical(),"reason","合成并发验收"));
        var held=new CountDownLatch(1);var release=new CountDownLatch(1);var started=new CountDownLatch(1);var waitingPid=new AtomicInteger();
        try(var workers=Executors.newFixedThreadPool(2)){
            var contract=workers.submit(()->{try(var c=database.apiConnection()){return inTransaction(c,Capability.COMMAND,x->{
                var fact=service().execute(x,"REQUEST_CONTRACT_PREPARATION",actor(),request);held.countDown();await(release);return fact;
            });}});
            Future<String> following;
            try {
                await(held);
                following=workers.submit(()->{try(var c=database.apiConnection()){
                    waitingPid.set(pid(c));started.countDown();
                    return assertThrows(CommandHandler.Rejected.class,()->runtime.execute(c,activation)).code();
                }});
                await(started);blocked(waitingPid.get());
            } finally {release.countDown();}
            contract.get(20,TimeUnit.SECONDS);assertEquals("STALE_SUBJECT",following.get(20,TimeUnit.SECONDS));
        }
        assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and business_purpose_code='PROGRESS_OPPORTUNITY'",seed.tenant(),opportunity.id()));
        assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and business_purpose_code='DECIDE_CONTRACT_PREPARATION' and state='OPEN'",seed.tenant(),opportunity.id()));
    }
    @Test void chain_race_initial_commit_is_taken_over_and_original_receipt_replays()throws Exception {
        initialize();var worker=service("OPPORTUNITY_TASK_ACTIVATE");
        var runtime=R2OpportunityCommandRuntime.fromSourcePolicy(policies,protection,cipher,null,"F02_RACE_IT");
        var activation=new CommandEnvelope(CommandEnvelope.Type.ACTIVATE_INITIAL_OPPORTUNITY_TASK,UUID.randomUUID(),UUID.randomUUID(),worker,
                Map.of("opportunityId",opportunity.id().toString(),"expectedOpportunityRevision",opportunity.revision()));
        var request=payload(context(),Map.of("commercial",terms.canonical(),"reason","合成并发验收"));
        var held=new CountDownLatch(1);var release=new CountDownLatch(1);var started=new CountDownLatch(1);var waitingPid=new AtomicInteger();
        CommandResult receipt;
        try(var workers=Executors.newFixedThreadPool(2)){
            var activationResult=workers.submit(()->{try(var c=database.apiConnection()){return runtime.executeProjected(c,activation,(x,result)->{
                assertEquals(CommandOutcome.Status.SUCCEEDED,assertInstanceOf(CommandOutcome.class,result).status());held.countDown();await(release);return result;
            });}});
            Future<?> contract;
            try {
                await(held);
                contract=workers.submit(()->{try(var c=database.apiConnection()){
                    waitingPid.set(pid(c));started.countDown();
                    return inTransaction(c,Capability.COMMAND,x->service().execute(x,"REQUEST_CONTRACT_PREPARATION",actor(),request));
                }});
                await(started);blocked(waitingPid.get());
            } finally {release.countDown();}
            receipt=activationResult.get(20,TimeUnit.SECONDS);contract.get(20,TimeUnit.SECONDS);
        }
        assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and business_purpose_code='PROGRESS_OPPORTUNITY' and state in ('OPEN','WAITING')",seed.tenant(),opportunity.id()));
        assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and business_purpose_code='DECIDE_CONTRACT_PREPARATION' and state='OPEN'",seed.tenant(),opportunity.id()));
        try(var c=database.apiConnection()){assertEquals(receipt,runtime.execute(c,activation));}
    }
}
