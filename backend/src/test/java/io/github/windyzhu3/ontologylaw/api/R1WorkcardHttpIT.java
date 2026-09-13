package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.responsibility.TaskFactory;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class R1WorkcardHttpIT extends R1HttpFixture {
    @ParameterizedTest @EnumSource(TaskFactory.Type.class)
    void seven_current_cards_are_typed_private_projections_and_every_200_304_has_committed_audit(TaskFactory.Type type)throws Exception {
        setupFlow(type);
        int sources=switch(type){case COMPLETE_LEAD_INGRESS,RESOLVE_LEAD_ROUTING_GAP->5;case CONTACT_LEAD,ACK_SOURCE_INTAKE_STOP_REQUEST,REVIEW_LEAD_VALIDITY->6;case RESOLVE_LEAD_DUPLICATE->7;case ASSIGN_LEAD->8;};
        try(var http=new HttpHarness()) {
            var before=counts();var response=http.request("GET","/api/v1/workcards/current",null,Map.of());assertEquals(200,response.statusCode(),response.body());
            var body=http.body(response);var card=(Map<?,?>)body.get("currentCard");assertEquals(type.name(),card.get("taskType"));assertEquals(current.selector().id().toString(),card.get("taskId"));
            assertEquals("private, no-cache",response.headers().firstValue("Cache-Control").orElseThrow());assertEquals("Authorization",response.headers().firstValue("Vary").orElseThrow());String etag=response.headers().firstValue("ETag").orElseThrow();assertTrue(etag.matches("\"wb\\.[A-Za-z0-9_-]{43}\""));
            assertTrue(card.containsKey("actionDraft"));assertNull(card.get("actionDraft"));assertNull(((Map<?,?>)card.get("preconditions")).get("draftETag"));delta(before,0,0,0,0,0,0,0,0,sources,0,0);
            before=counts();var cached=http.request("GET","/api/v1/workcards/current",null,Map.of("If-None-Match",etag));assertEquals(304,cached.statusCode(),cached.body());assertEquals("",cached.body());assertEquals(etag,cached.headers().firstValue("ETag").orElseThrow());delta(before,0,0,0,0,0,0,0,0,sources,0,0);
        }
    }
    @Test void zero_state_is_explicit_null_with_no_sensitive_source_or_fabricated_disclosure_audit()throws Exception {
        setupContact();cancelCurrent();try(var http=new HttpHarness()) {
            var before=counts();var response=http.request("GET","/api/v1/workcards/current",null,Map.of());assertEquals(200,response.statusCode(),response.body());var body=http.body(response);assertTrue(body.containsKey("currentCard"));assertNull(body.get("currentCard"));assertEquals(false,((Map<?,?>)body.get("chatComposer")).get("enabled"));assertNull(((Map<?,?>)body.get("chatComposer")).get("targetTaskId"));assertEquals(before,counts());
        }
    }
}
