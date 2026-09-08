package io.github.windyzhu3.ontologylaw.api;

import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class R1DraftProjectionHttpIT extends R1HttpFixture {
    @ParameterizedTest @ValueSource(strings={"TASK","LEAD"})
    void current_exact_deny_prevents_old_key_projection_and_precondition_tag_without_any_write(String denied)throws Exception {
        setupContact();var key=UUID.randomUUID();String path="/api/v1/tasks/"+current.selector().id()+"/draft";var payload=Map.of("actionCode","RECORD_CONTACT_RESULT","schemaVersion",1,"values",contact("NOT_CONNECTED"));
        try(var http=new HttpHarness()) {
            var saved=http.request("PUT",path,payload,Map.of("Idempotency-Key",key.toString(),"If-None-Match","*"));assertEquals(201,saved.statusCode(),saved.body());var draft=(Map<?,?>)http.body(saved).get("draft");
            var subject="TASK".equals(denied)?current.selector():current.lead();deny(subject,"SALES_CONTACT_OWNER");
            var before=counts();var replay=http.request("PUT",path,payload,Map.of("Idempotency-Key",key.toString(),"If-None-Match","*"));assertEquals(403,replay.statusCode(),replay.body());assertEquals("NOT_AUTHORIZED",http.body(replay).get("code"));assertFalse(replay.body().contains((String)draft.get("draftId")));assertFalse(http.body(replay).containsKey("receiptRef"));assertEquals(before,counts());
            var update=http.request("PUT",path,payload,Map.of("Idempotency-Key",UUID.randomUUID().toString(),"If-Match",saved.headers().firstValue("ETag").orElseThrow()));assertEquals(403,update.statusCode(),update.body());assertFalse(http.body(update).containsKey("receiptRef"));assertEquals(before,counts());
            var missing=http.request("PUT",path,payload,Map.of("Idempotency-Key",UUID.randomUUID().toString()));assertEquals(428,missing.statusCode(),missing.body());assertFalse(http.body(missing).containsKey("currentETag"));assertEquals(before,counts());
        }
    }
}
