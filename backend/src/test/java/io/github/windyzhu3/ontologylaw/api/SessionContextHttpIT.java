package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.OntologyLawApplication;
import io.github.windyzhu3.ontologylaw.testing.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.api.security.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.support.GenericApplicationContext;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;

class SessionContextHttpIT extends PostgresIntegrationTest {
    @Test void mapped_principal_without_an_appointment_gets_audited_no_appointment_context()throws Exception {
        // Break caught: forcing a business Actor during authentication prevents the named principal-only SELF query.
        var seed=AuthorizationServiceIT.seed(database);byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);
        var subjects=new ExternalSubjectProtection(t->key);
        try(var idp=new KeycloakFixture().start()) {
            var login=idp.login();UUID principal=UUID.randomUUID();
            try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{sql(x,"insert into identity.principal (tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) values (?,?,'HUMAN','TASK92',?,'Synthetic self','ACTIVE',clock_timestamp())",seed.tenant(),principal,subjects.digest(seed.tenant(),login.subject()));return null;});}
            var verifier=HumanCredentialVerifier.isolatedLoopback(List.of(new HumanCredentialVerifier.Trust(idp.issuer(),KeycloakFixture.AUDIENCE,"TASK92",seed.tenant(),KeycloakFixture.AUDIENCE,idp.introspectionSecret)));
            var resolver=new ActorContextResolver(database::apiConnection,subjects,verifier);
            try(var context=new SpringApplicationBuilder(OntologyLawApplication.class).initializers(c->{var registry=(GenericApplicationContext)c;registry.registerBean(ActorContextResolver.class,()->resolver);registry.registerBean(SessionContextController.Services.class,()->new SessionContextController.Services(database::apiConnection,io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked("TASK92_IT"),new ActorScopeProtection(t->key)));})
                    .properties("ols.runtime-role=api","server.port=0","spring.main.banner-mode=off","logging.level.root=OFF").run();var client=HttpClient.newHttpClient()) {
                var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+context.getEnvironment().getRequiredProperty("local.server.port")+"/api/v1/session/context")).header("Authorization","Bearer "+login.accessToken()).GET().build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(200,response.statusCode(),"Authenticated SELF must not require an Appointment");
                var body=tools.jackson.databind.json.JsonMapper.builder().build().readTree(response.body());
                assertEquals("NO_APPOINTMENT",body.path("state").asString());assertTrue(body.path("selectedAppointmentId").isNull());assertTrue(body.path("actorScopeKey").isNull());
                assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
                try(var c=database.migratorConnection();var p=c.prepareStatement("select count(*) from audit.audit_entry where tenant_id=? and actor_principal_id=? and action_code='GET_SESSION_CONTEXT' and actor_appointment_id is null and on_behalf_of_principal_id is null and authorization_slot_code='SELF_IDENTITY' and authorization_fact_id is null")){p.setObject(1,seed.tenant());p.setObject(2,principal);try(var r=p.executeQuery()){r.next();assertEquals(1,r.getInt(1));}}
            }
        }
    }
}
