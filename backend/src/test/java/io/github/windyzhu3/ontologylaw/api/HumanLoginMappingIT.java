package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.testing.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.api.security.ActorContextResolver;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;

class HumanLoginMappingIT extends PostgresIntegrationTest {
    private KeycloakFixture idp;
    @BeforeAll void startIdentity()throws Exception{idp=new KeycloakFixture().start();}
    @AfterAll void stopIdentity(){if(idp!=null)idp.close();}
    @Test void newly_bound_real_oidc_user_can_authenticate_without_a_per_person_registration_or_restart()throws Exception {
        // Break caught: enumerating static Actor registrations prevents a new valid HUMAN binding from logging in.
        var seed=AuthorizationServiceIT.seed(database);byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);
        var subjects=new ExternalSubjectProtection(tenant->key);
        {
            var verifier=io.github.windyzhu3.ontologylaw.api.security.HumanCredentialVerifier.isolatedLoopback(List.of(new io.github.windyzhu3.ontologylaw.api.security.HumanCredentialVerifier.Trust(idp.issuer(),KeycloakFixture.AUDIENCE,"TASK92",seed.tenant(),KeycloakFixture.AUDIENCE,idp.introspectionSecret)));
            var resolver=new ActorContextResolver(database::apiConnection,subjects,verifier);
            var login=idp.login();
            var introspection=idp.post(idp.issuer()+"/protocol/openid-connect/token/introspect",Map.of("client_id",KeycloakFixture.AUDIENCE,"client_secret",idp.introspectionSecret,"token",login.accessToken()));
            assertEquals(200,introspection.statusCode());
            var inspected=tools.jackson.databind.json.JsonMapper.builder().build().readTree(introspection.body());
            assertTrue(inspected.path("active").asBoolean(),"Real credential must be active before dynamic mapping");
            assertTrue(inspected.has("sub"),"Introspection subject claim present");assertTrue(inspected.has("iss"),"Introspection issuer claim present");
            UUID principal=UUID.randomUUID(),appointment=UUID.randomUUID();
            try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
                sql(x,"insert into identity.principal (tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) values (?,?,'HUMAN','TASK92',?,'Synthetic new user','ACTIVE',clock_timestamp())",seed.tenant(),principal,subjects.digest(seed.tenant(),login.subject()));
                sql(x,"insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'INTAKE_OPERATOR',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),appointment,principal,seed.org());return null;});}
            var actor=assertDoesNotThrow(()->resolver.bearer(login.accessToken()));
            assertEquals(principal,actor.principalId());assertEquals(appointment,actor.appointmentId());
        }
    }
    @Test void valid_signature_never_substitutes_for_current_idp_activity_or_availability()throws Exception {
        // Break caught: retaining an active=true result accepts a revoked credential, or IdP outage fails open.
        var seed=AuthorizationServiceIT.seed(database);
        var trust=new io.github.windyzhu3.ontologylaw.api.security.HumanCredentialVerifier.Trust(idp.issuer(),KeycloakFixture.AUDIENCE,"TASK92",seed.tenant(),KeycloakFixture.AUDIENCE,idp.introspectionSecret);
        var verifier=io.github.windyzhu3.ontologylaw.api.security.HumanCredentialVerifier.isolatedLoopback(List.of(trust));
        var login=idp.login();assertDoesNotThrow(()->verifier.verify(login.accessToken()));
        idp.unavailable(()->assertThrows(org.springframework.security.authentication.AuthenticationServiceException.class,()->verifier.verify(login.accessToken())));
        assertDoesNotThrow(()->verifier.verify(login.accessToken()));
        idp.logout(login);
        assertThrows(org.springframework.security.authentication.BadCredentialsException.class,()->verifier.verify(login.accessToken()));
    }
    @Test void wrong_audience_unknown_issuer_tampered_signature_and_unmapped_subject_fail_closed()throws Exception {
        var seed=AuthorizationServiceIT.seed(database);var login=idp.login();
        var trust=new io.github.windyzhu3.ontologylaw.api.security.HumanCredentialVerifier.Trust(idp.issuer(),KeycloakFixture.AUDIENCE,"TASK92",seed.tenant(),KeycloakFixture.AUDIENCE,idp.introspectionSecret);
        var verifier=io.github.windyzhu3.ontologylaw.api.security.HumanCredentialVerifier.isolatedLoopback(List.of(trust));
        var resolver=new ActorContextResolver(database::apiConnection,new ExternalSubjectProtection(t->new byte[32]),verifier);
        assertThrows(org.springframework.security.authentication.BadCredentialsException.class,()->resolver.human(login.accessToken()));
        var wrongAudience=io.github.windyzhu3.ontologylaw.api.security.HumanCredentialVerifier.isolatedLoopback(List.of(new io.github.windyzhu3.ontologylaw.api.security.HumanCredentialVerifier.Trust(idp.issuer(),"wrong-audience","TASK92",seed.tenant(),"wrong-audience",idp.introspectionSecret)));
        assertThrows(org.springframework.security.authentication.BadCredentialsException.class,()->wrongAudience.verify(login.accessToken()));
        var wrongIssuer=io.github.windyzhu3.ontologylaw.api.security.HumanCredentialVerifier.isolatedLoopback(List.of(new io.github.windyzhu3.ontologylaw.api.security.HumanCredentialVerifier.Trust(idp.issuer().replace("/realms/task92","/realms/other"),KeycloakFixture.AUDIENCE,"TASK92",seed.tenant(),KeycloakFixture.AUDIENCE,idp.introspectionSecret)));
        assertThrows(org.springframework.security.authentication.BadCredentialsException.class,()->wrongIssuer.verify(login.accessToken()));
        int signature=login.accessToken().lastIndexOf('.')+1;String altered=login.accessToken().substring(0,signature)+(login.accessToken().charAt(signature)=='A'?'B':'A')+login.accessToken().substring(signature+1);
        assertThrows(org.springframework.security.authentication.BadCredentialsException.class,()->verifier.verify(altered));
    }
    @Test void production_trust_rejects_http_and_duplicate_realm_tenant_mapping()throws Exception {
        var first=new io.github.windyzhu3.ontologylaw.api.security.HumanCredentialVerifier.Trust(idp.issuer(),KeycloakFixture.AUDIENCE,"TASK92",UUID.randomUUID(),KeycloakFixture.AUDIENCE,idp.introspectionSecret);
        assertThrows(IllegalArgumentException.class,()->new io.github.windyzhu3.ontologylaw.api.security.HumanCredentialVerifier(List.of(first)));
        var duplicate=new io.github.windyzhu3.ontologylaw.api.security.HumanCredentialVerifier.Trust(first.issuer(),first.audience(),first.provider(),UUID.randomUUID(),first.introspectionClientId(),first.introspectionSecret());
        assertThrows(IllegalArgumentException.class,()->io.github.windyzhu3.ontologylaw.api.security.HumanCredentialVerifier.isolatedLoopback(List.of(first,duplicate)));
        assertThrows(IllegalArgumentException.class,()->new io.github.windyzhu3.ontologylaw.api.security.HumanCredentialVerifier(List.of()));
    }
    @Test void real_short_lived_access_token_expires_and_an_already_used_authorization_code_cannot_be_replayed()throws Exception {
        var verifier=io.github.windyzhu3.ontologylaw.api.security.HumanCredentialVerifier.isolatedLoopback(List.of(new io.github.windyzhu3.ontologylaw.api.security.HumanCredentialVerifier.Trust(idp.issuer(),KeycloakFixture.AUDIENCE,"TASK92",UUID.randomUUID(),KeycloakFixture.AUDIENCE,idp.introspectionSecret)));
        var login=idp.shortLivedLogin();assertDoesNotThrow(()->verifier.verify(login.accessToken()));
        var expiry=com.nimbusds.jwt.SignedJWT.parse(login.accessToken()).getJWTClaimsSet().getExpirationTime().toInstant();
        assertTrue(expiry.isBefore(java.time.Instant.now().plusSeconds(5)),"Isolated expiry client must issue an actually short-lived credential");
        while(!java.time.Instant.now().isAfter(expiry.plusMillis(20)))Thread.sleep(20);
        assertThrows(org.springframework.security.authentication.BadCredentialsException.class,()->verifier.verify(login.accessToken()));
        var ordinary=idp.login();
        var replay=idp.post(idp.issuer()+"/protocol/openid-connect/token",Map.of("grant_type","authorization_code","client_id",KeycloakFixture.CLIENT,"redirect_uri",KeycloakFixture.REDIRECT,"code",ordinary.code(),"code_verifier",ordinary.verifier()));
        assertEquals(400,replay.statusCode(),"A used authorization code is rejected without emitting its value or response body");
    }
    @Test void real_authorization_rejects_wrong_password_disabled_account_unlisted_redirect_and_missing_pkce()throws Exception {
        assertThrows(IllegalStateException.class,()->idp.wrongPasswordLogin(),"Wrong password must not obtain a token");
        assertThrows(IllegalStateException.class,()->idp.disabledAccountLogin(),"Disabled account must not obtain a token");
        var unlisted=idp.invalidAuthorizationRequest(true);assertEquals(400,unlisted.status());assertFalse(unlisted.fixedRedirect());assertTrue(unlisted.codeAbsent());
        var missingPkce=idp.invalidAuthorizationRequest(false);assertEquals(302,missingPkce.status());assertTrue(missingPkce.fixedRedirect());assertTrue(missingPkce.codeAbsent());assertEquals("invalid_request",missingPkce.error());
    }
}
