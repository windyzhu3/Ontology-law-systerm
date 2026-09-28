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
/** Real credential selection: a management entry never depends on a sales task permission. */
class R2ManagementSessionHttpIT extends PostgresIntegrationTest {
    @Test void transfer_only_reader_receives_exact_view_hint_and_loses_it_after_revocation()throws Exception {
        var seed=AuthorizationServiceIT.seed(database);byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);var subjects=new ExternalSubjectProtection(t->key);
        try(var idp=new KeycloakFixture().start()){
            var login=idp.login();UUID principal=UUID.randomUUID(),appointment=UUID.randomUUID(),grant=UUID.randomUUID();
            try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
                sql(x,"insert into identity.principal (tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) values (?,?,'HUMAN','M01SESSION',?,'Ledger manager','ACTIVE',clock_timestamp())",seed.tenant(),principal,subjects.digest(seed.tenant(),login.subject()));
                sql(x,"insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'ROUTING_SUPERVISOR',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),appointment,principal,seed.org());
                sql(x,"insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,'TRANSFER_LEDGER_READ',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),grant,appointment,seed.appointment(),seed.org());return null;
            });}
            var verifier=HumanCredentialVerifier.isolatedLoopback(List.of(new HumanCredentialVerifier.Trust(idp.issuer(),KeycloakFixture.AUDIENCE,"M01SESSION",seed.tenant(),KeycloakFixture.AUDIENCE,idp.introspectionSecret)));
            var resolver=new ActorContextResolver(database::apiConnection,subjects,verifier);
            try(var context=new SpringApplicationBuilder(OntologyLawApplication.class).initializers(c->{var registry=(GenericApplicationContext)c;registry.registerBean(ActorContextResolver.class,()->resolver);registry.registerBean(SessionContextController.Services.class,()->new SessionContextController.Services(database::apiConnection,io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked("M01_SESSION_IT"),new ActorScopeProtection(t->key)));}).properties("ols.runtime-role=api","server.port=0","spring.main.banner-mode=off","logging.level.root=OFF").run();var client=HttpClient.newHttpClient()){
                var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+context.getEnvironment().getRequiredProperty("local.server.port")+"/api/v1/session/context")).header("Authorization","Bearer "+login.accessToken()).header("X-Appointment-Id",appointment.toString()).GET().build();
                var response=client.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode(),response.body());var mapper=tools.jackson.databind.json.JsonMapper.builder().build();var body=mapper.readTree(response.body());assertEquals("READY",body.path("state").asString());assertFalse(body.path("canEnterWorkbench").asBoolean());assertFalse(body.path("canEnterIdentityAdmin").asBoolean());assertTrue(body.path("canReadBusinessManagement").asBoolean());assertFalse(body.path("canReadOpportunityLedger").asBoolean());assertEquals(1,body.path("businessManagementViews").size());assertEquals("transfer",body.path("businessManagementViews").get(0).asString());
                try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{sql(x,"update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='TEST',revision=revision+1 where tenant_id=? and authority_grant_id=?",seed.tenant(),grant);return null;});}
                var revoked=client.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(200,revoked.statusCode(),revoked.body());assertFalse(mapper.readTree(revoked.body()).path("canReadBusinessManagement").asBoolean());assertEquals(0,mapper.readTree(revoked.body()).path("businessManagementViews").size());
            }
        }
    }
}
