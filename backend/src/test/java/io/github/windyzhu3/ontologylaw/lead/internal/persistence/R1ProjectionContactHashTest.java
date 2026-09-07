package io.github.windyzhu3.ontologylaw.lead.internal.persistence;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.lead.internal.persistence.jooq.Tables.LEAD_CONTACT_RESULT;
import java.util.*;
import java.time.OffsetDateTime;
import org.jooq.impl.DSL;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.Test;
class R1ProjectionContactHashTest {
    @Test void independent_node_sha256_vectors_include_summary_and_explicit_null_with_six_digit_timestamps(){
        var f=LEAD_CONTACT_RESULT;var row=DSL.using(SQLDialect.POSTGRES).newRecord(f.fields());UUID tenant=UUID.fromString("01900000-0000-7000-8000-000000000001"),id=UUID.fromString("01900000-0000-7000-8000-000000000002");
        row.set(f.LEAD_ID,UUID.fromString("01900000-0000-7000-8000-000000000003"));row.set(f.LEAD_ASSIGNMENT_ID,UUID.fromString("01900000-0000-7000-8000-000000000004"));row.set(f.CONTACT_TASK_ID,UUID.fromString("01900000-0000-7000-8000-000000000005"));row.set(f.CONTACT_NO,1L);row.set(f.CONTACT_CHANNEL_CODE,"PHONE");row.set(f.RESULT_CODE,"NOT_CONNECTED");row.set(f.RESULT_SUMMARY,"Independent summary vector");row.set(f.EVIDENCE_SUBMISSION_ID,null);row.set(f.RESULTED_AT,OffsetDateTime.parse("2026-08-03T01:00:00Z"));row.set(f.CREATED_AT,OffsetDateTime.parse("2026-08-03T01:00:00Z"));
        assertEquals("bDh3selqo6WikgA0Ugx3HZ_ZN46eh13wVypgZNjHc-w",JooqR1EventFacts.contactSelector(tenant,id,row).hash());row.set(f.RESULT_SUMMARY,"Independent summary vector changed");assertEquals("CcEhrxz63Bo45cHNQxTnr_PdEbPQbbC36C6CWeO1I9A",JooqR1EventFacts.contactSelector(tenant,id,row).hash());row.set(f.RESULT_SUMMARY,null);assertEquals("pch7rJBW6DLsXctWOr2DQFZs0JPBNawVeMuQv4CcZfo",JooqR1EventFacts.contactSelector(tenant,id,row).hash());
    }
}
