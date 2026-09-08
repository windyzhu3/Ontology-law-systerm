package io.github.windyzhu3.ontologylaw.api.security;

import io.github.windyzhu3.ontologylaw.testing.KeycloakFixture;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class KeycloakDirectoryIT {
    @Test void exact_real_enabled_account_is_proven_with_a_separate_read_only_client_that_cannot_create_users()throws Exception {
        try(var idp=new KeycloakFixture().start()) {
            var login=idp.login();var directory=KeycloakDirectoryReader.isolatedLoopback(new KeycloakDirectoryReader.Trust(idp.issuer(),"task92-directory",idp.directorySecret));
            var account=assertDoesNotThrow(()->directory.exact(idp.username));assertTrue(login.subject().equals(account.subject()),"Directory and verified login must identify the same subject (values restricted)");assertEquals(account,directory.enabled(account.subject()));
            assertThrows(org.springframework.security.authentication.BadCredentialsException.class,()->directory.exact("synthetic"),"Fuzzy prefix is not an exact account");
            assertThrows(org.springframework.security.authentication.BadCredentialsException.class,()->directory.exact("absent-exact-account"));
            assertThrows(org.springframework.security.authentication.BadCredentialsException.class,()->directory.exact("synthetic-disabled"));
            assertThrows(org.springframework.security.authentication.BadCredentialsException.class,()->directory.exact("service-account-task92-directory"),"A service account must never become a HUMAN bootstrap candidate");
            var credentials=idp.post(idp.issuer()+"/protocol/openid-connect/token",Map.of("grant_type","client_credentials","client_id","task92-directory","client_secret",idp.directorySecret));assertEquals(200,credentials.statusCode());
            String access=tools.jackson.databind.json.JsonMapper.builder().build().readTree(credentials.body()).path("access_token").asString();
            try(var client=HttpClient.newHttpClient()) {
                var response=client.send(HttpRequest.newBuilder(URI.create(idp.issuer().replace("/realms/","/admin/realms/")+"/users")).header("Authorization","Bearer "+access).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}" )).build(),HttpResponse.BodyHandlers.discarding());assertEquals(403,response.statusCode(),"Directory account must not be allowed to create users");
            }
        }
    }
}
