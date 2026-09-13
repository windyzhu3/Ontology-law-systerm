package io.github.windyzhu3.ontologylaw.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

/** Early assembly selection only; no Owner, credentials or database responsibilities. */
public final class RuntimeWebRoleEnvironment implements EnvironmentPostProcessor {
    public void postProcessEnvironment(ConfigurableEnvironment environment,SpringApplication application) {
        var role=RuntimeRoleConfiguration.resolveRole(environment);
        String required=role==RuntimeRole.API?"servlet":"none";
        for(var source:environment.getPropertySources()) {
            if(org.springframework.boot.context.properties.source.ConfigurationPropertySources.isAttachedConfigurationPropertySource(source))continue;
            var configured=source.getProperty("spring.main.web-application-type");
            if(configured!=null&&!required.equals(configured.toString()))throw new IllegalStateException("Runtime role and web application type conflict");
        }
        application.setWebApplicationType(role==RuntimeRole.API?org.springframework.boot.WebApplicationType.SERVLET:org.springframework.boot.WebApplicationType.NONE);
        environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("r1RuntimeWebAssembly",java.util.Map.of("spring.main.web-application-type",required)));
    }
}
