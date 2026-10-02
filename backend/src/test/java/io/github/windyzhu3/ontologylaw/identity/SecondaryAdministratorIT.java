package io.github.windyzhu3.ontologylaw.identity;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.execution.IdentityAdminReadRuntime;
import java.time.Instant;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SecondaryAdministratorIT extends IdentityAdminFixture {
 @Test void final_management_set_rejects_a_deny_activated_during_its_evaluation()throws Exception {
  UUID app=appointment(principal(),seed.org());Instant activates=Instant.now().plusSeconds(3);
  mutate("insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values (?,?,?,?,'IDENTITY_PRINCIPAL_MANAGE','DENY',?::timestamptz,'ACTIVE',clock_timestamp(),'identity.appointment',?,0)",seed.tenant(),UUID.randomUUID(),seed.principal(),seed.appointment(),activates.toString(),app);
  var before=counts();var reads=new java.util.concurrent.atomic.AtomicInteger();
  try(var actual=database.apiConnection()) {
   var delayed=(Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
    try {
     Object value=method.invoke(actual,args);
     if(method.getName().equals("prepareStatement")&&args[0] instanceof String sql&&sql.contains("authority_grant")&&sql.contains("authority_grant_id")&&sql.contains("where")) {
      var statement=(PreparedStatement)value;var selected=new java.util.concurrent.atomic.AtomicBoolean();
      return java.lang.reflect.Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{
       if(m.getName().startsWith("set")&&a!=null&&a.length>=2&&seed.grant().toString().equals(String.valueOf(a[1])))selected.set(true);
       if((m.getName().equals("executeQuery")||m.getName().equals("execute"))&&selected.get()&&reads.incrementAndGet()==4) {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(6);boolean active=false;
        while(System.nanoTime()<deadline){active="YES".equals(scalar("select case when clock_timestamp()>=?::timestamptz then 'YES' else 'NO' end",activates.toString()));if(active)break;Thread.sleep(25);}assertTrue(active);
       }
       try{return m.invoke(statement,a);}catch(java.lang.reflect.InvocationTargetException error){throw error.getCause();}
      });
     }
     return value;
    }catch(java.lang.reflect.InvocationTargetException error){throw error.getCause();}
   });
   var result=runtime.execute(delayed,envelope(UUID.randomUUID(),"CREATE_AUTHORITY_GRANT",null,null,body("appointmentId",app.toString(),"scopeOrganizationId",seed.org().toString(),"authorityCode","IDENTITY_AUTHORITY_MANAGE","validFrom",Instant.now().minusSeconds(5).toString(),"validUntil",null)));
   assertTrue(reads.get()>=4,"The final management authorization pass must reach the delayed target check");rejected("NOT_AUTHORIZED",result);
  }
  assertTrue(reads.get()>=4,"The final management authorization pass must reach the delayed target check");delta(before,-1);
 }
 private UUID grantCode(UUID app,UUID scope,String code)throws Exception {
  return success(execute("CREATE_AUTHORITY_GRANT",null,null,body("appointmentId",app.toString(),"scopeOrganizationId",scope.toString(),"authorityCode",code,"validFrom",Instant.now().minusSeconds(5).toString(),"validUntil",null))).id();
 }
 private List<String> options()throws Exception {
  try(var c=database.apiConnection()) {
   var result=new IdentityAdminReadRuntime(audit,protection,candidates,"FIXTURE",directory).read(c,actor,"getIdentityAdminOptions","AUTHORITY_GRANTS","APPOINTMENT",50,null,null);
   @SuppressWarnings("unchecked") var codes=(List<String>)result.get("grantableAuthorityCodes");return codes;
  }
 }
 @Test void second_admin_uses_four_explicit_grants_then_grants_audit_to_original_admin()throws Exception {
  UUID person=principal(),app=appointment(person,seed.org());
  for(String code:IdentityCommands.MANAGEMENT){var before=counts();grantCode(app,seed.org(),code);delta(before,3);}
  actor=new Actor(seed.tenant(),person,app,null,null);
  assertTrue(options().containsAll(IdentityCommands.MANAGEMENT));
  var before=counts();UUID auditGrant=grantCode(seed.appointment(),seed.org(),"AUDIT_READ");delta(before,3);
  assertEquals(app.toString(),scalar("select granted_by_appointment_id from identity.authority_grant where tenant_id=? and authority_grant_id=?",seed.tenant(),auditGrant));
 }
 @Test void creator_with_only_authority_management_cannot_delegate_management_even_at_root()throws Exception {
  UUID person=principal(),partial=appointment(person,seed.org()),target=appointment(principal(),seed.org());
  grantCode(partial,seed.org(),"IDENTITY_AUTHORITY_MANAGE");actor=new Actor(seed.tenant(),person,partial,null,null);
  assertFalse(options().stream().anyMatch(IdentityCommands.MANAGEMENT::contains));var before=counts();
  assertEquals("NOT_AUTHORIZED",assertThrows(IdentityCommands.Failure.class,()->grantCode(target,seed.org(),"IDENTITY_PRINCIPAL_MANAGE")).code());assertEquals(before,counts());
 }
 @Test void local_scope_or_local_target_cannot_receive_management()throws Exception {
  UUID org=organization(),rootApp=appointment(principal(),seed.org()),localApp=appointment(principal(),org);
  for(var pair:List.of(List.of(rootApp,org),List.of(localApp,seed.org()))) {
   var before=counts();assertEquals("NOT_AUTHORIZED",assertThrows(IdentityCommands.Failure.class,()->grantCode(pair.get(0),pair.get(1),"IDENTITY_AUTHORITY_MANAGE")).code());assertEquals(before,counts());
  }
 }
 @Test void missing_management_object_permission_rejects_known_target()throws Exception {
  UUID app=appointment(principal(),seed.org());
  mutate("insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values (?,?,?,?,'IDENTITY_PRINCIPAL_MANAGE','DENY',clock_timestamp()-interval '1 hour','ACTIVE',clock_timestamp(),'identity.appointment',?,0)",seed.tenant(),UUID.randomUUID(),seed.principal(),seed.appointment(),app);
  var before=counts();assertEquals("NOT_AUTHORIZED",assertThrows(IdentityCommands.Failure.class,()->grantCode(app,seed.org(),"IDENTITY_AUTHORITY_MANAGE")).code());assertEquals(before,counts());
 }
 @Test void self_management_grant_remains_refused_and_second_admin_does_not_gain_audit_implicitly()throws Exception {
  rejected("IDENTITY_SELF_LOCKOUT",execute("CREATE_AUTHORITY_GRANT",null,null,body("appointmentId",seed.appointment().toString(),"scopeOrganizationId",seed.org().toString(),"authorityCode","IDENTITY_AUTHORITY_MANAGE","validFrom",Instant.now().toString(),"validUntil",null)));
  UUID person=principal(),app=appointment(person,seed.org());for(String code:IdentityCommands.MANAGEMENT)grantCode(app,seed.org(),code);
  assertEquals("0",scalar("select count(*) from identity.authority_grant where tenant_id=? and grantee_appointment_id=? and authority_code='AUDIT_READ'",seed.tenant(),app));
  actor=new Actor(seed.tenant(),person,app,null,null);UUID original=UUID.fromString(scalar("select authority_grant_id from identity.authority_grant where tenant_id=? and grantee_appointment_id=? and authority_code='IDENTITY_AUTHORITY_MANAGE'",seed.tenant(),seed.appointment()));success(change("REVOKE_AUTHORITY_GRANT",original));
  UUID own=UUID.fromString(scalar("select authority_grant_id from identity.authority_grant where tenant_id=? and grantee_appointment_id=? and authority_code='IDENTITY_AUTHORITY_MANAGE'",seed.tenant(),app));rejected("IDENTITY_LAST_ADMIN",change("REVOKE_AUTHORITY_GRANT",own));
 }
 @Test void management_grant_replay_requires_the_current_complete_permission_set()throws Exception {
  UUID person=principal(),app=appointment(person,seed.org()),key=UUID.randomUUID();
  var payload=body("appointmentId",app.toString(),"scopeOrganizationId",seed.org().toString(),"authorityCode","IDENTITY_PRINCIPAL_MANAGE","validFrom",Instant.now().minusSeconds(5).toString(),"validUntil",null);
  UUID original=success(execute(key,"CREATE_AUTHORITY_GRANT",null,null,payload)).id();var before=counts();assertEquals(original,success(execute(key,"CREATE_AUTHORITY_GRANT",null,null,payload)).id());assertEquals(before,counts());
  for(String code:IdentityCommands.MANAGEMENT)if(!code.equals("IDENTITY_PRINCIPAL_MANAGE"))grantCode(app,seed.org(),code);
  var founder=actor;actor=new Actor(seed.tenant(),person,app,null,null);success(change("REVOKE_AUTHORITY_GRANT",seed.grant()));actor=founder;before=counts();
  assertEquals("NOT_AUTHORIZED",assertThrows(IdentityCommands.Failure.class,()->execute(key,"CREATE_AUTHORITY_GRANT",null,null,payload)).code());assertEquals(before,counts());
 }
}
