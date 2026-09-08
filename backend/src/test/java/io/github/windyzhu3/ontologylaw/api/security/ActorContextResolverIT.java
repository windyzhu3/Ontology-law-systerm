package io.github.windyzhu3.ontologylaw.api.security;

import io.github.windyzhu3.ontologylaw.testing.PostgresIntegrationTest;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import static org.junit.jupiter.api.Assertions.*;
import java.security.*;
import java.security.interfaces.*;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import io.github.windyzhu3.ontologylaw.OntologyLawApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.support.GenericApplicationContext;
import java.net.URI;
import java.net.http.*;

class ActorContextResolverIT extends PostgresIntegrationTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"JUST_EXPIRED,-1,-10,401","AT_EXP,0,-10,401","BEFORE_NBF,60,1,401","AT_NBF,60,0,200","VALID,60,-10,200","MISSING_EXP,999,-10,401"})
    void strict_token_time_boundaries_are_decided_by_trusted_clock_over_real_http_with_zero_writes(String variant,long expiration,long notBefore,int expected)throws Exception {
        var registered=registration();var now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);var clock=java.time.Clock.fixed(now,java.time.ZoneOffset.UTC);
        var resolver=new ActorContextResolver(database::apiConnection,new ExternalSubjectProtection(keys::get),List.of(new ActorContextResolver.Trust(ISSUER,AUDIENCE,(RSAPublicKey)key.getPublic())),List.of(registered),List.of(),clock);
        var claims=new JWTClaimsSet.Builder().issuer(ISSUER).audience(AUDIENCE).subject(SUBJECT).notBeforeTime(Date.from(now.plusSeconds(notBefore)));
        if(!variant.equals("MISSING_EXP"))claims.expirationTime(Date.from(now.plusSeconds(expiration)));
        var jwt=new SignedJWT(new JWSHeader(JWSAlgorithm.RS256),claims.build());jwt.sign(new RSASSASigner(key.getPrivate()));
        var before=authenticationCounts();
        try(var context=new SpringApplicationBuilder(OntologyLawApplication.class,io.github.windyzhu3.ontologylaw.testing.AuthenticationProbeController.class).initializers(c->((GenericApplicationContext)c).registerBean(ActorContextResolver.class,()->resolver))
                .properties("ols.runtime-role=api","server.port=0","spring.main.banner-mode=off","logging.level.root=OFF").run();var client=HttpClient.newHttpClient()) {
            var uri=URI.create("http://localhost:"+context.getEnvironment().getRequiredProperty("local.server.port",Integer.class)+"/api/v1/authentication-fixture");
            var response=client.send(HttpRequest.newBuilder(uri).header("Authorization","Bearer "+jwt.serialize()).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(expected,response.statusCode(),variant);if(expected==401){assertEquals("Bearer",response.headers().firstValue("WWW-Authenticate").orElseThrow());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());assertTrue(response.body().contains("UNAUTHENTICATED"));}else assertEquals("{\"kind\":\"HUMAN\"}",response.body());
            assertEquals(before,authenticationCounts());
        }
    }
    private List<Long> authenticationCounts()throws Exception {
        var counts=new ArrayList<Long>();try(var c=database.migratorConnection();var statement=c.createStatement()) {
            for(String table:List.of("execution.command_execution_slot","execution.command_receipt","audit.audit_entry","execution.domain_event","execution.domain_event_outbox","lead.lead","responsibility.task_occurrence"))try(var row=statement.executeQuery("select count(*) from "+table)){assertTrue(row.next());counts.add(row.getLong(1));}
        }return counts;
    }
    static final String ISSUER="https://identity.example.test", AUDIENCE="r1-test", SUBJECT="  Exact-Subject/张  ";
    final KeyPair key=key();
    final Map<UUID,byte[]> keys=new HashMap<>();
    static KeyPair key(){try {var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);return generator.generateKeyPair();}catch(Exception e){throw new AssertionError(e);}}
    ActorContextResolver.Registration registration() throws Exception {
        var s=AuthorizationServiceIT.seed(database);byte[] secret=new byte[32];new SecureRandom().nextBytes(secret);keys.put(s.tenant(),secret);
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));byte[] hmac=mac.doFinal(SUBJECT.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID principal=UUID.randomUUID(),appointment=UUID.randomUUID();
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            sql(x,"insert into identity.principal (tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) values (?,?,'HUMAN','FIXTURE',?,'credential fixture','ACTIVE',clock_timestamp())",s.tenant(),principal,hmac);
            sql(x,"insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'OWNER',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",s.tenant(),appointment,principal,s.org());return null;});}
        return new ActorContextResolver.Registration(ISSUER,AUDIENCE,"FIXTURE",new Actor(s.tenant(),principal,appointment,null,null));
    }
    ActorContextResolver resolver(List<ActorContextResolver.Registration> registrations){return new ActorContextResolver(database::apiConnection,new ExternalSubjectProtection(keys::get),List.of(new ActorContextResolver.Trust(ISSUER,AUDIENCE,(RSAPublicKey)key.getPublic())),registrations);}
    String token(KeyPair signing,String issuer,String audience,String subject,Instant expiration,JWSAlgorithm algorithm,Map<String,Object> claims) throws Exception {
        var builder=new JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject(subject).issueTime(Date.from(Instant.now().minusSeconds(2))).expirationTime(Date.from(expiration));
        claims.forEach(builder::claim);var jwt=new SignedJWT(new JWSHeader.Builder(algorithm).keyID("fixture").build(),builder.build());jwt.sign(new RSASSASigner(signing.getPrivate()));return jwt.serialize();
    }
    String valid(Map<String,Object> claims)throws Exception{return token(key,ISSUER,AUDIENCE,SUBJECT,Instant.now().plusSeconds(120),JWSAlgorithm.RS256,claims);}
    @Test void signed_subject_maps_only_exact_trusted_database_identity_ignoring_forged_authority_claims() throws Exception {
        var registered=registration();var actor=assertDoesNotThrow(()->resolver(List.of(registered)).bearer(valid(Map.of("tenantId",UUID.randomUUID().toString(),"principalId",UUID.randomUUID().toString(),"principalKind","SERVICE","authority","ADMIN","onBehalfAppointmentId",UUID.randomUUID().toString()))));
        assertEquals(registered.actor(),actor);assertEquals(PrincipalKind.HUMAN,actor.principalKind());assertNull(actor.onBehalfAppointmentId());
    }
    @Test void duplicate_subject_in_two_trusted_tenants_is_not_disambiguated_by_claim() throws Exception {
        var first=registration();var second=registration();assertThrows(BadCredentialsException.class,()->resolver(List.of(first,second)).bearer(valid(Map.of("tenantId",first.actor().tenantId().toString()))));
    }
    @Test void cryptographic_and_claim_failures_never_return_an_actor() throws Exception {
        var registered=registration();var resolver=resolver(List.of(registered));
        for(String bad:List.of("garbage",token(key(),ISSUER,AUDIENCE,SUBJECT,Instant.now().plusSeconds(120),JWSAlgorithm.RS256,Map.of()),token(key,"https://wrong.test",AUDIENCE,SUBJECT,Instant.now().plusSeconds(120),JWSAlgorithm.RS256,Map.of()),token(key,ISSUER,"wrong",SUBJECT,Instant.now().plusSeconds(120),JWSAlgorithm.RS256,Map.of()),token(key,ISSUER,AUDIENCE,SUBJECT,Instant.now().minusSeconds(300),JWSAlgorithm.RS256,Map.of()),token(key,ISSUER,AUDIENCE,SUBJECT,Instant.now().plusSeconds(120),JWSAlgorithm.RS512,Map.of()),token(key,ISSUER,AUDIENCE,SUBJECT.trim(),Instant.now().plusSeconds(120),JWSAlgorithm.RS256,Map.of())))assertThrows(BadCredentialsException.class,()->resolver.bearer(bad));
    }
    @Test void database_provider_and_kind_must_exactly_match_deployment_registration() throws Exception {
        var registered=registration();var wrongProvider=new ActorContextResolver.Registration(ISSUER,AUDIENCE,"CHANGED",registered.actor());
        assertThrows(BadCredentialsException.class,()->resolver(List.of(wrongProvider)).bearer(valid(Map.of())));
        var actor=registered.actor();var wrongKind=new ActorContextResolver.Registration(ISSUER,AUDIENCE,"FIXTURE",new Actor(actor.tenantId(),actor.principalId(),actor.appointmentId(),null,null,PrincipalKind.SERVICE));
        assertThrows(BadCredentialsException.class,()->resolver(List.of(wrongKind)).bearer(valid(Map.of())));
    }
    @Test void credential_hmac_uses_exact_verified_utf8_and_tenant_specific_key() throws Exception {
        var first=registration();var second=registration();var protection=new ExternalSubjectProtection(keys::get);
        assertEquals(32,assertDoesNotThrow(()->protection.digest(first.actor().tenantId(),SUBJECT)).length);
        assertFalse(Arrays.equals(protection.digest(first.actor().tenantId(),SUBJECT),protection.digest(second.actor().tenantId(),SUBJECT)));
        assertFalse(Arrays.equals(protection.digest(first.actor().tenantId(),SUBJECT),protection.digest(first.actor().tenantId(),SUBJECT.trim())));
        assertThrows(RuntimeException.class,()->protection.digest(UUID.randomUUID(),SUBJECT));
    }
    @Test void real_http_filter_accepts_only_verified_bearer_and_never_uses_it_for_internal_routes() throws Exception {
        var registered=registration();var resolver=resolver(List.of(registered));
        try(var context=new SpringApplicationBuilder(OntologyLawApplication.class,io.github.windyzhu3.ontologylaw.testing.AuthenticationProbeController.class).initializers(c->((GenericApplicationContext)c).registerBean(ActorContextResolver.class,()->resolver))
                .properties("ols.runtime-role=api","server.port=0","spring.main.banner-mode=off","logging.level.root=OFF").run();var client=HttpClient.newHttpClient()) {
            int port=context.getEnvironment().getRequiredProperty("local.server.port",Integer.class);
            var uri=URI.create("http://localhost:"+port+"/api/v1/authentication-fixture");
            var accepted=client.send(HttpRequest.newBuilder(uri).header("Authorization","Bearer "+valid(Map.of())).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,accepted.statusCode());assertEquals("{\"kind\":\"HUMAN\"}",accepted.body());
            var refused=client.send(HttpRequest.newBuilder(uri).header("Authorization","Bearer invalid").GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(401,refused.statusCode());assertEquals("Bearer",refused.headers().firstValue("WWW-Authenticate").orElseThrow());
            var internal=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/internal/v1/projections/r1/readiness")).header("Authorization","Bearer "+valid(Map.of())).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(401,internal.statusCode());assertTrue(internal.headers().firstValue("WWW-Authenticate").isEmpty());assertEquals("no-store",internal.headers().firstValue("Cache-Control").orElseThrow());
        }
    }
}
