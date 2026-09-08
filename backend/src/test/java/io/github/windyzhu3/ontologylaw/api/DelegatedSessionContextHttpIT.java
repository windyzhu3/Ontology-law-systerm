package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.OntologyLawApplication;
import io.github.windyzhu3.ontologylaw.testing.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.api.security.*;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.support.GenericApplicationContext;
import java.net.*;
import java.net.http.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;

/** Existing one-hop delegations are explicitly initialized synthetic compatibility fixtures, not ADM-05 CRUD. */
class DelegatedSessionContextHttpIT extends PostgresIntegrationTest {
    KeycloakFixture idp;
    @BeforeAll void startIdentity()throws Exception{idp=new KeycloakFixture().start();}
    @AfterAll void stopIdentity(){if(idp!=null)idp.close();}
    record Fixture(UUID tenant,UUID organization,UUID principal,UUID own,UUID represented,UUID representedPrincipal,UUID source,UUID delegation,ActorContextResolver resolver,ActorScopeProtection scopes,String token) {
        public String toString(){return "DelegatedFixture[restricted]";}
    }
    Fixture fixture()throws Exception {
        var seed=AuthorizationServiceIT.seed(database,"HUMAN","LEAD_CAPTURE");var login=idp.login();byte[] subjectKey=new byte[32],scopeKey=new byte[32];new java.security.SecureRandom().nextBytes(subjectKey);new java.security.SecureRandom().nextBytes(scopeKey);
        var subjects=new ExternalSubjectProtection(t->subjectKey);UUID principal=UUID.randomUUID(),own=UUID.randomUUID(),delegation=UUID.randomUUID();
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            sql(x,"insert into identity.principal (tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) values (?,?,'HUMAN','TASK92',?,'Synthetic delegate','ACTIVE',clock_timestamp())",seed.tenant(),principal,subjects.digest(seed.tenant(),login.subject()));
            sql(x,"insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'CONTACT_OPERATOR',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),own,principal,seed.org());
            sql(x,"insert into identity.delegation_grant (tenant_id,delegation_grant_id,source_authority_grant_id,delegator_appointment_id,delegate_appointment_id,scope_organization_unit_id,valid_from,state,created_at) values (?,?,?,?,?,?,clock_timestamp()-interval '1 hour','ACTIVE',clock_timestamp())",seed.tenant(),delegation,seed.grant(),seed.appointment(),own,seed.org());return null;});}
        var verifier=HumanCredentialVerifier.isolatedLoopback(List.of(new HumanCredentialVerifier.Trust(idp.issuer(),KeycloakFixture.AUDIENCE,"TASK92",seed.tenant(),KeycloakFixture.AUDIENCE,idp.introspectionSecret)));
        return new Fixture(seed.tenant(),seed.org(),principal,own,seed.appointment(),seed.principal(),seed.grant(),delegation,new ActorContextResolver(database::apiConnection,subjects,verifier),new ActorScopeProtection(t->scopeKey),login.accessToken());
    }
    final class Http implements AutoCloseable {
        final org.springframework.context.ConfigurableApplicationContext context;final HttpClient client=HttpClient.newHttpClient();final Fixture f;
        Http(Fixture f)throws Exception{this(f,database::apiConnection);}
        Http(Fixture f,ActorContextResolver.Connections selfConnections)throws Exception {
            this.f=f;context=new SpringApplicationBuilder(OntologyLawApplication.class).initializers(c->{var registry=(GenericApplicationContext)c;registry.registerBean(ActorContextResolver.class,()->f.resolver());registry.registerBean(SessionContextController.Services.class,()->new SessionContextController.Services(selfConnections,AuditAppender.databaseBacked("TASK92_DELEGATED_IT"),f.scopes()));})
                    .properties("ols.runtime-role=api","server.port=0","spring.main.banner-mode=off","logging.level.root=OFF").run();
        }
        HttpResponse<String> request(String method,String path,Map<String,List<String>> headers)throws Exception {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+context.getEnvironment().getRequiredProperty("local.server.port")+path)).header("Authorization","Bearer "+f.token());
            headers.forEach((key,values)->values.forEach(value->request.header(key,value)));
            return client.send(request.method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> self(Map<String,List<String>> headers)throws Exception{return request("GET","/api/v1/session/context",headers);}
        public void close(){context.close();client.close();}
    }
    static Map<String,List<String>> selected(Fixture f){return Map.of("X-Appointment-Id",List.of(f.own().toString()),"X-On-Behalf-Appointment-Id",List.of(f.represented().toString()));}
    static tools.jackson.databind.JsonNode json(HttpResponse<String> response){return tools.jackson.databind.json.JsonMapper.builder().build().readTree(response.body());}
    void mutate(String statement,Object...args)throws Exception {try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{sql(x,statement,args);return null;});}}
    long audits(Fixture f)throws Exception {try(var c=database.migratorConnection();var p=c.prepareStatement("select count(*) from audit.audit_entry where tenant_id=?")){p.setObject(1,f.tenant());try(var r=p.executeQuery()){r.next();return r.getLong(1);}}}
    @Test void candidates_are_deduplicated_and_never_implicitly_selected_but_explicit_tuple_has_stable_distinct_key()throws Exception {
        // Break caught: ignoring the new selector silently executes as the logged-in person's own identity.
        var f=fixture();
        mutate("insert into identity.delegation_grant (tenant_id,delegation_grant_id,source_authority_grant_id,delegator_appointment_id,delegate_appointment_id,scope_organization_unit_id,valid_from,state,created_at) values (?,?,?,?,?,?,clock_timestamp()-interval '1 hour','ACTIVE',clock_timestamp())",f.tenant(),UUID.randomUUID(),f.source(),f.represented(),f.own(),f.organization());
        String delegatedKey;
        try(var http=new Http(f)) {
            var own=http.self(Map.of());assertEquals(200,own.statusCode());var ownBody=json(own);
            assertEquals(1,ownBody.path("delegatedAppointmentChoices").size());assertTrue(ownBody.path("selectedOnBehalfAppointmentId").isNull());
            var selected=http.self(selected(f));assertEquals(200,selected.statusCode());var body=json(selected);
            assertEquals(f.represented().toString(),body.path("selectedOnBehalfAppointmentId").asString());assertFalse(body.path("canEnterIdentityAdmin").asBoolean());assertFalse(body.path("canEnterWorkbench").asBoolean(),"A capture-only candidate does not confer workbench entry");
            delegatedKey=body.path("actorScopeKey").asString();assertFalse(ownBody.path("actorScopeKey").asString().equals(delegatedKey),"Own and delegated scopes differ (values restricted)");
            assertEquals(9,body.size());assertFalse(selected.body().contains(f.principal().toString()));assertFalse(selected.body().contains(f.representedPrincipal().toString()));
        }
        mutate("update identity.delegation_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and delegation_grant_id=?",f.tenant(),f.delegation());
        var loginAgain=idp.login();var renewed=new Fixture(f.tenant(),f.organization(),f.principal(),f.own(),f.represented(),f.representedPrincipal(),f.source(),f.delegation(),f.resolver(),f.scopes(),loginAgain.accessToken());
        try(var restarted=new Http(renewed)){assertTrue(delegatedKey.equals(json(restarted.self(selected(f))).path("actorScopeKey").asString()),"Relogin, server reconstruction and alternate current delegation evidence preserve the Actor scope (values restricted)");}
        try(var c=database.migratorConnection();var p=c.prepareStatement("select actor_principal_id,actor_appointment_id,on_behalf_of_principal_id,on_behalf_of_appointment_id,change_summary from audit.audit_entry where tenant_id=? order by trusted_at")){p.setObject(1,f.tenant());try(var r=p.executeQuery()){int count=0;while(r.next()){count++;assertEquals(f.principal(),r.getObject(1));assertEquals(f.own(),r.getObject(2));assertNull(r.getObject(3));assertNull(r.getObject(4));var summary=tools.jackson.databind.json.JsonMapper.builder().build().readTree(r.getString(5));assertEquals(3,summary.path("disclosedSources").size());assertFalse(r.getString(5).contains(f.source().toString()));}assertEquals(3,count);}}
    }
    @ParameterizedTest @ValueSource(strings={"EMPTY","DUPLICATE","COMMA","BAD_UUID","UNPAIRED","OWN_BAD"})
    void malformed_authentication_selectors_are_400_with_no_disclosure(String defect)throws Exception {
        var f=fixture();var headers=new HashMap<>(selected(f));
        switch(defect){case "EMPTY"->headers.put("X-On-Behalf-Appointment-Id",List.of(""));case "DUPLICATE"->headers.put("X-On-Behalf-Appointment-Id",List.of(f.represented().toString(),f.represented().toString()));case "COMMA"->headers.put("X-On-Behalf-Appointment-Id",List.of(f.represented()+","+f.own()));case "BAD_UUID"->headers.put("X-On-Behalf-Appointment-Id",List.of("bad"));case "UNPAIRED"->headers.remove("X-Appointment-Id");case "OWN_BAD"->headers.put("X-Appointment-Id",List.of("bad"));}
        long before=audits(f);try(var http=new Http(f)){var response=http.self(headers);assertEquals(400,response.statusCode());assertEquals("VALIDATION_FAILED",json(response).path("code").asString());assertTrue(json(response).has("fieldErrors"));assertFalse(response.body().contains("Synthetic delegate"));}assertEquals(before,audits(f));
    }
    @ParameterizedTest @ValueSource(strings={"DELEGATION","SOURCE","OWN_PRINCIPAL","REPRESENTED_PRINCIPAL","OWN_APPOINTMENT","REPRESENTED_APPOINTMENT","ORGANIZATION","UNKNOWN"})
    void current_relation_or_party_revocation_is_uniformly_denied_without_own_identity_fallback(String defect)throws Exception {
        var f=fixture();var headers=selected(f);
        switch(defect){
            case "DELEGATION"->mutate("update identity.delegation_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and delegation_grant_id=?",f.tenant(),f.delegation());
            case "SOURCE"->mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_grant_id=?",f.tenant(),f.source());
            case "OWN_PRINCIPAL","REPRESENTED_PRINCIPAL"->mutate("update identity.principal set state='SUSPENDED',revision=revision+1 where tenant_id=? and principal_id=?",f.tenant(),defect.startsWith("OWN")?f.principal():f.representedPrincipal());
            case "OWN_APPOINTMENT","REPRESENTED_APPOINTMENT"->mutate("update identity.appointment set state='SUSPENDED',revision=revision+1 where tenant_id=? and appointment_id=?",f.tenant(),defect.startsWith("OWN")?f.own():f.represented());
            case "ORGANIZATION"->mutate("update identity.organization_unit set state='CLOSED',closed_at=clock_timestamp(),revision=revision+1 where tenant_id=? and organization_unit_id=?",f.tenant(),f.organization());
            case "UNKNOWN"->headers=Map.of("X-Appointment-Id",List.of(f.own().toString()),"X-On-Behalf-Appointment-Id",List.of(UUID.randomUUID().toString()));
        }
        long before=audits(f);try(var http=new Http(f)){var response=http.self(headers);assertEquals(403,response.statusCode());assertEquals("NOT_AUTHORIZED",json(response).path("code").asString());assertFalse(response.body().contains("actorScopeKey"));}assertEquals(before,audits(f));
    }
    @Test void all_twenty_identity_management_operations_reject_delegated_selector_before_handlers()throws Exception {
        var f=fixture();String root="/api/v1/admin/identity/",id=UUID.randomUUID().toString();
        var operations=new ArrayList<String>();for(String route:List.of("provider-users","options","principals","organizations","appointments","authority-grants"))operations.add("GET "+root+route);
        for(String route:List.of("principals","organizations","appointments","authority-grants"))operations.add("POST "+root+route);
        for(String route:List.of("principals","organizations"))operations.add("PATCH "+root+route+"/"+id+"/display-name");
        for(String suffix:List.of("suspend","resume","disable"))operations.add("POST "+root+"principals/"+id+"/"+suffix);
        operations.add("POST "+root+"organizations/"+id+"/close");for(String suffix:List.of("suspend","resume","end"))operations.add("POST "+root+"appointments/"+id+"/"+suffix);operations.add("POST "+root+"authority-grants/"+id+"/revoke");
        assertEquals(20,operations.size());long before=audits(f);try(var http=new Http(f)){for(String operation:operations){var parts=operation.split(" ");assertEquals(403,http.request(parts[0],parts[1],selected(f)).statusCode(),parts[0]+" management endpoint must reject delegated context");}}assertEquals(before,audits(f));
    }
    void additionalChoices(Fixture f,int ownCount,int delegatedCount)throws Exception {
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            for(int i=0;i<ownCount;i++)sql(x,"insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'CONTACT_OPERATOR',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",f.tenant(),UUID.randomUUID(),f.principal(),f.organization());
            for(int i=0;i<delegatedCount;i++){
                UUID represented=UUID.randomUUID(),source=UUID.randomUUID();
                sql(x,"insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'CONTACT_OPERATOR',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",f.tenant(),represented,f.representedPrincipal(),f.organization());
                sql(x,"insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,'LEAD_CAPTURE',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",f.tenant(),source,represented,represented,f.organization());
                sql(x,"insert into identity.delegation_grant (tenant_id,delegation_grant_id,source_authority_grant_id,delegator_appointment_id,delegate_appointment_id,scope_organization_unit_id,valid_from,state,created_at) values (?,?,?,?,?,?,clock_timestamp()-interval '1 hour','ACTIVE',clock_timestamp())",f.tenant(),UUID.randomUUID(),source,represented,f.own(),f.organization());
            }return null;});}
    }
    @Test void maximum_self_disclosure_is_exactly_own_principal_plus_fifty_own_and_fifty_delegated_appointments()throws Exception {
        var f=fixture();additionalChoices(f,49,49);
        try(var http=new Http(f)) {
            var unselected=http.self(Map.of());assertEquals(200,unselected.statusCode());assertEquals("APPOINTMENT_SELECTION_REQUIRED",json(unselected).path("state").asString());assertTrue(json(unselected).path("actorScopeKey").isNull());assertEquals(0,json(unselected).path("delegatedAppointmentChoices").size());
            var selected=http.self(selected(f));assertEquals(200,selected.statusCode());assertEquals(50,json(selected).path("appointmentChoices").size());assertEquals(50,json(selected).path("delegatedAppointmentChoices").size());
            var ids=new ArrayList<String>();json(selected).path("delegatedAppointmentChoices").forEach(choice->ids.add(choice.path("id").asString()));var sorted=new ArrayList<>(ids);Collections.sort(sorted);assertEquals(sorted,ids);
        }
        try(var c=database.migratorConnection();var p=c.prepareStatement("select change_summary from audit.audit_entry where tenant_id=? order by trusted_at desc limit 1")){p.setObject(1,f.tenant());try(var r=p.executeQuery()){assertTrue(r.next());var summary=tools.jackson.databind.json.JsonMapper.builder().build().readTree(r.getString(1));assertEquals(101,summary.path("disclosedSources").size());assertEquals(101,summary.path("resultCount").asInt());}}
    }
    @ParameterizedTest @ValueSource(strings={"OWN","DELEGATED"})
    void fifty_first_effective_choice_fails_closed_without_truncation_or_audit(String kind)throws Exception {
        var f=fixture();additionalChoices(f,kind.equals("OWN")?50:0,kind.equals("DELEGATED")?50:0);
        try(var http=new Http(f)){var result=http.self(selected(f));assertEquals(503,result.statusCode());assertFalse(result.body().contains("Synthetic"));}assertEquals(0,audits(f));
    }
    @ParameterizedTest @ValueSource(strings={"AUDIT_WRITE","COMMIT_ACK"})
    void audit_failure_or_uncertain_commit_never_releases_self_body(String kind)throws Exception {
        var f=fixture();ActorContextResolver.Connections faulty=()->{
            var connection=database.apiConnection();
            return (Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                if(kind.equals("AUDIT_WRITE")&&method.getName().equals("prepareStatement")&&args[0] instanceof String statement&&statement.toLowerCase(Locale.ROOT).contains("audit_entry"))throw new SQLException("Synthetic audit failure","08006");
                try{var result=method.invoke(connection,args);if(kind.equals("COMMIT_ACK")&&method.getName().equals("commit"))throw new SQLException("Synthetic unknown commit","08006");return result;}
                catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
            });
        };
        try(var http=new Http(f,faulty)){var result=http.self(selected(f));assertEquals(503,result.statusCode());assertFalse(result.body().contains("Synthetic delegate"));assertFalse(result.body().contains("actorScopeKey"));}
        assertEquals(kind.equals("COMMIT_ACK")?1:0,audits(f));
    }
    @Test void self_does_not_accept_query_identity_aliases_or_conditional_cache_validation()throws Exception {
        var f=fixture();try(var http=new Http(f)) {
            var query=http.request("GET","/api/v1/session/context?tenantId="+UUID.randomUUID(),Map.of());assertEquals(400,query.statusCode());assertEquals(0,audits(f));
            var conditional=http.self(Map.of("If-None-Match",List.of("*")));assertEquals(200,conditional.statusCode());assertEquals("no-store",conditional.headers().firstValue("Cache-Control").orElseThrow());assertTrue(conditional.headers().firstValue("ETag").isEmpty());
        }
    }
    @Test void self_holds_business_and_identity_fences_until_audit_commit_then_observes_revocation()throws Exception {
        var f=fixture();var auditReached=new java.util.concurrent.CountDownLatch(1);var allowAudit=new java.util.concurrent.CountDownLatch(1);
        ActorContextResolver.Connections held=()->{var actual=database.apiConnection();return (Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            if(method.getName().equals("prepareStatement")&&args[0] instanceof String sql&&sql.startsWith("insert")&&sql.contains("audit_entry")){auditReached.countDown();if(!allowAudit.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new SQLException("Synthetic synchronization timeout");}
            try{return method.invoke(actual,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
        });};
        try(var http=new Http(f,held);var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var response=executor.submit(()->http.self(selected(f)));assertTrue(auditReached.await(5,java.util.concurrent.TimeUnit.SECONDS));
            var writer=executor.submit(()->{try(var c=database.apiConnection()){return inTransaction(c,Capability.COMMAND,x->{io.github.windyzhu3.ontologylaw.execution.R1BusinessFence.databaseBacked().exclusive(x,f.tenant());AuthorizationService.databaseBacked().lockForMutation(x,f.tenant());sql(x,"update identity.delegation_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and delegation_grant_id=?",f.tenant(),f.delegation());return true;});}});
            try{assertThrows(java.util.concurrent.TimeoutException.class,()->writer.get(200,java.util.concurrent.TimeUnit.MILLISECONDS));}finally{allowAudit.countDown();}
            assertEquals(200,response.get(10,java.util.concurrent.TimeUnit.SECONDS).statusCode());assertTrue(writer.get(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(1,audits(f));
            assertEquals(403,http.self(selected(f)).statusCode());assertEquals(1,audits(f));
        }finally{allowAudit.countDown();}
    }
    @Test void a_local_principal_management_grant_does_not_confer_root_only_administration_entry()throws Exception {
        var f=fixture();UUID child=UUID.randomUUID(),appointment=UUID.randomUUID();
        mutate("insert into identity.organization_unit (tenant_id,organization_unit_id,parent_organization_unit_id,unit_code,display_name,state,created_at) values (?,?,?,'CHILD','Synthetic child','ACTIVE',clock_timestamp())",f.tenant(),child,f.organization());
        mutate("insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'CONTACT_OPERATOR',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",f.tenant(),appointment,f.principal(),child);
        mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,'IDENTITY_PRINCIPAL_MANAGE',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",f.tenant(),UUID.randomUUID(),appointment,appointment,child);
        try(var http=new Http(f)){var response=http.self(Map.of("X-Appointment-Id",List.of(appointment.toString())));assertEquals(200,response.statusCode());assertFalse(json(response).path("canEnterIdentityAdmin").asBoolean());}
    }
    @Test void invalid_credential_is_401_before_selector_validation_and_never_discloses_self()throws Exception {
        var valid=fixture();var f=new Fixture(valid.tenant(),valid.organization(),valid.principal(),valid.own(),valid.represented(),valid.representedPrincipal(),valid.source(),valid.delegation(),valid.resolver(),valid.scopes(),"not-a-credential");
        try(var http=new Http(f)){var response=http.self(Map.of("X-On-Behalf-Appointment-Id",List.of("bad")));assertEquals(401,response.statusCode());assertEquals("UNAUTHENTICATED",json(response).path("code").asString());assertEquals(0,audits(f));}
    }
}
