package io.github.windyzhu3.ontologylaw.api;

import java.util.*;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class R1WireShapeHttpIT extends R1HttpFixture {
    @Test void optional_candidate_fields_are_absent_not_null_while_required_card_nulls_remain_explicit()throws Exception {
        setupContact();var values=contact("NOT_CONNECTED");
        try(var http=new HttpHarness()) {
            var response=http.request("PUT","/api/v1/tasks/"+current.selector().id()+"/draft",Map.of("actionCode","RECORD_CONTACT_RESULT","schemaVersion",1,"values",values),Map.of("Idempotency-Key",UUID.randomUUID().toString(),"If-None-Match","*"));assertEquals(201,response.statusCode(),response.body());var draft=(Map<?,?>)http.body(response).get("draft");assertEquals(values.keySet(),((Map<?,?>)draft.get("values")).keySet());assertFalse(((Map<?,?>)draft.get("values")).containsValue(null));
            var card=http.body(http.request("GET","/api/v1/workcards/current",null,Map.of()));var projected=(Map<?,?>)card.get("currentCard");assertFalse(((Map<?,?>)((Map<?,?>)projected.get("commandForm")).get("values")).containsKey("legalNeed"));
        }
    }
    @Test void unauthenticated_problem_preserves_exact_safe_localized_utf8_text()throws Exception {
        setupContact();try(var http=new HttpHarness()){
            var response=http.client.send(HttpRequest.newBuilder(http.origin.resolve("/api/v1/workcards/current")).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(401,response.statusCode());assertEquals("需要有效认证",http.body(response).get("title"));assertEquals("需要有效认证",http.body(response).get("detail"));
        }
    }
}
