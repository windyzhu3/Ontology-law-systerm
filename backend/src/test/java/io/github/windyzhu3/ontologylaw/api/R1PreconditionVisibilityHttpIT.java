package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.responsibility.TaskFactory;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class R1PreconditionVisibilityHttpIT extends R1HttpFixture {
    @ParameterizedTest @ValueSource(strings={"ABSENT_DRAFT","MISSING_TASK","CROSS_TENANT","WRONG_TASK_TYPE"})
    void missing_precondition_never_invents_a_draft_tag_or_discloses_an_ineligible_task(String defect)throws Exception {
        setupContact();UUID task=current.selector().id();String operation="record-contact-result";
        if(defect.equals("MISSING_TASK"))task=UUID.randomUUID();if(defect.equals("CROSS_TENANT"))setupContact();if(defect.equals("WRONG_TASK_TYPE")){setupFlow(TaskFactory.Type.ASSIGN_LEAD);task=current.selector().id();}
        try(var http=new HttpHarness()) {
            var before=counts();var response=http.request(defect.equals("ABSENT_DRAFT")?"PUT":"POST","/api/v1/tasks/"+task+(defect.equals("ABSENT_DRAFT")?"/draft":"/commands/"+operation),Map.of(),Map.of("Idempotency-Key",UUID.randomUUID().toString()));
            assertEquals(428,response.statusCode(),response.body());assertEquals(defect.equals("ABSENT_DRAFT")?"DRAFT_PRECONDITION_REQUIRED":"TASK_PRECONDITION_REQUIRED",http.body(response).get("code"));assertFalse(http.body(response).containsKey("currentETag"));assertTrue(response.headers().firstValue("ETag").isEmpty());assertEquals(before,counts());
        }
    }
    @Test void path_validation_and_long_unknown_null_fields_return_bounded_contract_pointers()throws Exception {
        setupContact();try(var http=new HttpHarness()) {
            var before=counts();var response=http.request("POST","/api/v1/tasks/not-a-uuid/commands/record-contact-result",Map.of(),Map.of("Idempotency-Key",UUID.randomUUID().toString()));assertEquals(400,response.statusCode());Object pathPointer=((Map<?,?>)((List<?>)http.body(response).get("fieldErrors")).getFirst()).get("pointer");assertEquals(before,counts());
            response=http.request("PUT","/api/v1/tasks/"+current.selector().id()+"/draft","{\""+"s".repeat(600)+"\":null}",Map.of("Idempotency-Key",UUID.randomUUID().toString(),"If-None-Match","*"));assertEquals(400,response.statusCode());Object longPointer=((Map<?,?>)((List<?>)http.body(response).get("fieldErrors")).getFirst()).get("pointer");assertEquals(before,counts());assertAll(()->assertEquals("/path/taskId",pathPointer),()->assertEquals("/",longPointer));
        }
    }
}
