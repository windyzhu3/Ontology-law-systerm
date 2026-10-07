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
            assertFalse(http.body(response).containsKey("sourceSelection"));
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
    @Test void bound_http_catalog_filters_other_accounts_and_does_not_mutate_facts() throws Exception {
        checkBoundCatalog();
    }
    @Test void full_sales_runtime_also_checks_the_bound_source_at_write() throws Exception {
        opportunityProtection=io.github.windyzhu3.ontologylaw.opportunity.OpportunityProgressProtection.aesGcm(tenant->new javax.crypto.spec.SecretKeySpec(new byte[32],"AES"));
        try{checkBoundCatalog();}finally{opportunityProtection=null;}
    }
    private void checkBoundCatalog() throws Exception {
        setupIntake();var policy=policies.find("FIXTURE");policies=new R1SourcePolicyRegistry(Map.of("FIXTURE",policy,"OTHER",policy));
        intakeSources=List.of(intakeSources.getFirst(),new LeadIntakeSources.Source("OTHER","其他来源","MANUAL","CONSULTATION","CN","NORMAL"));
        humanIntakeBindings=new R1HumanSourceBinding(List.of(new R1HumanSourceBinding.Entry(seed.tenant(),seed.request().actor().principalId(),"FIXTURE")),policies);
        try(var http=new HttpHarness()) {
            var before=counts();var response=http.request("GET",PATH,null,Map.of());
            assertEquals(200,response.statusCode(),response.body());
            assertEquals(1,((List<?>)http.body(response).get("sources")).size());
            assertEquals("BOUND_TO_PRINCIPAL",http.body(response).get("sourceSelection"));
            assertFalse(response.body().contains("其他来源"));assertEquals(before,counts());
            var leadCount=scalar("select count(*) from lead.lead where tenant_id=?",seed.tenant());
            var taskCount=scalar("select count(*) from responsibility.task_occurrence where tenant_id=?",seed.tenant());
            var forged=new TreeMap<String,Object>(input(false));forged.put("sourceAccountCode","OTHER");forged.put("sourceRecordKey","forged-http-source");
            var rejected=http.request("POST","/api/v1/leads",forged,Map.of("Idempotency-Key",UUID.randomUUID().toString()));
            assertEquals(403,rejected.statusCode(),rejected.body());assertEquals("NOT_AUTHORIZED",http.body(rejected).get("code"));
            assertEquals(leadCount,scalar("select count(*) from lead.lead where tenant_id=?",seed.tenant()));
            assertEquals(taskCount,scalar("select count(*) from responsibility.task_occurrence where tenant_id=?",seed.tenant()));
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
