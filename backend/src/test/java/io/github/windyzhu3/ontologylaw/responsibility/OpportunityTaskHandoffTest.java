package io.github.windyzhu3.ontologylaw.responsibility;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.sql.Connection;
import java.time.ZoneId;
import java.util.UUID;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
class OpportunityTaskHandoffTest {
 @Test void exposesDedicatedExactHandoffPort() throws Exception {
  assertNotNull(TaskFactory.class.getMethod("handoffOpportunityTask",Connection.class,UUID.class,Subject.class,Subject.class,Subject.class,Subject.class,Subject.class,UUID.class,UUID.class,UUID.class,ZoneId.class));
 }
 @Test void rejectsAutocommitBeforeAnyReadOrMutation() {
  Connection c=(Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
   if(method.getName().equals("getAutoCommit"))return true;
   throw new AssertionError("Unexpected database access: "+method.getName());
  });
  var failure=assertThrows(java.sql.SQLException.class,()->TaskFactory.databaseBacked().handoffOpportunityTask(c,null,null,null,null,null,null,null,null,null,null));
  assertEquals("25001",failure.getSQLState());
 }
 @Test void rejectsWrongIsolationBeforeAnyReadOrMutation() {
  Connection c=(Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
   if(method.getName().equals("getAutoCommit"))return false;
   if(method.getName().equals("getTransactionIsolation"))return Connection.TRANSACTION_REPEATABLE_READ;
   throw new AssertionError("Unexpected database access: "+method.getName());
  });
  var failure=assertThrows(java.sql.SQLException.class,()->TaskFactory.databaseBacked().handoffOpportunityTask(c,null,null,null,null,null,null,null,null,null,null));
  assertEquals("25001",failure.getSQLState());
 }}

