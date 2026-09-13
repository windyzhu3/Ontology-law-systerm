package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.responsibility.TaskFactory;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class R1ReceiptHttpIT extends R1HttpFixture {
    @Test void undeclared_get_query_and_body_are_inert_and_cannot_supply_a_different_actor_tenant_or_target()throws Exception {
        setupContact();var command=prepare(contact("CONNECTED_VALID"));execute(command);
        try(var http=new HttpHarness()) {
            String path="/api/v1/commands/"+command.commandId()+"/receipt";var original=http.request("GET",path,null,Map.of());assertEquals(200,original.statusCode());
            for(Object body:List.of(Map.of("tenantId",UUID.randomUUID().toString(),"actor",Map.of("principalId",UUID.randomUUID().toString()),"commandId",UUID.randomUUID().toString(),"selector",Map.of("type","identity.principal")),"{unparsed malformed body")) {
                var before=counts();var response=http.request("GET",path+"?tenantId="+UUID.randomUUID()+"&actor=other&commandId="+UUID.randomUUID()+"&subject=identity.principal",body,Map.of());assertEquals(200,response.statusCode(),response.body());assertEquals(http.body(original),http.body(response));delta(before,0,0,0,0,0,0,0,0,1,0,0);
            }
            var before=counts();var absent=http.request("GET","/api/v1/commands/"+UUID.randomUUID()+"/receipt?commandId="+command.commandId(),Map.of("commandId",command.commandId().toString()),Map.of());assertEquals(404,absent.statusCode());assertEquals(before,counts());
        }
    }
    @Test void receipt_not_found_and_current_revocation_have_closed_safe_problems_without_disclosure()throws Exception {
        setupContact();var command=prepare(contact("CONNECTED_VALID"));execute(command);
        try(var http=new HttpHarness()) {
            var before=counts();var absent=http.request("GET","/api/v1/commands/"+UUID.randomUUID()+"/receipt",null,Map.of());
            assertEquals(404,absent.statusCode());assertTrue(absent.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json"));
            assertEquals("NOT_FOUND",http.body(absent).get("code"));assertEquals(Set.of("type","title","status","code","detail","instance","retryPolicy"),http.body(absent).keySet());assertEquals(before,counts());
            mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_code='SALES_CONTACT_OWNER'",seed.tenant());
            before=counts();var denied=http.request("GET","/api/v1/commands/"+command.commandId()+"/receipt",null,Map.of());assertEquals(403,denied.statusCode());assertEquals("NOT_AUTHORIZED",http.body(denied).get("code"));assertEquals("no-store",denied.headers().firstValue("Cache-Control").orElseThrow());assertTrue(denied.headers().firstValue("WWW-Authenticate").isEmpty());
            assertFalse(denied.body().contains(seed.tenant().toString()));assertFalse(denied.body().contains("receiptRef"));assertFalse(denied.body().contains("identity."));assertEquals(before,counts());
        }
    }
    @Test void generated_receipt_endpoint_returns_original_done_command_only_after_real_disclosure_audit_commit()throws Exception {
        setupFlow(TaskFactory.Type.CONTACT_LEAD);var command=prepare(contact("CONNECTED_VALID"));var original=execute(command);var before=counts();
        try(var http=new HttpHarness()) {
            var response=http.request("GET","/api/v1/commands/"+command.commandId()+"/receipt",null,Map.of("If-None-Match","*"));assertEquals(200,response.statusCode());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());assertTrue(response.headers().firstValue("ETag").isEmpty());
            var body=http.body(response);assertEquals(command.commandId().toString(),body.get("commandId"));assertEquals(original.receiptId().toString(),body.get("receiptId"));assertEquals("SUCCEEDED",body.get("outcome"));assertFalse(response.body().contains(current.lead().id().toString()));
            delta(before,0,0,0,0,0,0,0,0,1,0,0);
            assertEquals("1",scalar("select count(*)::text from audit.audit_entry_classified_v where tenant_id=? and action_code='READ_COMMAND_RECEIPT'",seed.tenant()));
            var repeated=http.request("GET","/api/v1/commands/"+command.commandId()+"/receipt",null,Map.of());assertEquals(200,repeated.statusCode());assertEquals(body,http.body(repeated));
        }
    }
}
