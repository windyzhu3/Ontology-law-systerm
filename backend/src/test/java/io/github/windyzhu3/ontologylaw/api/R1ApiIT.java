package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.OntologyLawApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import static org.junit.jupiter.api.Assertions.*;

class R1ApiIT extends R1HttpFixture {
    @Test void public_http_without_bearer_returns_safe_401_challenge() throws Exception {
        setupContact();try(var http=new HttpHarness()) {
            var response = http.client.send(HttpRequest.newBuilder(http.origin.resolve("/api/v1/workcards/current")).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(401, response.statusCode());
            assertEquals("Bearer", response.headers().firstValue("WWW-Authenticate").orElseThrow());
            assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/problem+json"));
            assertTrue(response.body().contains("UNAUTHENTICATED"));
            assertFalse(response.body().contains("exception"));
        }
    }
}
