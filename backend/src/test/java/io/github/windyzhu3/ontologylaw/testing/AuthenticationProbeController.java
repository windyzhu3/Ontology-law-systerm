package io.github.windyzhu3.ontologylaw.testing;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Only explicitly installed by authentication ITs; not a production operation/service substitute. */
@RestController
public final class AuthenticationProbeController {
    @GetMapping({"/api/v1/authentication-fixture","/internal/v1/authentication-fixture"})
    public Map<String,String> authenticatedKind(){return Map.of("kind",((Actor)SecurityContextHolder.getContext().getAuthentication().getPrincipal()).principalKind().name());}
}
