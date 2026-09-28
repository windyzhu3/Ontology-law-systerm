package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.lead.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.PrincipalKind;
import io.github.windyzhu3.ontologylaw.responsibility.TaskFactory;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LeadIntakeSourcesHttpIT extends R1HttpFixture {
    static final String PATH = "/api/v1/leads/intake-sources";
    void setupIntake() throws Exception {
        policies = new R1SourcePolicyRegistry(Map.of("FIXTURE", new R1SourcePolicyRegistry.SourcePolicy(R1SourcePolicyRegistry.AssignmentMode.MANUAL,List.of("ROOT"),"ROOT","ROOT","Asia/Shanghai")));
        setupFlow(TaskFactory.Type.COMPLETE_LEAD_INGRESS);
        intakeSources = List.of(new LeadIntakeSources.Source("FIXTURE","客户转介绍","MANUAL","CONSULTATION","CN","NORMAL"));
        mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,'LEAD_CAPTURE',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),seed.appointment(),seed.appointment(),seed.org());
    }
    @Test void authenticated_catalog_is_no_store_and_revocation_removes_the_source() throws Exception {
        setupIntake();
        try(var http = new HttpHarness()) {
            var before = counts();
            var response = http.request("GET",PATH,null,Map.of());
            assertEquals(200,response.statusCode(),response.body());
            assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals(List.of(Map.of("sourceAccountCode","FIXTURE","displayName","客户转介绍","sourceChannelCode","MANUAL","serviceCategoryCode","CONSULTATION","jurisdictionCode","CN","urgencyCode","NORMAL")),http.body(response).get("sources"));
            assertEquals(before,counts());
            var malformed = http.request("GET",PATH,null,Map.of("X-Appointment-Id","not-a-uuid"));
            assertEquals(400,malformed.statusCode(),malformed.body());
            assertEquals("no-store",malformed.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals("VALIDATION_FAILED",http.body(malformed).get("code"));
            mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='TEST',revision=revision+1 where tenant_id=? and authority_code='LEAD_CAPTURE'",seed.tenant());
            var revoked = http.request("GET",PATH,null,Map.of());
            assertEquals(200,revoked.statusCode(),revoked.body());
            assertEquals(List.of(),http.body(revoked).get("sources"));
        }
    }
    @Test void no_credentials_and_service_actor_cannot_read_human_intake_catalog() throws Exception {
        setupIntake();
        var service = credentialActor(PrincipalKind.SERVICE,"source-service","LEAD_CAPTURE");
        try(var http = new HttpHarness(service,"source-service",List.of())) {
            assertEquals(403,http.request("GET",PATH,null,Map.of()).statusCode());
            var anonymous = http.client.send(HttpRequest.newBuilder(http.origin.resolve(PATH)).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(401,anonymous.statusCode());
        }
    }
    @Test void unconfigured_catalog_is_empty_and_connection_failure_is_closed() throws Exception {
        setupIntake(); intakeSources = List.of();
        try(var http = new HttpHarness()) {
            var response = http.request("GET",PATH,null,Map.of());
            assertEquals(200,response.statusCode(),response.body());
            assertEquals(List.of(),http.body(response).get("sources"));
        }
        disclosureConnection = c -> { try { c.close(); } catch(Exception ignored) {} throw new IllegalStateException("fixture private failure"); };
        try(var http = new HttpHarness()) {
            var response = http.request("GET",PATH,null,Map.of());
            assertEquals(503,response.statusCode(),response.body());
            assertFalse(response.body().contains("fixture private failure"));
        }
    }
}
