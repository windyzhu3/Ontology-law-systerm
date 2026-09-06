package io.github.windyzhu3.ontologylaw.lead;

import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ActionDraftCandidatesTest {
    @Test void contact_requires_legal_need_only_for_connected_valid_and_allows_optional_omissions() {
        var values=new TreeMap<String,Object>(Map.of("leadAssignmentId","10000000-0000-0000-0000-000000000001","leadAssignmentRevision",0,
                "contactChannelCode","PHONE","resultCode","NOT_CONNECTED"));
        assertEquals("NOT_CONNECTED",assertDoesNotThrow(()->LeadCommands.candidate(CommandEnvelope.Type.RECORD_CONTACT_RESULT,values)).get("resultCode"));
        values.put("resultCode","CONNECTED_VALID");
        assertThrows(CommandHandler.Rejected.class,()->LeadCommands.candidate(CommandEnvelope.Type.RECORD_CONTACT_RESULT,values));
        values.put("legalNeed"," Synthetic legal need ");
        assertEquals("Synthetic legal need",LeadCommands.candidate(CommandEnvelope.Type.RECORD_CONTACT_RESULT,values).get("legalNeed"));
        values.put("resultCode","SUSPECT_INVALID");
        assertThrows(CommandHandler.Rejected.class,()->LeadCommands.candidate(CommandEnvelope.Type.RECORD_CONTACT_RESULT,values));
    }
    @Test void review_requires_exact_trigger_and_closed_decision() {
        var values=new TreeMap<String,Object>(Map.of("triggeringContactResultId","10000000-0000-0000-0000-000000000001",
                "triggeringContactResultHash","AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","decisionCode","REOPEN_CONTACT","rationaleSummary"," Synthetic rationale "));
        assertEquals("Synthetic rationale",assertDoesNotThrow(()->LeadCommands.candidate(CommandEnvelope.Type.REVIEW_LEAD_VALIDITY,values)).get("rationaleSummary"));
        values.remove("triggeringContactResultHash");
        assertThrows(CommandHandler.Rejected.class,()->LeadCommands.candidate(CommandEnvelope.Type.REVIEW_LEAD_VALIDITY,values));
    }
    @Test void optional_contact_fields_are_typed_and_reject_unknown_or_disallowed_codes() {
        var values=new TreeMap<String,Object>(Map.of("leadAssignmentId","10000000-0000-0000-0000-000000000001","leadAssignmentRevision",0L,
                "contactChannelCode","EMAIL","resultCode","SUSPECT_INVALID","resultSummary"," Synthetic summary ","evidenceSubmissionId","ABCDEFAB-0000-0000-0000-000000000001"));
        var normalized=LeadCommands.candidate(CommandEnvelope.Type.RECORD_CONTACT_RESULT,values);
        assertEquals("Synthetic summary",normalized.get("resultSummary"));assertEquals("abcdefab-0000-0000-0000-000000000001",normalized.get("evidenceSubmissionId"));
        for(String key:List.of("contactChannelCode","resultCode","resultSummary","evidenceSubmissionId")) {
            var invalid=new TreeMap<>(values);invalid.put(key,key.equals("resultSummary")?"\u0001":"UNREGISTERED");
            assertThrows(CommandHandler.Rejected.class,()->LeadCommands.candidate(CommandEnvelope.Type.RECORD_CONTACT_RESULT,invalid));
        }
        values.put("extra",true);assertThrows(CommandHandler.Rejected.class,()->LeadCommands.candidate(CommandEnvelope.Type.RECORD_CONTACT_RESULT,values));
    }
}
