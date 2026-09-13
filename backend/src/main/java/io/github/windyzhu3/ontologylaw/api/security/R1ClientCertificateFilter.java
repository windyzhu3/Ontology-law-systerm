package io.github.windyzhu3.ontologylaw.api.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.List;
import io.github.windyzhu3.ontologylaw.api.ProblemDetailsAdvice;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** No forwarded header or Bearer credential can stand in for container-verified mutual TLS. */
final class R1ClientCertificateFilter extends OncePerRequestFilter {
    private final ObjectProvider<ActorContextResolver> resolvers;
    R1ClientCertificateFilter(ObjectProvider<ActorContextResolver> resolvers){this.resolvers=resolvers;}
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        if(request.getRequestURI().startsWith("/internal/v1/")) {
            try {
                var resolver=resolvers.getIfAvailable();var certificates=request.getAttribute("jakarta.servlet.request.X509Certificate");
                if(!request.isSecure()||resolver==null||!(certificates instanceof X509Certificate[] verified))throw new IllegalArgumentException();
                var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(resolver.certificate(verified),null,List.of()));SecurityContextHolder.setContext(context);
            }catch(org.springframework.security.core.AuthenticationException denied){SecurityContextHolder.clearContext();ProblemDetailsAdvice.authenticationFailure(response,false,denied);return;}
            catch(RuntimeException denied){SecurityContextHolder.clearContext();ProblemDetailsAdvice.unauthenticated(response,false);return;}
        }
        chain.doFilter(request,response);
    }
}
