package io.github.windyzhu3.ontologylaw.api.security;

import io.github.windyzhu3.ontologylaw.api.ProblemDetailsAdvice;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import java.util.List;

/** No anonymous business route exists, including when deployment trust is missing. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class R1SecurityConfiguration {
    @Bean
    SecurityFilterChain r1SecurityFilterChain(HttpSecurity http,ObjectProvider<ActorContextResolver> resolvers) throws Exception {
        var tokens=new DefaultBearerTokenResolver();
        return http.csrf(csrf -> csrf.disable())
                .addFilterBefore(new R1ClientCertificateFilter(resolvers),org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, failure) ->
                        ProblemDetailsAdvice.authenticationFailure(response, request.getRequestURI().startsWith("/api/v1/"),failure)))
                .oauth2ResourceServer(resource->resource
                        .bearerTokenResolver(request->request.getRequestURI().startsWith("/api/v1/")?tokens.resolve(request):null)
                        .authenticationManagerResolver(request->authentication->{
                            var resolver=resolvers.getIfAvailable();if(resolver==null||!(authentication instanceof BearerTokenAuthenticationToken bearer))throw new BadCredentialsException("UNAUTHENTICATED");
                            return UsernamePasswordAuthenticationToken.authenticated(resolver.bearer(bearer.getToken()),null,List.of());
                        })
                        .authenticationEntryPoint((request,response,failure)->ProblemDetailsAdvice.authenticationFailure(response,request.getRequestURI().startsWith("/api/v1/"),failure))
                        .withObjectPostProcessor(new org.springframework.security.config.ObjectPostProcessor<org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter>(){
                            public <O extends org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter> O postProcess(O filter){filter.setAuthenticationFailureHandler((request,response,failure)->ProblemDetailsAdvice.authenticationFailure(response,true,failure));return filter;}
                        }))
                .build();
    }
}
