package io.github.windyzhu3.ontologylaw.identity;
import java.sql.*;import java.util.*;import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
class AuthorizationBatchBoundaryIT extends AuthorizationServiceIT {
 @Test void grant_expiring_inside_the_batch_is_not_returned_as_allowed()throws Exception{cross(false);}
 @Test void future_deny_starting_inside_the_batch_is_not_missed()throws Exception{cross(true);}
 private void cross(boolean deny)throws Exception {
  var seed=seed();var clocks=new AtomicInteger();UUID timed=UUID.randomUUID();
  var original=seed.request();var request=deny?original:new AuthorizationService.Request(original.actor(),original.subject(),original.scopeOrganizationId(),new AuthorizationService.Requirement("LEAD_INGRESS_COMPLETE","SOURCE_INTAKE_OWNER",AuthorizationService.Path.DIRECT,timed));
  try(var c=database.apiConnection()){
   inTransaction(c,Capability.COMMAND,x->{
    if(deny)sql(x,"insert into identity.object_access_grant(tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values(?,?,?,?,'LEAD_INGRESS_COMPLETE','DENY',clock_timestamp()+interval '2 seconds','ACTIVE',clock_timestamp(),?,?,?)",seed.tenant(),UUID.randomUUID(),seed.principal(),seed.appointment(),seed.request().subject().type(),seed.request().subject().id(),seed.request().subject().revision());
    else sql(x,"insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,valid_until,state,created_at) values (?,?,?,?,?,'LEAD_INGRESS_COMPLETE',clock_timestamp()-interval '1 day',clock_timestamp()+interval '2 seconds','ACTIVE',clock_timestamp())",seed.tenant(),timed,seed.appointment(),seed.appointment(),seed.org());
    return null;
   });
   var wrapped=(Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,args)->{
    if(m.getName().equals("prepareStatement")&&args[0].equals("select clock_timestamp()")&&clocks.incrementAndGet()==2)try(var delay=c.prepareStatement("select pg_sleep(2.1)")){delay.execute();}
    try{return m.invoke(c,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
   });
   inTransaction(wrapped,Capability.QUERY,x->{try(var scope=service.lockedReadScope(x,seed.tenant())){
    var result=service.evaluateAll(x,List.of(request),true);
    assertFalse(result.getFirst().allowed());assertEquals(3,clocks.get(),"The end-of-batch boundary must force a complete second evaluation");
   }return null;});
  }
 }
}
