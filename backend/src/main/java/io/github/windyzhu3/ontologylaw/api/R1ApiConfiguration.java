package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.api.security.ActorContextResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods=false)
@ConditionalOnMissingBean({R1ApiServices.class,ActorContextResolver.class})
class R1ApiConfiguration {
    @Bean R1ApiDeployment r1ApiDeployment(Environment environment){return R1ApiDeployment.from(environment);}
    @Bean ActorContextResolver actorContextResolver(R1ApiDeployment deployment){return deployment.actors;}
    @Bean R1ApiServices r1ApiServices(R1ApiDeployment deployment){return deployment.services;}
    @Bean SessionContextController.Services sessionContextServices(R1ApiDeployment deployment){return deployment.session;}
    @Bean IdentityAdminController.Services identityAdminServices(R1ApiDeployment deployment){return deployment.identities;}
    @Bean ApiRuntimeHealth apiRuntimeHealth(R1ApiDeployment deployment,org.springframework.context.ApplicationContext context){return new ApiRuntimeHealth(deployment.database,context,deployment.humans::healthy);}
    @Bean ApiAvailability applicationAvailability(ApiRuntimeHealth health){return new ApiAvailability(health);}
}
