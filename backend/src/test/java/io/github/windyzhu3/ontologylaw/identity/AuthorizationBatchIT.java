package io.github.windyzhu3.ontologylaw.identity;
import java.sql.*;import java.util.*;import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
class AuthorizationBatchIT extends AuthorizationServiceIT {
 @Test void authority_selection_keeps_one_stable_batch_for_one_complete_grant()throws Exception {
  var seed=seed();var clocks=new AtomicInteger();
  try(var c=database.apiConnection()){
   var wrapped=(Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,args)->{
    if(m.getName().equals("prepareStatement")&&args[0].equals("select clock_timestamp()"))clocks.incrementAndGet();
    try{return m.invoke(c,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
   });
   inTransaction(wrapped,Capability.QUERY,x->{try(var scope=service.lockedReadScope(x,seed.tenant())){
    var request=seed.request();var reader=R1AuthorityReader.databaseBacked();var subjects=Collections.nCopies(100,request.subject());
    clocks.set(0);var first=reader.authorizeAll(x,request.actor(),subjects,request.scopeOrganizationId(),request.requirement().slot(),request.requirement().authorityCode());
    assertEquals(100,first.size());assertEquals(2,clocks.get(),"A complete single path already passed the stable batch's end-time check");
    clocks.set(0);var second=reader.authorizeAll(x,request.actor(),subjects,request.scopeOrganizationId(),request.requirement().slot(),request.requirement().authorityCode());
    assertEquals(2,clocks.get());assertTrue(second.getFirst().checkedAt().isAfter(first.getFirst().checkedAt()));
   }return null;});
  }
 }
 @Test void locked_authorization_does_not_repeat_driver_isolation_queries()throws Exception {
  var seed=seed();var isolationReads=new AtomicInteger();
  try(var c=database.apiConnection()){
   var wrapped=(Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,args)->{
    if(m.getName().equals("getTransactionIsolation"))isolationReads.incrementAndGet();
    try{return m.invoke(c,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
   });
   inTransaction(wrapped,Capability.QUERY,x->{try(var scope=service.lockedReadScope(x,seed.tenant())){
    int before=isolationReads.get();
    for(int i=0;i<100;i++)assertTrue(service.evaluate(x,seed.request(),true).allowed());
    assertEquals(before,isolationReads.get(),"The same locked transaction was already validated");
   }return null;});
   assertThrows(SQLException.class,()->service.evaluate(wrapped,seed.request(),true),"Scope cleanup cannot authorize outside a transaction");
   c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
   assertThrows(SQLException.class,()->service.lockedReadScope(wrapped,seed.tenant()),"A new scope still validates isolation");
   c.rollback();c.setAutoCommit(true);
  }
 }
 @Test void one_locked_batch_uses_bounded_clock_reads_without_caching_decisions()throws Exception {
  var seed=seed();var clocks=new AtomicInteger();
  try(var c=database.apiConnection()){
   var wrapped=(Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,args)->{
    if(m.getName().equals("prepareStatement")&&args[0].equals("select clock_timestamp()"))clocks.incrementAndGet();
    try{return m.invoke(c,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
   });
   inTransaction(wrapped,Capability.QUERY,x->{try(var scope=service.lockedReadScope(x,seed.tenant())){
    var requests=java.util.stream.IntStream.range(0,100).mapToObj(i->seed.request()).toList();
    clocks.set(0);var snapshots=service.evaluateAll(x,requests,true);assertEquals(100,snapshots.size());assertTrue(snapshots.stream().allMatch(AuthorizationSnapshot::allowed));assertEquals(2,clocks.get());
    clocks.set(0);assertTrue(service.evaluateAll(x,requests,true).getFirst().checkedAt().isAfter(snapshots.getFirst().checkedAt()));assertEquals(2,clocks.get());
   }return null;});
  }
 }
    @Test void batch_locked_fact_reuse_still_observes_expiration() throws Exception {
        Seed s=seed();UUID timed=UUID.randomUUID();
        try(var c=database.apiConnection()) {
            inTransaction(c,Capability.COMMAND,x->{
                sql(x,"insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,valid_until,state,created_at) values (?,?,?,?,?,'LEAD_INGRESS_COMPLETE',clock_timestamp()-interval '1 day',clock_timestamp()+interval '1 second','ACTIVE',clock_timestamp())",s.tenant(),timed,s.appointment(),s.appointment(),s.org());return null;
            });
            var request=new AuthorizationService.Request(s.request().actor(),s.request().subject(),s.org(),new AuthorizationService.Requirement("LEAD_INGRESS_COMPLETE","SOURCE_INTAKE_OWNER",AuthorizationService.Path.DIRECT,timed));
            inTransaction(c,Capability.QUERY,x->{
                try(var scope=service.lockedReadScope(x,s.tenant())){
                var start=service.evaluateAll(x,List.of(request),false).getFirst();assertTrue(start.allowed());
                try(var p=x.prepareStatement("select pg_sleep(1.1)")){p.execute();}
                var end=service.evaluateAll(x,List.of(request),true).getFirst();assertFalse(end.allowed());assertTrue(end.checkedAt().isAfter(start.checkedAt()));return null;}
            });
        }
    }
    @Test void batch_locked_denial_rows_become_effective_at_their_database_time()throws Exception {
        Seed s=seed();
        try(var c=database.apiConnection()) {
            inTransaction(c,Capability.COMMAND,x->{
                sql(x,"insert into identity.object_access_grant(tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values(?,?,?,?,'LEAD_INGRESS_COMPLETE','DENY',clock_timestamp()+interval '1 second','ACTIVE',clock_timestamp(),?,?,?)",s.tenant(),UUID.randomUUID(),s.principal(),s.appointment(),s.request().subject().type(),s.request().subject().id(),s.request().subject().revision());return null;
            });
            inTransaction(c,Capability.QUERY,x->{
                try(var scope=service.lockedReadScope(x,s.tenant())) {
                    assertTrue(service.evaluateAll(x,List.of(s.request()),false).getFirst().allowed());
                    try(var p=x.prepareStatement("select pg_sleep(1.1)")){p.execute();}
                    assertFalse(service.evaluateAll(x,List.of(s.request()),false).getFirst().allowed());
                }return null;
            });
        }
    }
}
