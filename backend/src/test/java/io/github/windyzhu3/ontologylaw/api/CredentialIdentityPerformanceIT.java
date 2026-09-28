package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.testing.PostgresIntegrationTest;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CredentialIdentityPerformanceIT extends PostgresIntegrationTest {
 @Test void credential_mapping_and_selection_do_not_wait_for_unrelated_business_writes()throws Exception {
  var seed=AuthorizationServiceIT.seed(database);var runtime=new CredentialIdentityRuntime();
  try(var writer=database.apiConnection();var reader=database.apiConnection();var executor=Executors.newVirtualThreadPerTaskExecutor()) {
   writer.setAutoCommit(false);setLocalRole(writer,Capability.COMMAND);R1BusinessFence.databaseBacked().exclusive(writer,seed.tenant());
   var reading=executor.submit(()->{var human=runtime.human(reader,seed.tenant(),"FIXTURE",new byte[32]);return runtime.selectHuman(reader,human,seed.appointment());});
   try {assertEquals(seed.request().actor(),reading.get(2,TimeUnit.SECONDS));}
   finally {writer.rollback();writer.setAutoCommit(true);reading.get(5,TimeUnit.SECONDS);}
  }
 }
 @Test void credential_read_waits_for_identity_mutation_and_observes_suspension()throws Exception {
  var seed=AuthorizationServiceIT.seed(database);var runtime=new CredentialIdentityRuntime();
  try(var writer=database.apiConnection();var reader=database.apiConnection();var observer=database.adminConnection();var executor=Executors.newVirtualThreadPerTaskExecutor()) {
   int pid;try(var p=reader.createStatement();var r=p.executeQuery("select pg_backend_pid()")){r.next();pid=r.getInt(1);}
   writer.setAutoCommit(false);setLocalRole(writer,Capability.COMMAND);AuthorizationService.databaseBacked().lockForMutation(writer,seed.tenant());
   try(var p=writer.prepareStatement("update identity.principal set state='SUSPENDED',revision=revision+1 where tenant_id=? and principal_id=?")){p.setObject(1,seed.tenant());p.setObject(2,seed.principal());p.executeUpdate();}
   var reading=executor.submit(()->runtime.human(reader,seed.tenant(),"FIXTURE",new byte[32]));
   try {
    boolean blocked=false;long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
    while(!blocked&&System.nanoTime()<end){try(var p=observer.prepareStatement("select exists(select 1 from pg_locks where pid=? and not granted and locktype='advisory')")){p.setInt(1,pid);try(var r=p.executeQuery()){r.next();blocked=r.getBoolean(1);}}if(!blocked)Thread.sleep(5);}
    assertTrue(blocked);assertFalse(reading.isDone());writer.commit();
    var failure=assertThrows(ExecutionException.class,()->reading.get(5,TimeUnit.SECONDS));assertInstanceOf(HumanIdentityReader.Failure.class,failure.getCause());
   }finally {writer.rollback();writer.setAutoCommit(true);}
  }
 }
}
