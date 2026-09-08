package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.execution.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class R1ProblemHttpIT extends R1HttpFixture {
    void setupCapture()throws Exception{setupFlow(io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.Type.COMPLETE_LEAD_INGRESS);mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,'LEAD_CAPTURE',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),seed.appointment(),seed.appointment(),seed.org());}
    Map<String,Object> capture(){var body=new TreeMap<String,Object>(input(false));body.put("sourceRecordKey","HTTP-validation");return body;}
    void problem(HttpHarness http,java.net.http.HttpResponse<String> response,int status,String code){
        assertEquals(status,response.statusCode(),response.body());var body=http.body(response);assertEquals(code,body.get("code"));assertEquals(status,body.get("status"));assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());assertEquals("application/problem+json",response.headers().firstValue("Content-Type").orElseThrow().split(";")[0]);assertFalse(response.body().contains(seed.tenant().toString()));assertTrue(response.headers().firstValue("ETag").isEmpty());assertTrue(response.headers().firstValue("Location").isEmpty());assertTrue(response.headers().firstValue("WWW-Authenticate").isEmpty());
        if(code.equals("VALIDATION_FAILED")){assertEquals(1,((List<?>)body.get("fieldErrors")).size());assertEquals("SAME_KEY_AFTER_FIX",body.get("retryPolicy"));}
    }
    @Test void required_invalid_and_repeated_idempotency_key_are_safe_preslot_errors()throws Exception {
        setupCapture();try(var http=new HttpHarness()){
            var before=counts();problem(http,http.request("POST","/api/v1/leads",capture(),Map.of()),400,"IDEMPOTENCY_KEY_REQUIRED");assertEquals(before,counts());
            for(String key:List.of("1-1-1-1-1","not-a-uuid",UUID.randomUUID()+","+UUID.randomUUID())){problem(http,http.request("POST","/api/v1/leads",capture(),Map.of("Idempotency-Key",key)),400,"IDEMPOTENCY_KEY_INVALID");assertEquals(before,counts());}
        }
    }
    @Test void closed_json_rejects_unknown_duplicates_explicit_null_wrong_scalar_and_malformed_before_slot()throws Exception {
        setupCapture();var unknown=new TreeMap<>(capture());unknown.put("tenantId",seed.tenant().toString());var explicitNull=new TreeMap<>(capture());explicitNull.put("phone",null);var number=new TreeMap<>(capture());number.put("sourceRecordKey",123);String raw=mapper.writeValueAsString(capture());String duplicate=raw.substring(0,raw.length()-1)+",\"sourceRecordKey\":\"changed\"}";
        try(var http=new HttpHarness()) {assertTrue(http.context.getBean(tools.jackson.databind.json.JsonMapper.class).isEnabled(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES),"Production MVC mapper must reject unknown properties");var before=counts();int variant=0;for(Object body:List.of(unknown,explicitNull,number,duplicate,"{","[]")) {var response=http.request("POST","/api/v1/leads",body,Map.of("Idempotency-Key",UUID.randomUUID().toString()));assertEquals(400,response.statusCode(),"Invalid input variant "+variant++);problem(http,response,400,"VALIDATION_FAILED");assertEquals(before,counts());}}
    }
    @Test void missing_task_and_draft_preconditions_are_zero_write_and_only_current_visible_kind_is_returned()throws Exception {
        setupContact();var command=prepare(contact("NOT_CONNECTED"));String task=current.selector().id().toString();
        try(var http=new HttpHarness()) {
            var card=http.body(http.request("GET","/api/v1/workcards/current",null,Map.of()));var tags=(Map<?,?>)((Map<?,?>)card.get("currentCard")).get("preconditions");var before=counts();
            var response=http.request("POST","/api/v1/tasks/"+task+"/commands/record-contact-result",command.payload(),Map.of("Idempotency-Key",command.commandId().toString()));problem(http,response,428,"TASK_PRECONDITION_REQUIRED");assertEquals(Map.of("resourceKind","TASK","value",tags.get("taskETag")),http.body(response).get("currentETag"));assertEquals(before,counts());
            response=http.request("PUT","/api/v1/tasks/"+task+"/draft",Map.of("actionCode","RECORD_CONTACT_RESULT","schemaVersion",1,"values",contact("NOT_CONNECTED")),Map.of("Idempotency-Key",UUID.randomUUID().toString()));problem(http,response,428,"DRAFT_PRECONDITION_REQUIRED");assertEquals(Map.of("resourceKind","DRAFT","value",tags.get("draftETag")),http.body(response).get("currentETag"));assertEquals(before,counts());
        }
    }
    @Test void postslot_stale_task_returns_only_current_task_tag_and_safe_known_key_ref_after_commit()throws Exception {
        setupContact();var command=prepare(contact("NOT_CONNECTED"));String currentTag=command.taskPrecondition().ifMatch();
        try(var http=new HttpHarness()) {
            var before=counts();var response=http.request("POST","/api/v1/tasks/"+current.selector().id()+"/commands/record-contact-result",command.payload(),Map.of("Idempotency-Key",command.commandId().toString(),"If-Match","\"task."+"A".repeat(43)+"\""));problem(http,response,412,"STALE_TASK");var body=http.body(response);assertEquals(Map.of("resourceKind","TASK","value",currentTag),body.get("currentETag"));assertEquals(Map.of("commandId",command.commandId().toString(),"href","/api/v1/commands/"+command.commandId()+"/receipt"),body.get("receiptRef"));delta(before,0,0,0,0,0,0,1,1,1,0,0);
            var read=http.request("GET","/api/v1/commands/"+command.commandId()+"/receipt",null,Map.of());assertEquals(200,read.statusCode());assertEquals("REJECTED",http.body(read).get("outcome"));
        }
    }
    @Test void same_key_payload_conflict_never_exposes_internal_receipt_or_original_outcome_and_writes_nothing()throws Exception {
        setupCapture();try(var http=new HttpHarness()) {
            UUID key=UUID.randomUUID();var first=http.request("POST","/api/v1/leads",capture(),Map.of("Idempotency-Key",key.toString()));assertEquals(201,first.statusCode(),first.body());String receipt=(String)http.body(first).get("receiptId");var before=counts();var changed=new TreeMap<>(capture());changed.put("legalNeedSummary","Another request");var response=http.request("POST","/api/v1/leads",changed,Map.of("Idempotency-Key",key.toString()));problem(http,response,409,"COMMAND_PAYLOAD_CONFLICT");assertEquals(Map.of("commandId",key.toString(),"href","/api/v1/commands/"+key+"/receipt"),http.body(response).get("receiptRef"));assertFalse(response.body().contains(receipt));assertFalse(response.body().contains("SUCCEEDED"));assertEquals(before,counts());
        }
    }
}
