package io.github.windyzhu3.ontologylaw.api.security;

import io.github.windyzhu3.ontologylaw.testing.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.OntologyLawApplication;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.security.authentication.BadCredentialsException;

class ClientCertificateIT extends PostgresIntegrationTest {
    @TempDir Path directory;
    @Test void real_tls_certificate_is_the_only_internal_credential_and_public_routes_still_require_bearer() throws Exception {
        var tls=new TlsFixture(directory);var server=tls.key("server");var worker=tls.key("worker");var unmapped=tls.key("unmapped");var rogue=tls.key("rogue");
        var serverTrust=tls.trust("server-trust",worker,unmapped);var clientTrust=tls.trust("client-trust",server);
        var seed=AuthorizationServiceIT.seed(database,"SERVICE");var actor=new AuthorizationService.Actor(seed.tenant(),seed.principal(),seed.appointment(),null,null,AuthorizationService.PrincipalKind.SERVICE);
        var key=KeyPairGenerator.getInstance("RSA");key.initialize(2048);var publicKey=(RSAPublicKey)key.generateKeyPair().getPublic();
        var resolver=new ActorContextResolver(database::apiConnection,new ExternalSubjectProtection(t->new byte[32]),List.of(new ActorContextResolver.Trust("https://test.example","r1",publicKey)),List.of(new ActorContextResolver.Registration("https://test.example","r1","FIXTURE",actor)),List.of(new ActorContextResolver.CertificateRegistration(tls.sha256(worker),"FIXTURE",actor)));
        assertEquals(actor,assertDoesNotThrow(()->resolver.certificate(tls.chain(worker))));
        try(var context=new SpringApplicationBuilder(OntologyLawApplication.class,AuthenticationProbeController.class).initializers(c->((GenericApplicationContext)c).registerBean(ActorContextResolver.class,()->resolver))
                .properties("ols.runtime-role=api","server.port=0","spring.main.banner-mode=off","logging.level.root=OFF",
                        "server.ssl.key-store="+server.path(),"server.ssl.key-store-password="+new String(tls.password),"server.ssl.key-store-type=PKCS12",
                        "server.ssl.trust-store="+serverTrust.path(),"server.ssl.trust-store-password="+new String(tls.password),"server.ssl.trust-store-type=PKCS12","server.ssl.client-auth=want").run();
            var client=HttpClient.newBuilder().sslContext(tls.client(worker,clientTrust)).build();var noCert=HttpClient.newBuilder().sslContext(tls.client(null,clientTrust)).build();var unregistered=HttpClient.newBuilder().sslContext(tls.client(unmapped,clientTrust)).build();var untrusted=HttpClient.newBuilder().sslContext(tls.forcedClient(rogue,clientTrust)).build()) {
            int port=context.getEnvironment().getRequiredProperty("local.server.port",Integer.class);var internal=URI.create("https://localhost:"+port+"/internal/v1/authentication-fixture");
            var response=client.send(HttpRequest.newBuilder(internal).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode());assertEquals("{\"kind\":\"SERVICE\"}",response.body());
            for(var denied:List.of(noCert,unregistered)){var result=denied.send(HttpRequest.newBuilder(internal).header("X-SSL-Client-Cert",tls.sha256(worker)).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(401,result.statusCode());assertTrue(result.headers().firstValue("WWW-Authenticate").isEmpty());assertEquals("no-store",result.headers().firstValue("Cache-Control").orElseThrow());}
            assertThrows(java.io.IOException.class,()->untrusted.send(HttpRequest.newBuilder(internal).GET().build(),HttpResponse.BodyHandlers.ofString()));
            var publicResult=client.send(HttpRequest.newBuilder(URI.create("https://localhost:"+port+"/api/v1/workbench/current-card")).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(401,publicResult.statusCode());assertEquals("Bearer",publicResult.headers().firstValue("WWW-Authenticate").orElseThrow());
            assertEquals(actor,assertDoesNotThrow(()->resolver.certificate(tls.chain(worker))));assertThrows(BadCredentialsException.class,()->resolver.certificate(tls.chain(unmapped)));
        }
    }
}
