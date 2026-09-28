package io.github.windyzhu3.ontologylaw.api;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.opportunity.QuoteWorkflowService;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Observe an actual root lock wait before committing either competing business result. */
class R2QuoteTerminationConcurrencyIT extends R2QuoteTerminationIT {
 private static void await(CountDownLatch latch)throws SQLException{try{if(!latch.await(20,TimeUnit.SECONDS))throw new SQLException("Barrier timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new SQLException(e);}}
 private static int pid(Connection c)throws SQLException{try(var p=c.prepareStatement("select pg_backend_pid()");var r=p.executeQuery()){r.next();return r.getInt(1);}}
 private void blocked(int pid)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<end){if(!"0".equals(scalar("select cardinality(pg_blocking_pids(?))",pid)))return;Thread.sleep(20);}fail("No observed database lock wait");}
 private void race(boolean terminationFirst)throws Exception {
  stage("AWAIT_APPROVAL");var end=quotePayload(reason());var approve=quotePayload(Map.of("decision","APPROVED","reason","仅批准准确本版"));
  String winner=terminationFirst?"END_QUOTE_NEGOTIATION":"RECORD_QUOTE_DECISION",loser=terminationFirst?"RECORD_QUOTE_DECISION":"END_QUOTE_NEGOTIATION";var first=terminationFirst?end:approve;var second=terminationFirst?approve:end;
  var held=new CountDownLatch(1);var release=new CountDownLatch(1);var started=new CountDownLatch(1);var waitingPid=new AtomicInteger();
  try(var pool=Executors.newFixedThreadPool(2)){
   var one=pool.submit(()->{try(var c=database.apiConnection()){return inTransaction(c,Capability.COMMAND,x->{var fact=quotes().execute(x,winner,seed.request().actor(),first);held.countDown();await(release);return fact;});}});
   Future<String> two;
   try{await(held);two=pool.submit(()->{try(var c=database.apiConnection()){waitingPid.set(pid(c));started.countDown();return assertThrows(QuoteWorkflowService.Blocked.class,()->inTransaction(c,Capability.COMMAND,x->quotes().execute(x,loser,seed.request().actor(),second))).code();}});await(started);blocked(waitingPid.get());}finally{release.countDown();}
   one.get(20,TimeUnit.SECONDS);assertEquals("STALE_SUBJECT",two.get(20,TimeUnit.SECONDS));
  }
  assertEquals(terminationFirst?"1":"0",scalar("select count(*) from opportunity.quote_termination where tenant_id=?",seed.tenant()));assertEquals(terminationFirst?"0":"1",scalar("select count(*) from opportunity.quote_approval_decision where tenant_id=?",seed.tenant()));assertEquals(terminationFirst?"0":"1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and state in ('OPEN','WAITING')",seed.tenant(),opportunity.id()));
 }
 @Test void termination_race_close_wins_without_late_approval()throws Exception{race(true);}
 @Test void termination_race_approval_wins_without_closing_an_unreviewed_state()throws Exception{race(false);}
}
