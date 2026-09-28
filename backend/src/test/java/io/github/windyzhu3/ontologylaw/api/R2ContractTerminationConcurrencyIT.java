package io.github.windyzhu3.ontologylaw.api;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.contract.ContractWorkflowService;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Observe an actual root lock wait before committing either competing business result. */
class R2ContractTerminationConcurrencyIT extends R2SalesTerminationIT {
 private static void await(CountDownLatch latch)throws SQLException{try{if(!latch.await(20,TimeUnit.SECONDS))throw new SQLException("Barrier timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new SQLException(e);}}
 private static int pid(Connection c)throws SQLException{try(var p=c.prepareStatement("select pg_backend_pid()");var r=p.executeQuery()){r.next();return r.getInt(1);}}
 private void blocked(int pid)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<end){if(!"0".equals(scalar("select cardinality(pg_blocking_pids(?))",pid)))return;Thread.sleep(20);}fail("No observed database lock wait");}
 private void race(boolean terminationFirst,boolean signature)throws Exception {
  if(signature){start();arrange();submit();}else unsignedStage("AWAIT_APPROVAL");
  var end=dispositionPayload(Map.of());var business=signature?signaturePayload(verification("VERIFIED")):payload(context(),Map.of("decision","APPROVED","reason","仅批准准确本版"));
  String termination=signature?"REQUEST_CONTRACT_TERMINATION_REVIEW":"END_CONTRACT_NEGOTIATION",normal=signature?"RECORD_CONTRACT_SIGNATURE_VERIFICATION":"RECORD_CONTRACT_DECISION";
  String winner=terminationFirst?termination:normal,loser=terminationFirst?normal:termination;var first=terminationFirst?end:business;var second=terminationFirst?business:end;
  var held=new CountDownLatch(1);var release=new CountDownLatch(1);var started=new CountDownLatch(1);var waitingPid=new AtomicInteger();
  try(var pool=Executors.newFixedThreadPool(2)){
   var one=pool.submit(()->{try(var c=database.apiConnection()){return inTransaction(c,Capability.COMMAND,x->{var fact=service().execute(x,winner,actor(),first);held.countDown();await(release);return fact;});}});
   Future<String> two;
   try{await(held);two=pool.submit(()->{try(var c=database.apiConnection()){waitingPid.set(pid(c));started.countDown();return assertThrows(ContractWorkflowService.Blocked.class,()->inTransaction(c,Capability.COMMAND,x->service().execute(x,loser,actor(),second))).code();}});await(started);blocked(waitingPid.get());}finally{release.countDown();}
   one.get(20,TimeUnit.SECONDS);assertEquals("STALE_SUBJECT",two.get(20,TimeUnit.SECONDS));
  }
  assertEquals(terminationFirst?"1":"0",scalar("select count(*) from contract.negotiation_disposition where tenant_id=?",seed.tenant()));
  assertEquals(terminationFirst?"0":"1",scalar("select count(*) from contract."+(signature?"signature_verification":"revision_approval_decision")+" where tenant_id=?",seed.tenant()));
 }
 @Test void termination_race_unsigned_end_wins_without_late_approval()throws Exception{race(true,false);}
 @Test void termination_race_approval_wins_and_requires_new_end_basis()throws Exception{race(false,false);}
 @Test void termination_race_signed_request_wins_without_late_verification()throws Exception{race(true,true);}
 @Test void termination_race_verification_wins_and_requires_new_supervisor_request()throws Exception{race(false,true);}
}
