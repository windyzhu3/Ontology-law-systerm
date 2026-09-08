package io.github.windyzhu3.ontologylaw.api.security;

import com.sun.net.httpserver.HttpServer;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import java.net.*;
import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Controlled transport fault, NOT evidence of a real IdP login. */
class HumanCredentialTransportTest {
    @ParameterizedTest @ValueSource(strings={"JWKS_SLOW","INTROSPECTION_SLOW","JWKS_OVERSIZED","INTROSPECTION_OVERSIZED"})
    void a_partial_or_oversized_remote_body_is_bounded_and_unavailable(String defect)throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(executor);
        var pair=KeyPairGenerator.getInstance("RSA");pair.initialize(2048);var key=pair.generateKeyPair();
        var publicJwk=new com.nimbusds.jose.jwk.RSAKey.Builder((RSAPublicKey)key.getPublic()).keyID("transport-only").keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE).build();
        server.createContext("/realms/transport/protocol/openid-connect/",exchange->{
            try(exchange){boolean jwks=exchange.getRequestURI().getPath().endsWith("/certs");
                if(jwks&&defect.startsWith("INTROSPECTION")){byte[] body=new com.nimbusds.jose.jwk.JWKSet(publicJwk).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);return;}
                exchange.sendResponseHeaders(200,0);
                if(defect.endsWith("OVERSIZED")){exchange.getResponseBody().write(new byte[65537]);return;}
                exchange.getResponseBody().write('{');exchange.getResponseBody().flush();
                try{Thread.sleep(4000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            }
        });server.start();
        try {
            String issuer="http://127.0.0.1:"+server.getAddress().getPort()+"/realms/transport";
            var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("transport-only").build(),new JWTClaimsSet.Builder().issuer(issuer).audience("api").subject("synthetic").expirationTime(Date.from(Instant.now().plusSeconds(60))).build());jwt.sign(new RSASSASigner((RSAPrivateKey)key.getPrivate()));
            var verifier=HumanCredentialVerifier.isolatedLoopback(List.of(new HumanCredentialVerifier.Trust(issuer,"api","TRANSPORT",UUID.randomUUID(),"api","synthetic-secret")));
            long start=System.nanoTime();
            var failure=assertThrows(org.springframework.security.core.AuthenticationException.class,()->verifier.verify(jwt.serialize()));
            long elapsed=Duration.ofNanos(System.nanoTime()-start).toMillis();
            assertAll(()->assertTrue(elapsed<2700,"A partial body must be cancelled within the complete remote-check deadline"),
                    ()->assertInstanceOf(org.springframework.security.authentication.AuthenticationServiceException.class,failure));
        }finally{server.stop(0);executor.shutdownNow();executor.close();}
    }
}
