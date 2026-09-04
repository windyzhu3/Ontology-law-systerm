package io.github.windyzhu3.ontologylaw.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.windyzhu3.ontologylaw.OntologyLawApplication;
import io.github.windyzhu3.ontologylaw.api.ApiRuntimeProbe;
import io.github.windyzhu3.ontologylaw.worker.WorkerRuntimeProbe;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

class RuntimeRoleTest {
    @Test
    void missing_role_fails_startup() {
        assertThrows(Exception.class, () -> start());
    }

    @Test
    void unknown_role_fails_startup() {
        assertThrows(Exception.class, () -> start("--ols.runtime-role=unknown"));
    }

    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = {"api,api", "api,", "API", " api", "api "})
    void role_must_be_one_exact_unmodified_value(String value) {
        assertStartupFails("--ols.runtime-role=" + value);
    }

    @Test
    void identical_values_from_independent_sources_are_allowed() {
        try (ConfigurableApplicationContext context = application()
                .properties("ols.runtime-role=api")
                .run("--ols.runtime-role=api")) {
            assertNotNull(context.getBean(ApiRuntimeProbe.class));
        }
    }

    @Test
    void uppercase_command_line_key_is_rejected() {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(
                new SimpleCommandLinePropertySource("commandLine", "--OLS_RUNTIME_ROLE=api"));
        assertThrows(IllegalStateException.class, () -> RuntimeRoleConfiguration.resolveRole(environment));
    }

    @Test
    void uppercase_config_key_is_rejected() {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(
                new MapPropertySource("config", Map.of("OLS_RUNTIME_ROLE", "api")));
        assertThrows(IllegalStateException.class, () -> RuntimeRoleConfiguration.resolveRole(environment));
    }

    @Test
    void uppercase_jvm_system_property_key_is_rejected() {
        Properties properties = new Properties();
        properties.setProperty("OLS_RUNTIME_ROLE", "api");
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(
                new PropertiesPropertySource("systemProperties", properties));
        assertThrows(IllegalStateException.class, () -> RuntimeRoleConfiguration.resolveRole(environment));
    }

    @Test
    void operating_system_environment_mapping_accepts_uppercase_variable() {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                "systemEnvironment", Map.of("OLS_RUNTIME_ROLE", "api")));
        assertEquals(RuntimeRole.API, RuntimeRoleConfiguration.resolveRole(environment));
    }

    @Test
    void conflicting_property_sources_fail_startup() {
        assertThrows(Exception.class, () -> application()
                .properties("ols.runtime-role=worker")
                .run("--ols.runtime-role=api"));
    }

    @Test
    void api_context_scans_only_the_api_assembly() {
        try (ConfigurableApplicationContext context = start("--ols.runtime-role=api")) {
            assertNotNull(context.getBean(ApiRuntimeProbe.class));
            assertThrows(Exception.class, () -> context.getBean(WorkerRuntimeProbe.class));
        }
    }

    @Test
    void worker_context_scans_only_the_worker_assembly() {
        try (ConfigurableApplicationContext context = start("--ols.runtime-role=worker")) {
            assertNotNull(context.getBean(WorkerRuntimeProbe.class));
            assertThrows(Exception.class, () -> context.getBean(ApiRuntimeProbe.class));
        }
    }

    @Test
    void api_and_worker_contexts_cannot_live_in_the_same_jvm_but_release_on_close() {
        ConfigurableApplicationContext api = start("--ols.runtime-role=api");
        try {
            assertThrows(Exception.class, () -> start("--ols.runtime-role=worker"));
        } finally {
            api.close();
        }
        try (ConfigurableApplicationContext worker = start("--ols.runtime-role=worker")) {
            assertNotNull(worker.getBean(WorkerRuntimeProbe.class));
        }
    }

    private ConfigurableApplicationContext start(String... arguments) {
        return application().run(arguments);
    }

    private void assertStartupFails(String... arguments) {
        ConfigurableApplicationContext unexpectedContext = null;
        try {
            unexpectedContext = start(arguments);
            throw new AssertionError("application startup unexpectedly succeeded");
        } catch (Exception expected) {
            // Expected startup rejection.
        } finally {
            if (unexpectedContext != null) {
                unexpectedContext.close();
            }
        }
    }

    private SpringApplicationBuilder application() {
        return new SpringApplicationBuilder(OntologyLawApplication.class)
                .web(WebApplicationType.NONE)
                .logStartupInfo(false)
                .properties("spring.main.banner-mode=off", "logging.level.root=OFF");
    }
}
