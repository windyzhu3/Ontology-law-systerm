package io.github.windyzhu3.ontologylaw.bootstrap;

import io.github.windyzhu3.ontologylaw.OntologyLawApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeWebRoleTest {
    @Test void worker_default_is_nonweb_before_context_creation() {
        var application=new SpringApplication(OntologyLawApplication.class);
        new RuntimeWebRoleEnvironment().postProcessEnvironment(new MockEnvironment().withProperty("ols.runtime-role","worker"),application);
        assertEquals(WebApplicationType.NONE,application.getWebApplicationType());
    }
    @Test void worker_explicit_servlet_cannot_start_a_second_http_surface() {
        assertThrows(IllegalStateException.class,()->new RuntimeWebRoleEnvironment().postProcessEnvironment(new MockEnvironment().withProperty("ols.runtime-role","worker").withProperty("spring.main.web-application-type","servlet"),new SpringApplication(OntologyLawApplication.class)));
    }
    @Test void api_is_servlet_and_cannot_disable_its_production_gate_with_none() {
        var application=new SpringApplication(OntologyLawApplication.class);application.setWebApplicationType(WebApplicationType.NONE);
        new RuntimeWebRoleEnvironment().postProcessEnvironment(new MockEnvironment().withProperty("ols.runtime-role","api"),application);assertEquals(WebApplicationType.SERVLET,application.getWebApplicationType());
        assertThrows(IllegalStateException.class,()->new RuntimeWebRoleEnvironment().postProcessEnvironment(new MockEnvironment().withProperty("ols.runtime-role","api").withProperty("spring.main.web-application-type","none"),application));
    }
}
