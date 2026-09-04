package io.github.windyzhu3.ontologylaw;

import io.github.windyzhu3.ontologylaw.bootstrap.RuntimeRoleConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication(
        scanBasePackages = "io.github.windyzhu3.ontologylaw.bootstrap",
        proxyBeanMethods = false)
@Import(RuntimeRoleConfiguration.class)
public class OntologyLawApplication {
    public static void main(String[] args) {
        SpringApplication.run(OntologyLawApplication.class, args);
    }
}
