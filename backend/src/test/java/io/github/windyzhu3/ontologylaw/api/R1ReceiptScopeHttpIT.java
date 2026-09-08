package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.lead.*;
import io.github.windyzhu3.ontologylaw.responsibility.TaskFactory;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class R1ReceiptScopeHttpIT extends R1HttpFixture {
    @Test void original_source_organization_id_cannot_be_replaced_even_by_another_authorized_policy_root()throws Exception {
        setupCapture();UUID key=UUID.randomUUID();try(var http=new HttpHarness()){assertEquals(201,http.request("POST","/api/v1/leads",capture("source-org-id"),Map.of("Idempotency-Key",key.toString())).statusCode());}
        UUID other=UUID.randomUUID();mutate("insert into identity.organization_unit (tenant_id,organization_unit_id,unit_code,display_name,parent_organization_unit_id,state,created_at) values (?,?,'OTHER_ROOT','Other current policy',?,'ACTIVE',clock_timestamp())",seed.tenant(),other,seed.org());
        var original=policies.find("FIXTURE");policies=new R1SourcePolicyRegistry(Map.of("FIXTURE",new R1SourcePolicyRegistry.SourcePolicy(original.assignmentMode(),original.routingOrganizationRootCodes(),original.routingSupervisorRootCode(),"OTHER_ROOT",original.businessTimezone())));
        try(var http=new HttpHarness()){var before=counts();var response=http.request("GET","/api/v1/commands/"+key+"/receipt",null,Map.of());assertEquals(403,response.statusCode(),response.body());assertFalse(response.body().contains("receiptRef"));assertEquals(before,counts());}
    }
    @Test void rejected_capture_without_lead_is_readable_but_later_natural_lead_deny_blocks_it()throws Exception {
        setupCapture();mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_code='LEAD_INGRESS_COMPLETE'",seed.tenant());var payload=capture("rejected-then-later-lead");UUID key=UUID.randomUUID();byte[] natural=protection.hmac(seed.tenant(),LeadProtection.Purpose.SOURCE_RECORD_KEY,"FIXTURE","rejected-then-later-lead");
        try(var http=new HttpHarness()) {
            var rejected=http.request("POST","/api/v1/leads",payload,Map.of("Idempotency-Key",key.toString()));assertEquals(422,rejected.statusCode(),rejected.body());assertEquals("SUPERVISOR_UNRESOLVED",http.body(rejected).get("code"));assertTrue(http.body(rejected).containsKey("receiptRef"));assertEquals("0",scalar("select count(*)::text from lead.lead where tenant_id=? and source_account_code='FIXTURE' and source_record_key_digest=?",seed.tenant(),natural));
            var before=counts();var receipt=http.request("GET","/api/v1/commands/"+key+"/receipt",null,Map.of());assertEquals(200,receipt.statusCode(),receipt.body());assertEquals("REJECTED",http.body(receipt).get("outcome"));assertFalse(http.body(receipt).containsKey("resultFact"));delta(before,0,0,0,0,0,0,0,0,1,0,0);
            Subject lead;try(var c=database.apiConnection()){lead=io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.inTransaction(c,io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.Capability.COMMAND,x->LeadIngressService.databaseBacked(protection).capture(x,seed.tenant(),payload,natural,java.time.Instant.now()).selector());}
            mutate("insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values (?,?,?,?,'LEAD_CAPTURE','DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),'lead.lead',?,?)",seed.tenant(),UUID.randomUUID(),seed.principal(),seed.appointment(),lead.id(),lead.revision());before=counts();var denied=http.request("GET","/api/v1/commands/"+key+"/receipt",null,Map.of());assertEquals(403,denied.statusCode(),denied.body());assertFalse(denied.body().contains("receiptRef"));assertEquals(before,counts());
        }
    }
    void setupCapture()throws Exception {policies=new R1SourcePolicyRegistry(Map.of("FIXTURE",new R1SourcePolicyRegistry.SourcePolicy(R1SourcePolicyRegistry.AssignmentMode.MANUAL,List.of("ROOT"),"ROOT","ROOT","Asia/Shanghai")));setupFlow(TaskFactory.Type.COMPLETE_LEAD_INGRESS);mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,'LEAD_CAPTURE',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),seed.appointment(),seed.appointment(),seed.org());}
    Map<String,Object> capture(String key){var values=new TreeMap<String,Object>(input(false));values.put("sourceRecordKey",key);return values;}
    @Test void cross_scope_and_another_actor_conflict_exposes_only_the_callers_known_key_and_get_remains_original_actor_only()throws Exception {
        setupCapture();UUID key=UUID.randomUUID();String receipt;
        try(var http=new HttpHarness()) {
            var first=http.request("POST","/api/v1/leads",capture("scope-A"),Map.of("Idempotency-Key",key.toString()));assertEquals(201,first.statusCode(),first.body());receipt=(String)http.body(first).get("receiptId");
            var before=counts();var conflict=http.request("POST","/api/v1/leads",capture("scope-B"),Map.of("Idempotency-Key",key.toString()));conflict(http,conflict,key,receipt);assertEquals(before,counts());
        }
        String subject="another verified provider subject";var other=credentialActor(PrincipalKind.HUMAN,subject,"LEAD_CAPTURE");
        try(var http=new HttpHarness(other,subject,List.of())) {
            var before=counts();var conflict=http.request("POST","/api/v1/leads",capture("scope-C"),Map.of("Idempotency-Key",key.toString()));conflict(http,conflict,key,receipt);assertEquals(before,counts());
            var hidden=http.request("GET","/api/v1/commands/"+key+"/receipt?actor="+seed.principal()+"&tenantId="+seed.tenant(),Map.of("principalId",seed.principal().toString()),Map.of());assertEquals(404,hidden.statusCode(),hidden.body());assertFalse(hidden.body().contains(receipt));assertFalse(hidden.body().contains("receiptRef"));assertEquals(before,counts());
        }
        var represented=new Actor(seed.tenant(),seed.principal(),seed.appointment(),other.principalId(),other.appointmentId(),PrincipalKind.HUMAN);
        try(var http=new HttpHarness(represented,SUBJECT,List.of())){var before=counts();var hidden=http.request("GET","/api/v1/commands/"+key+"/receipt",null,Map.of());assertEquals(404,hidden.statusCode(),hidden.body());assertEquals(before,counts());}
    }
    private void conflict(HttpHarness http,java.net.http.HttpResponse<String> response,UUID key,String receipt){assertEquals(409,response.statusCode(),response.body());assertEquals("COMMAND_PAYLOAD_CONFLICT",http.body(response).get("code"));assertEquals(Map.of("commandId",key.toString(),"href","/api/v1/commands/"+key+"/receipt"),http.body(response).get("receiptRef"));assertFalse(response.body().contains(receipt));assertFalse(response.body().contains("SUCCEEDED"));assertFalse(response.body().contains("resultFact"));assertFalse(response.body().contains("scope-A"));}
    @Test void service_capture_and_receipt_require_exact_current_source_binding_not_another_account_in_same_organization()throws Exception {
        setupCapture();policies=new R1SourcePolicyRegistry(Map.of("FIXTURE",policies.find("FIXTURE"),"OTHER",policies.find("FIXTURE")));
        String subject="verified SERVICE subject";var actor=credentialActor(PrincipalKind.SERVICE,subject,"LEAD_CAPTURE");
        var binding=new R1ServiceSourceBinding.Entry(ISSUER,AUDIENCE,"FIXTURE",actor.tenantId(),actor.principalId(),actor.appointmentId(),Set.of("FIXTURE"));UUID key=UUID.randomUUID();var payload=capture("service-exact-source");
        try(var http=new HttpHarness(actor,subject,List.of(binding))) {
            var before=counts();var other=new TreeMap<>(capture("service-other-account"));other.put("sourceAccountCode","OTHER");var denied=http.request("POST","/api/v1/leads",other,Map.of("Idempotency-Key",UUID.randomUUID().toString()));assertEquals(403,denied.statusCode(),denied.body());assertEquals(before,counts());
            var accepted=http.request("POST","/api/v1/leads",payload,Map.of("Idempotency-Key",key.toString()));assertEquals(201,accepted.statusCode(),accepted.body());
            before=counts();var receipt=http.request("GET","/api/v1/commands/"+key+"/receipt",null,Map.of());assertEquals(200,receipt.statusCode(),receipt.body());assertEquals(http.body(accepted),http.body(receipt));delta(before,0,0,0,0,0,0,0,0,1,0,0);
            before=counts();assertEquals(403,http.request("GET","/api/v1/workcards/current",null,Map.of()).statusCode());assertEquals(403,http.request("PUT","/api/v1/tasks/"+current.selector().id()+"/draft",Map.of(),Map.of("Idempotency-Key",UUID.randomUUID().toString(),"If-None-Match","*")).statusCode());
            var internal=http.request("GET","/internal/v1/projections/r1/readiness",null,Map.of());assertEquals(401,internal.statusCode());assertTrue(internal.headers().firstValue("WWW-Authenticate").isEmpty());assertEquals(before,counts());
        }
        // Deployment removes the binding; the same verified credential and generic grant do not retain access.
        try(var http=new HttpHarness(actor,subject,List.of())) {
            var before=counts();var denied=http.request("GET","/api/v1/commands/"+key+"/receipt",null,Map.of());assertEquals(403,denied.statusCode(),denied.body());assertFalse(denied.body().contains("receiptRef"));assertEquals(before,counts());
            assertEquals(403,http.request("POST","/api/v1/leads",payload,Map.of("Idempotency-Key",key.toString())).statusCode());assertEquals(before,counts());
        }
    }
    @Test void capture_receipt_does_not_migrate_to_a_changed_source_organization_revision()throws Exception {
        setupCapture();UUID key=UUID.randomUUID();try(var http=new HttpHarness()) {
            assertEquals(201,http.request("POST","/api/v1/leads",capture("original-source-org"),Map.of("Idempotency-Key",key.toString())).statusCode());
            mutate("update identity.organization_unit set display_name='Changed fixture',revision=revision+1 where tenant_id=? and organization_unit_id=?",seed.tenant(),seed.org());var before=counts();
            var denied=http.request("GET","/api/v1/commands/"+key+"/receipt",null,Map.of());assertEquals(403,denied.statusCode(),denied.body());assertFalse(denied.body().contains("receiptRef"));assertEquals(before,counts());
        }
    }
}
