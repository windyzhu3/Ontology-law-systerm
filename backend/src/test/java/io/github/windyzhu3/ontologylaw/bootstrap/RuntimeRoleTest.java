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

/** Pure role parsing/selection/lease tests. Real production startup is exercised by RuntimeRoleIT. */
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
        var environment=environment("--ols.runtime-role=api");environment.getPropertySources().addLast(new MapPropertySource("configuration",Map.of("ols.runtime-role","api")));
        assertEquals(RuntimeRole.API,RuntimeRoleConfiguration.resolveRole(environment));
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
        var environment=environment("--ols.runtime-role=api");environment.getPropertySources().addLast(new MapPropertySource("configuration",Map.of("ols.runtime-role","worker")));
        assertThrows(IllegalStateException.class,()->RuntimeRoleConfiguration.resolveRole(environment));
    }

    @Test
    void api_role_selects_only_the_api_assembly() {
        var names=assemblies("api");org.junit.jupiter.api.Assertions.assertTrue(names.contains(io.github.windyzhu3.ontologylaw.api.ApiRuntimeAssembly.class.getName()));org.junit.jupiter.api.Assertions.assertFalse(names.contains(io.github.windyzhu3.ontologylaw.worker.WorkerRuntimeAssembly.class.getName()));
    }

    @Test
    void worker_role_selects_only_the_worker_assembly() {
        var names=assemblies("worker");org.junit.jupiter.api.Assertions.assertTrue(names.contains(io.github.windyzhu3.ontologylaw.worker.WorkerRuntimeAssembly.class.getName()));org.junit.jupiter.api.Assertions.assertFalse(names.contains(io.github.windyzhu3.ontologylaw.api.ApiRuntimeAssembly.class.getName()));
    }

    @Test
    void api_and_worker_contexts_cannot_live_in_the_same_jvm_but_release_on_close() {
        var configuration=new RuntimeRoleConfiguration();var api=configuration.runtimeRoleLease(new RuntimeRoleConfiguration.RuntimeRoleSelection(RuntimeRole.API));
        try {
            assertThrows(IllegalStateException.class,()->configuration.runtimeRoleLease(new RuntimeRoleConfiguration.RuntimeRoleSelection(RuntimeRole.WORKER)));
        } finally {
            api.close();
        }
        try(var worker=configuration.runtimeRoleLease(new RuntimeRoleConfiguration.RuntimeRoleSelection(RuntimeRole.WORKER))) {
            assertNotNull(worker);
        }
    }

    private RuntimeRole start(String... arguments) {
        return RuntimeRoleConfiguration.resolveRole(environment(arguments));
    }

    private void assertStartupFails(String... arguments) {
        assertThrows(IllegalStateException.class,()->start(arguments));
    }

    private MockEnvironment environment(String... arguments) {
        var environment=new MockEnvironment();environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource("arguments",arguments));return environment;
    }
    private java.util.List<String> assemblies(String role){var selector=new RuntimeRoleConfiguration.RuntimeRoleImportSelector();selector.setEnvironment(environment("--ols.runtime-role="+role));return java.util.List.of(selector.selectImports(org.springframework.core.type.AnnotationMetadata.introspect(RuntimeRoleConfiguration.class)));}
}
