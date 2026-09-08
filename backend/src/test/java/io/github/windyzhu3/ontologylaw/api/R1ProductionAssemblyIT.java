package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.OntologyLawApplication;
import io.github.windyzhu3.ontologylaw.api.security.ActorContextResolver;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import static org.junit.jupiter.api.Assertions.*;

class R1ProductionAssemblyIT extends R1ProductionFixture {
    @TempDir Path directory;
    @ParameterizedTest @ValueSource(strings={"ols.api.certificates[0].principal-id","ols.api.registrations[0].appointment-id","ols.api.registrations[0].identity-provider-code"})
    void trusted_registration_must_match_the_actual_identity_owner_at_startup(String property)throws Exception {
        setupContact();var deployment=deployment(directory);deployment.api().put(property,property.endsWith("code")?"UNREGISTERED":java.util.UUID.randomUUID().toString());
        var environment=new org.springframework.mock.env.MockEnvironment();environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("testDeployment",deployment.api()));
        var failure=assertThrows(IllegalStateException.class,()->R1ApiDeployment.from(environment));assertEquals("R1_API_CONFIGURATION_UNAVAILABLE",failure.getMessage());assertNull(failure.getCause());
    }
    @Test void complete_deployment_configuration_constructs_real_owner_security_and_http_without_test_bean_replacements()throws Exception {
        setupContact();var deployment=deployment(directory);
        try(var context=new SpringApplicationBuilder(OntologyLawApplication.class).properties(deployment.api()).run();var client=HttpClient.newBuilder().sslContext(deployment.tls().client(null,deployment.clientTrust())).build()) {
            assertNotNull(context.getBean(R1ApiServices.class));assertNotNull(context.getBean(ActorContextResolver.class));assertNotNull(context.getBean(R1ApiDeployment.class));
            assertTrue(context.getBeansOfType(org.springframework.security.core.userdetails.UserDetailsService.class).isEmpty(),"Production must not fabricate a default password-backed identity");
            assertTrue(context.getBean(ApiRuntimeHealth.class).healthy());
            assertTrue(context.getBean(R1ApiDeployment.class).database.healthy());assertTrue(context.getBeansOfType(io.github.windyzhu3.ontologylaw.worker.WorkerRuntimeProbe.class).isEmpty());assertTrue(context.getBeansOfType(io.github.windyzhu3.ontologylaw.worker.InternalApiClient.class).isEmpty());
            var origin=URI.create("https://localhost:"+context.getEnvironment().getRequiredProperty("local.server.port"));
            var response=client.send(HttpRequest.newBuilder(origin.resolve("/api/v1/workcards/current")).header("Authorization","Bearer "+bearer()).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode(),response.body());assertTrue(response.body().contains(current.selector().id().toString()));assertEquals("private, no-cache",response.headers().firstValue("Cache-Control").orElseThrow());
            try(var c=database.migratorConnection()){io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql(c,"update platform_meta.deployment_state set operating_mode='BLOCKED',revision=revision+1,changed_at=clock_timestamp() where deployment_state_key='PRIMARY'");}
            assertFalse(context.getBean(ApiRuntimeHealth.class).healthy());var before=counts();
            var unavailable=client.send(HttpRequest.newBuilder(origin.resolve("/api/v1/workcards/current")).header("Authorization","Bearer "+bearer()).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(503,unavailable.statusCode(),unavailable.body());assertTrue(unavailable.body().contains("SERVICE_UNAVAILABLE"));assertTrue(unavailable.headers().firstValue("WWW-Authenticate").isEmpty());assertTrue(unavailable.headers().firstValue("ETag").isEmpty());assertEquals(before,counts());
        }
    }
}
