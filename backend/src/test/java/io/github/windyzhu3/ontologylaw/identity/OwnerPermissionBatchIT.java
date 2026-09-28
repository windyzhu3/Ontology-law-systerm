package io.github.windyzhu3.ontologylaw.identity;

import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;

class OwnerPermissionBatchIT extends AuthorizationServiceIT {
    @Test void locked_permission_checks_bound_clock_round_trips() throws Exception {
        var s=seed();var clocks=new AtomicInteger();
        try(var c=database.apiConnection()) {
            var wrapped=(Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,args)->{
                if(m.getName().equals("prepareStatement")&&args[0].equals("select clock_timestamp()"))clocks.incrementAndGet();
                try{return m.invoke(c,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
            });
            inTransaction(wrapped,Capability.QUERY,x->{
                try(var scope=service.lockedReadScope(x,s.tenant())) {
                    var facts=Collections.nCopies(100,s.request().subject());clocks.set(0);
                    assertTrue(OpportunityOwnerExceptionAuthorityReader.databaseBacked().permitted(x,s.request().actor(),s.org(),facts,"LEAD_INGRESS_COMPLETE"));
                    assertTrue(clocks.get()<=4,"one complete grant batch and final batch should require at most four clock reads; actual="+clocks.get());
                }return null;
            });
        }
    }
    @Test void exact_denial_and_empty_facts_remain_denied_inside_and_outside_scope() throws Exception {
        var s=seed();
        try(var c=database.apiConnection()) {
            inTransaction(c,Capability.COMMAND,x->{
                sql(x,"insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values (?,?,?,?,'LEAD_INGRESS_COMPLETE','DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),'lead.lead',?,0)",s.tenant(),UUID.randomUUID(),s.principal(),s.appointment(),s.subject());return null;
            });
            inTransaction(c,Capability.QUERY,x->{
                var reader=OpportunityOwnerExceptionAuthorityReader.databaseBacked();
                assertFalse(reader.permitted(x,s.request().actor(),s.org(),List.of(s.request().subject()),"LEAD_INGRESS_COMPLETE"));
                try(var scope=service.lockedReadScope(x,s.tenant())) {
                    assertFalse(reader.permitted(x,s.request().actor(),s.org(),List.of(),"LEAD_INGRESS_COMPLETE"));
                    assertFalse(reader.permitted(x,s.request().actor(),s.org(),List.of(s.request().subject()),"LEAD_INGRESS_COMPLETE"));
                }return null;
            });
        }
    }
}
