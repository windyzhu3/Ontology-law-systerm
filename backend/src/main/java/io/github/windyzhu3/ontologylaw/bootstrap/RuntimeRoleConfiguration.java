package io.github.windyzhu3.ontologylaw.bootstrap;

import io.github.windyzhu3.ontologylaw.api.ApiRuntimeAssembly;
import io.github.windyzhu3.ontologylaw.worker.WorkerRuntimeAssembly;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportSelector;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.type.AnnotationMetadata;

@Configuration(proxyBeanMethods = false)
@Import(RuntimeRoleConfiguration.RuntimeRoleImportSelector.class)
public class RuntimeRoleConfiguration {
    private static final AtomicReference<RuntimeRole> ACTIVE_ROLE = new AtomicReference<>();

    @Bean(destroyMethod = "close")
    RuntimeRoleLease runtimeRoleLease(RuntimeRoleSelection selection) {
        RuntimeRole role = selection.role();
        if (!ACTIVE_ROLE.compareAndSet(null, role)) {
            throw new IllegalStateException(
                    "only one runtime role Context may be active in the JVM; active=" + ACTIVE_ROLE.get());
        }
        return new RuntimeRoleLease(role);
    }

    static final class RuntimeRoleImportSelector implements ImportSelector, EnvironmentAware {
        private ConfigurableEnvironment environment;

        @Override
        public void setEnvironment(Environment environment) {
            if (!(environment instanceof ConfigurableEnvironment configurableEnvironment)) {
                throw new IllegalStateException("a configurable Spring Environment is required");
            }
            this.environment = configurableEnvironment;
        }

        @Override
        public String[] selectImports(AnnotationMetadata importingClassMetadata) {
            RuntimeRole role = resolveRole(environment);
            String assembly = role == RuntimeRole.API
                    ? ApiRuntimeAssembly.class.getName()
                    : WorkerRuntimeAssembly.class.getName();
            return new String[] {RuntimeRoleSelectionConfiguration.class.getName(), assembly};
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RuntimeRoleSelectionConfiguration {
        @Bean
        RuntimeRoleSelection runtimeRoleSelection(Environment environment) {
            return new RuntimeRoleSelection(resolveRole((ConfigurableEnvironment) environment));
        }
    }

    static RuntimeRole resolveRole(ConfigurableEnvironment environment) {
        Set<RuntimeRole> configuredRoles = new LinkedHashSet<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (ConfigurationPropertySources.isAttachedConfigurationPropertySource(source)) {
                continue;
            }
            if (!(source instanceof SystemEnvironmentPropertySource)
                    && source.containsProperty("OLS_RUNTIME_ROLE")) {
                throw new IllegalStateException(
                        "non-environment property sources must use canonical key ols.runtime-role");
            }
            addValue(source.getProperty("ols.runtime-role"), configuredRoles);
        }
        if (configuredRoles.isEmpty()) {
            throw new IllegalStateException("ols.runtime-role is required");
        }
        if (configuredRoles.size() != 1) {
            throw new IllegalStateException(
                    "conflicting ols.runtime-role values from multiple property sources: " + configuredRoles);
        }
        return configuredRoles.iterator().next();
    }

    private static void addValue(Object rawValue, Set<RuntimeRole> configuredRoles) {
        if (rawValue == null) {
            return;
        }
        configuredRoles.add(RuntimeRole.parse(rawValue.toString()));
    }

    record RuntimeRoleSelection(RuntimeRole role) {}

    static final class RuntimeRoleLease implements AutoCloseable {
        private final RuntimeRole role;
        private boolean closed;

        private RuntimeRoleLease(RuntimeRole role) {
            this.role = role;
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                ACTIVE_ROLE.compareAndSet(role, null);
                closed = true;
            }
        }
    }
}
