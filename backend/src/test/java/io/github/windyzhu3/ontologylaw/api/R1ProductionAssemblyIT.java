package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.OntologyLawApplication;
import io.github.windyzhu3.ontologylaw.api.security.ActorContextResolver;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.availability.*;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class R1ProductionAssemblyIT extends R1ProductionFixture {
    @TempDir Path directory;
    @Test void active_semantic_assembly_accepts_dynamic_human_realm_without_per_person_registration()throws Exception {
        setupContact();var deployment=deployment(directory);var environment=new org.springframework.mock.env.MockEnvironment();environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("testDeployment",deployment.api()));
        assertDoesNotThrow(()->R1ApiDeployment.from(environment));
    }
    @Test void native_https_identity_profile_also_uses_verified_tls_to_its_isolated_identity_database()throws Exception {
        assertTrue(identityProvider.identityDatabaseConnectionsUseTls(),"All actual Keycloak pool connections must use TLS, independently of the business database");
    }
    @ParameterizedTest @ValueSource(strings={"OLD_BASELINE","WRONG_BASELINE","MISSING_HUMAN","HUMAN_REGISTRATION","RECIPIENT","HTTP","WRONG_CERTIFICATE","WRONG_HOST","WRONG_SECRET","DUPLICATE_REALM","SHARED_SCOPE_KEY"})
    void invalid_human_trust_or_key_configuration_never_becomes_ready(String defect)throws Exception {
        setupContact();var deployment=deployment(directory);var settings=deployment.api();String human="ols.api.human-trusts[0].";
        switch(defect) {
            case "OLD_BASELINE"->settings.put("ols.api.semantic-baseline","MVP-2026-09-08.1");
            case "WRONG_BASELINE"->settings.put("ols.api.semantic-baseline","MVP-2026-09-08.2");
            case "MISSING_HUMAN"->settings.keySet().removeIf(key->key.startsWith("ols.api.human-trusts"));
            case "HUMAN_REGISTRATION"->settings.put("ols.api.registrations[0].principal-kind","HUMAN");
            case "RECIPIENT"->settings.put(human+"introspection-client-id","unrelated-recipient");
            case "HTTP"->settings.put(human+"issuer",identityProvider.issuer().replace("https:","http:"));
            case "WRONG_CERTIFICATE"->{settings.remove("ols.api.identity-trust-store-path");settings.remove("ols.api.identity-trust-store-password-path");}
            case "WRONG_HOST"->settings.put(human+"issuer",identityProvider.issuer().replace("localhost","127.0.0.1"));
            case "WRONG_SECRET"->{var wrong=directory.resolve("wrong.secret");java.nio.file.Files.writeString(wrong,"synthetic-but-not-the-configured-secret");settings.put(human+"introspection-secret-path",wrong.toString());}
            case "DUPLICATE_REALM"->{for(var entry:new java.util.TreeMap<>(settings).entrySet())if(entry.getKey().startsWith(human))settings.put(entry.getKey().replace("[0]","[1]"),entry.getValue());settings.put("ols.api.human-trusts[1].tenant-id",java.util.UUID.randomUUID().toString());}
            case "SHARED_SCOPE_KEY"->{String keys="ols.api.tenant-keys["+seed.tenant()+"].";settings.put(keys+"actor-scope-hmac",settings.get(keys+"credential-subject-hmac"));}
        }
        var environment=new org.springframework.mock.env.MockEnvironment();environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("testDeployment",settings));
        var failure=assertThrows(IllegalStateException.class,()->R1ApiDeployment.from(environment));assertEquals("R1_API_CONFIGURATION_UNAVAILABLE",failure.getMessage());assertNull(failure.getCause());
    }
    @Test void closed_offline_command_requires_confirmation_and_uses_real_https_directory_without_an_http_bootstrap_endpoint()throws Exception {
        setupContact();var deployment=deployment(directory);var api=deployment.api();var tenant=java.util.UUID.randomUUID();var config=new java.util.TreeMap<String,Object>();
        config.put("semanticBaseline","MVP-2026-09-08.3");config.put("tenantId",tenant.toString());config.put("tenantCode","CLI"+tenant.toString().replace("-",""));config.put("identityProviderCode","TASK92");config.put("issuer",identityProvider.issuer());config.put("apiAudience",io.github.windyzhu3.ontologylaw.testing.KeycloakFixture.AUDIENCE);config.put("directoryClientId","task92-directory");config.put("operatorAssertion","Approved isolated CLI operator");config.put("node","OFFLINE_CLI_IT");config.put("activeBootstrapKeyId","offline-v1");
        var directorySecret=directory.resolve("directory.secret");var candidateKey=directory.resolve("candidate.key");var subjectKey=directory.resolve("subject.key");var dbSecret=directory.resolve("database.secret");var identifier=directory.resolve("identifier.input");
        java.nio.file.Files.writeString(directorySecret,identityProvider.directorySecret);java.nio.file.Files.writeString(candidateKey,encoded(0x71));java.nio.file.Files.writeString(subjectKey,encoded(0x72));java.nio.file.Files.writeString(dbSecret,database.apiPassword());java.nio.file.Files.writeString(identifier,identityProvider.username);
        config.put("directorySecretPath",directorySecret.toString());config.put("bootstrapKeyPaths",java.util.Map.of("offline-v1",candidateKey.toString()));config.put("subjectHmacPath",subjectKey.toString());config.put("identityTrustStorePath",api.get("ols.api.identity-trust-store-path"));config.put("identityTrustStorePasswordPath",api.get("ols.api.identity-trust-store-password-path"));
        config.put("database",java.util.Map.of("url",database.jdbcUrl(),"username","law_api_login","passwordPath",dbSecret.toString(),"schemaVersion","52-plus-2-v1.2","releaseDigest","11".repeat(32),"manifestHash","22".repeat(32)));
        var configFile=directory.resolve("offline-config.json");java.nio.file.Files.writeString(configFile,mapper.writeValueAsString(config));var stdout=new java.io.ByteArrayOutputStream();var stderr=new java.io.ByteArrayOutputStream();
        try(var out=new java.io.PrintStream(stdout);var errors=new java.io.PrintStream(stderr)) {
            assertEquals(0,IdentityBootstrapCommand.run(new String[]{"candidate",configFile.toString(),identifier.toString()},out,errors));String selector=mapper.readTree(stdout.toByteArray()).path("providerUserSelector").asString();assertFalse(selector.isBlank());assertEquals(0,stderr.size());stdout.reset();
            var manifest=new io.github.windyzhu3.ontologylaw.identity.IdentityBootstrapService.Manifest("R1_IDENTITY_BOOTSTRAP_V1",java.util.UUID.randomUUID(),(String)config.get("tenantCode"),"Synthetic CLI tenant","ROOT","Synthetic root","TASK92",identityProvider.issuer(),selector,"Synthetic founder",java.time.Instant.now().minusSeconds(1),(String)config.get("operatorAssertion"));var manifestFile=directory.resolve("manifest.json");java.nio.file.Files.writeString(manifestFile,mapper.writeValueAsString(manifest));
            assertEquals(0,IdentityBootstrapCommand.run(new String[]{"dry-run",configFile.toString(),manifestFile.toString()},out,errors));assertTrue(stdout.toString().contains("DRY_RUN"));assertEquals("0",scalar("select count(*)::text from identity.tenant where tenant_id=?",tenant));
            var preview=mapper.readTree(stdout.toByteArray()).path("preview");
            assertEquals(tenant.toString(),preview.path("tenant").path("id").asString());assertEquals(manifest.tenantCode(),preview.path("tenant").path("code").asString());assertEquals(manifest.tenantDisplayName(),preview.path("tenant").path("displayName").asString());
            assertEquals(manifest.rootCode(),preview.path("rootOrganization").path("code").asString());assertEquals(manifest.rootDisplayName(),preview.path("rootOrganization").path("displayName").asString());assertEquals(manifest.principalDisplayName(),preview.path("administrator").path("displayName").asString());assertEquals("HUMAN",preview.path("administrator").path("principalKind").asString());assertEquals("IDENTITY_ADMIN",preview.path("appointment").path("roleCode").asString());
            var grants=preview.path("authorityGrants");assertEquals(4,grants.size());var codes=new java.util.HashSet<String>();for(var grant:grants){codes.add(grant.path("authorityCode").asString());assertEquals("DIRECT",grant.path("path").asString());assertEquals("ROOT",grant.path("scope").asString());assertEquals(manifest.rootCode(),grant.path("scopeOrganizationCode").asString());}assertEquals(new java.util.HashSet<>(io.github.windyzhu3.ontologylaw.identity.IdentityBootstrapService.MANAGEMENT_CODES),codes);
            assertFalse(stdout.toString().contains(selector),"Dry-run must not disclose confidential candidate");assertFalse(stdout.toString().contains("subjectHmac"));assertFalse(stdout.toString().contains("providerUserSelector"));assertFalse(stdout.toString().contains(identityProvider.directorySecret),"Dry-run must not disclose directory credentials");stdout.reset();
            assertEquals(2,IdentityBootstrapCommand.run(new String[]{"execute",configFile.toString(),manifestFile.toString()},out,errors));assertEquals("0",scalar("select count(*)::text from identity.tenant where tenant_id=?",tenant));stderr.reset();
            assertEquals(0,IdentityBootstrapCommand.run(new String[]{"execute",configFile.toString(),manifestFile.toString(),"--confirm-bootstrap"},out,errors));assertTrue(stdout.toString().contains("CREATED"));stdout.reset();
            assertEquals(0,IdentityBootstrapCommand.run(new String[]{"verify",configFile.toString(),manifestFile.toString()},out,errors));assertTrue(stdout.toString().contains("VERIFIED_ORIGINAL"));assertEquals(0,stderr.size());assertEquals("4",scalar("select count(*)::text from identity.authority_grant where tenant_id=?",tenant));assertEquals("1",scalar("select count(*)::text from execution.command_receipt where tenant_id=?",tenant));
        }
    }
    @Test void blocked_after_lifecycle_start_cannot_be_overwritten_by_boot_final_ready_event()throws Exception {
        setupContact();var deployment=deployment(directory);
        var schedulerPaused=new CountDownLatch(1);var resumeScheduler=new CountDownLatch(1);
        org.springframework.context.ConfigurableApplicationContext context=null;
        try {
            context=new SpringApplicationBuilder(OntologyLawApplication.class).properties(deployment.api()).initializers(application ->
                ((GenericApplicationContext)application).registerBean("blockDeploymentAfterLifecycleStartFixture",ApplicationRunner.class,()->args -> {
                    var health=application.getBean(ApiRuntimeHealth.class);assertTrue(health.healthy());
                    // Pause periodic self-repair only; ApplicationReadyEvent still refreshes on the startup thread.
                    var scheduler=(ScheduledExecutorService)ReflectionTestUtils.getField(health,"scheduler");
                    assertNotNull(scheduler);scheduler.execute(()->{schedulerPaused.countDown();try {assertTrue(resumeScheduler.await(30,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();}});
                    assertTrue(schedulerPaused.await(10,TimeUnit.SECONDS));deploymentMode("BLOCKED");
                })).run();
            var health=context.getBean(ApiRuntimeHealth.class);var availability=context.getBean(ApplicationAvailability.class);
            assertFalse(health.healthy());
            assertEquals(ReadinessState.REFUSING_TRAFFIC,availability.getReadinessState(),"Boot must not overwrite the actual BLOCKED gate while periodic refresh is stalled");
            try(var client=HttpClient.newBuilder().sslContext(deployment.tls().client(null,deployment.clientTrust())).build()) {
                var origin=URI.create("https://localhost:"+context.getEnvironment().getRequiredProperty("local.server.port"));var before=counts();
                var response=client.send(HttpRequest.newBuilder(origin.resolve("/api/v1/workcards/current")).header("Authorization","Bearer "+bearer()).GET().build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(503,response.statusCode(),response.body());assertEquals(before,counts());
                deploymentMode("ACTIVE");health.applicationReady();assertTrue(health.healthy());assertEquals(ReadinessState.ACCEPTING_TRAFFIC,availability.getReadinessState());
                deploymentMode("BLOCKED");health.applicationReady();assertFalse(health.healthy());assertEquals(ReadinessState.REFUSING_TRAFFIC,availability.getReadinessState());
                deploymentMode("ACTIVE");health.applicationReady();assertTrue(health.healthy());assertEquals(ReadinessState.ACCEPTING_TRAFFIC,availability.getReadinessState());
            }
            resumeScheduler.countDown();context.close();assertFalse(health.healthy());assertEquals(ReadinessState.REFUSING_TRAFFIC,availability.getReadinessState());
        } finally {resumeScheduler.countDown();if(context!=null)context.close();}
    }
    private void deploymentMode(String mode)throws Exception {
        try(var connection=database.migratorConnection()){io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql(connection,"update platform_meta.deployment_state set operating_mode='"+mode+"',revision=revision+1,changed_at=clock_timestamp() where deployment_state_key='PRIMARY'");}
    }
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
            assertEquals(ReadinessState.ACCEPTING_TRAFFIC,context.getBean(ApplicationAvailability.class).getReadinessState());
            assertTrue(context.getBean(R1ApiDeployment.class).database.healthy());assertTrue(context.getBeansOfType(io.github.windyzhu3.ontologylaw.worker.WorkerRuntimeProbe.class).isEmpty());assertTrue(context.getBeansOfType(io.github.windyzhu3.ontologylaw.worker.InternalApiClient.class).isEmpty());
            var origin=URI.create("https://localhost:"+context.getEnvironment().getRequiredProperty("local.server.port"));
            var response=client.send(HttpRequest.newBuilder(origin.resolve("/api/v1/workcards/current")).header("Authorization","Bearer "+bearer()).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode(),response.body());assertTrue(response.body().contains(current.selector().id().toString()));assertEquals("private, no-cache",response.headers().firstValue("Cache-Control").orElseThrow());
            var bootstrapBefore=counts();var bootstrap=client.send(HttpRequest.newBuilder(origin.resolve("/api/v1/admin/identity/bootstrap")).header("Authorization","Bearer "+bearer()).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.discarding());assertEquals(404,bootstrap.statusCode(),"Bootstrap must have no HTTP endpoint even for a real authenticated HUMAN");assertEquals(bootstrapBefore,counts());
            try(var c=database.migratorConnection()){io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql(c,"update platform_meta.deployment_state set operating_mode='BLOCKED',revision=revision+1,changed_at=clock_timestamp() where deployment_state_key='PRIMARY'");}
            assertFalse(context.getBean(ApiRuntimeHealth.class).healthy());var before=counts();
            var unavailable=client.send(HttpRequest.newBuilder(origin.resolve("/api/v1/workcards/current")).header("Authorization","Bearer "+bearer()).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(503,unavailable.statusCode(),unavailable.body());assertTrue(unavailable.body().contains("SERVICE_UNAVAILABLE"));assertTrue(unavailable.headers().firstValue("WWW-Authenticate").isEmpty());assertTrue(unavailable.headers().firstValue("ETag").isEmpty());assertEquals(before,counts());
        }
    }
}
