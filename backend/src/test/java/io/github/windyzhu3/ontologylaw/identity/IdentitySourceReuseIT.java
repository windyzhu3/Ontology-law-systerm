package io.github.windyzhu3.ontologylaw.identity;
import java.sql.*;import java.time.*;import java.util.*;import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
class IdentitySourceReuseIT extends AuthorizationServiceIT {
 @Test void repeated_owner_metadata_reuses_raw_rows_but_rechecks_time_and_scope()throws Exception{
  var original=seed();var s=new Seed(original.tenant(),original.principal(),UUID.randomUUID(),original.org(),original.grant(),original.subject());Instant until=Instant.now().plusSeconds(30);
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{sql(x,"insert into identity.appointment(tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,effective_until,state,created_at) values(?,?,?,?,'TEST',clock_timestamp()-interval '1 day',?,'ACTIVE',clock_timestamp())",s.tenant(),s.appointment(),s.principal(),s.org(),until.atOffset(ZoneOffset.UTC));return null;});}
  var statements=new AtomicInteger();
  try(var c=database.apiConnection()){
   var measured=(Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
    if(m.getName().equals("prepareStatement")&&a[0].toString().contains("appointment"))statements.incrementAndGet();
    try{return m.invoke(c,a);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
   });
   inTransaction(measured,Capability.QUERY,tx->{
    var reader=AuthorizationIdentityReader.databaseBacked();var organizations=OpportunityOwnerExceptionAuthorityReader.databaseBacked();
    try(var scope=AuthorizationService.databaseBacked().lockedReadScope(tx,s.tenant())){
     statements.set(0);
     for(int i=0;i<20;i++){assertNotNull(reader.registration(tx,s.tenant(),s.appointment()));assertTrue(reader.owner(tx,s.tenant(),s.appointment(),until.minusSeconds(1)).active());assertFalse(reader.owner(tx,s.tenant(),s.appointment(),until.plusSeconds(1)).active());assertEquals(s.org(),organizations.historicalOrganization(tx,s.tenant(),s.appointment()));}
     assertEquals(3,statements.get(),"Registration, raw owner validity and historical organization each load once");
     assertNull(reader.registration(tx,UUID.randomUUID(),s.appointment()));
     try(var other=database.apiConnection()){inTransaction(other,Capability.QUERY,x->{assertNotNull(reader.registration(x,s.tenant(),s.appointment()));return null;});}
    }
    int before=statements.get();assertNotNull(reader.registration(tx,s.tenant(),s.appointment()));assertTrue(statements.get()>before);return null;
   });
  }
 }
}
