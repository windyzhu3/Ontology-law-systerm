package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.api.adapter.generated.model.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic output-only probes: no customer data, DB or runtime fixtures. */
class R2WorkcardOptionalWireTest {
    private final JsonMapper mapper=JsonMapper.builder().addModule(R1JsonConfiguration.oneOfModule()).build();
    @Test void absent_selection_notice_is_omitted_but_required_current_card_null_is_preserved(){
        var wire=mapper.readTree(mapper.writeValueAsString(new CurrentWorkCardEnvelope()));
        assertFalse(wire.has("selectionNotice"),"Absent optional text must not become JSON null");
        assertTrue(wire.has("currentCard"));assertTrue(wire.path("currentCard").isNull());
    }
    @Test void actual_selection_notice_is_retained(){
        var wire=mapper.readTree(mapper.writeValueAsString(new CurrentWorkCardEnvelope().selectionNotice("Selected task changed")));
        assertEquals("Selected task changed",wire.path("selectionNotice").asString());
    }
    @Test void opportunity_leaf_preserves_task_type_and_required_action_draft_null(){
        var leaf=new R2OpportunityCurrentCardV1().taskType(R2OpportunityCurrentCardV1.TaskTypeEnum.PROGRESS_OPPORTUNITY);
        var wire=mapper.readTree(mapper.writeValueAsString(new CurrentWorkCardEnvelope().currentCard(leaf))).path("currentCard");
        assertEquals("PROGRESS_OPPORTUNITY",wire.path("taskType").asString());
        assertTrue(wire.has("actionDraft"));assertTrue(wire.path("actionDraft").isNull());
    }
    @Test void next_summary_omits_absent_optional_customer_metadata(){
        var wire=mapper.readTree(mapper.writeValueAsString(new CurrentWorkCardEnvelope().addNextSummariesItem(new NextSummary())));
        var next=wire.path("nextSummaries").get(0);
        assertFalse(next.has("subjectTitle"),"Optional title must be absent, not null");
        assertFalse(next.has("subjectFactRef"),"Optional reference must be absent, not null");
        assertTrue(wire.has("currentCard"));assertTrue(wire.path("currentCard").isNull());
    }
    @Test void next_summary_keeps_present_customer_metadata(){
        var summary=new NextSummary().subjectTitle("Synthetic example").subjectFactRef("a".repeat(43));
        var wire=mapper.readTree(mapper.writeValueAsString(summary));
        assertEquals("Synthetic example",wire.path("subjectTitle").asString());
        assertEquals("a".repeat(43),wire.path("subjectFactRef").asString());
    }
}
