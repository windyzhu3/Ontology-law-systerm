package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.responsibility.TaskFactory;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class R1UnicodeHttpIT extends R1HttpFixture {
    record OtherConstraints(@jakarta.validation.constraints.Size(max=1) List<String> items,
                            @jakarta.validation.constraints.NotNull String required,
                            @jakarta.validation.constraints.Pattern(regexp="OK") String pattern,
                            @jakarta.validation.constraints.Max(1) int number) {}
    void setupCapture()throws Exception{setupFlow(TaskFactory.Type.COMPLETE_LEAD_INGRESS);mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,'LEAD_CAPTURE',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),seed.appointment(),seed.appointment(),seed.org());}
    @ParameterizedTest @CsvSource({"capturedName,200","legalNeedSummary,2000"})
    void safe_capture_text_counts_unicode_code_points_after_trim_without_rewriting_source_key(String field,int limit)throws Exception {
        setupCapture();String boundary="😀".repeat(limit);var payload=new TreeMap<String,Object>(input(false));payload.put("sourceRecordKey"," exact source key ");
        try(var http=new HttpHarness()) {
            payload.put(field,boundary+"😀");var before=counts();assertEquals(400,http.request("POST","/api/v1/leads",payload,Map.of("Idempotency-Key",UUID.randomUUID().toString())).statusCode());assertEquals(before,counts());
            payload.put(field,boundary);UUID key=UUID.randomUUID();var response=http.request("POST","/api/v1/leads",payload,Map.of("Idempotency-Key",key.toString()));assertEquals(201,response.statusCode(),response.body());
            payload.put(field,"\u3000 "+boundary+" \u3000");before=counts();var replay=http.request("POST","/api/v1/leads",payload,Map.of("Idempotency-Key",key.toString()));assertEquals(201,replay.statusCode(),replay.body());assertEquals(http.body(response),http.body(replay));assertEquals(before,counts());
            payload.put("sourceRecordKey","exact source key");assertEquals(409,http.request("POST","/api/v1/leads",payload,Map.of("Idempotency-Key",key.toString())).statusCode());assertEquals(before,counts());
        }
    }
    @Test void safe_500_text_and_empty_output_draft_text_keep_their_distinct_contracts()throws Exception {
        setupFlow(TaskFactory.Type.COMPLETE_LEAD_INGRESS);var values=new TreeMap<String,Object>(Map.of("phone","+12025550999","sourceCode","OWNER_CONFIRMED","sourceSummary","😀".repeat(501)));
        try(var http=new HttpHarness()) {
            var before=counts();String path="/api/v1/tasks/"+current.selector().id()+"/draft";assertEquals(400,http.request("PUT",path,Map.of("actionCode","COMPLETE_LEAD_INGRESS","schemaVersion",1,"values",values),Map.of("Idempotency-Key",UUID.randomUUID().toString(),"If-None-Match","*")).statusCode());assertEquals(before,counts());
            values.put("sourceSummary"," \u3000"+"😀".repeat(500)+"\u3000 ");var saved=http.request("PUT",path,Map.of("actionCode","COMPLETE_LEAD_INGRESS","schemaVersion",1,"values",values),Map.of("Idempotency-Key",UUID.randomUUID().toString(),"If-None-Match","*"));assertEquals(201,saved.statusCode(),saved.body());assertEquals("😀".repeat(500),((Map<?,?>)((Map<?,?>)http.body(saved).get("draft")).get("values")).get("sourceSummary"));
            var draft=new io.github.windyzhu3.ontologylaw.api.adapter.generated.model.PartialCompleteLeadIngressValuesV1().sourceSummary("");var validator=http.context.getBean(jakarta.validation.Validator.class);assertTrue(validator.validate(draft).isEmpty());draft.sourceSummary("  ");assertTrue(validator.validate(draft).isEmpty());assertTrue(http.context.getBean(tools.jackson.databind.json.JsonMapper.class).writeValueAsString(draft).contains("\"sourceSummary\":\"  \""));
            assertEquals(Set.of("items","required","pattern","number"),validator.validate(new OtherConstraints(List.of("a","b"),null,"WRONG",2)).stream().map(v->v.getPropertyPath().toString()).collect(java.util.stream.Collectors.toSet()));
        }
    }
    @Test void unpaired_surrogates_and_control_characters_are_rejected_without_any_command_write()throws Exception {
        setupCapture();try(var http=new HttpHarness()){var before=counts();var payload=new TreeMap<String,Object>(input(false));payload.remove("capturedName");payload.put("sourceRecordKey","raw-unicode");String raw=mapper.writeValueAsString(payload);for(String bad:List.of("\\uD800","\\uDC00","\\u0001","\\u000a")){String body=raw.substring(0,raw.length()-1)+",\"capturedName\":\""+bad+"\"}";var response=http.request("POST","/api/v1/leads",body,Map.of("Idempotency-Key",UUID.randomUUID().toString()));assertEquals(400,response.statusCode(),response.body());assertEquals(before,counts());}}
    }
}
