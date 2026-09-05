package io.github.windyzhu3.ontologylaw.execution;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CanonicalJsonTest {
    @Test void canonicalizes_nested_unicode_keys_and_escaping_without_normalizing_text() {
        assertEquals("{\"a\":[true,null,9007199254740991],\"z\":\"\\n\\t\\\"\\\\\",\"😀\":\"é\",\"דּ\":0}",
                CanonicalJson.encode(new HashMap<>(Map.of("z", "\n\t\"\\", "a", Arrays.asList(true, null, 9007199254740991L), "דּ", 0L, "😀", "é"))));
    }
    @Test void rejects_lossy_or_unsupported_numbers_and_invalid_unicode() {
        for (Object invalid : List.of(9007199254740992L, -9007199254740992L, 1.0, Double.NaN, new java.math.BigDecimal("1.1"), "\ud800", "\udc00"))
            assertThrows(IllegalArgumentException.class, () -> CanonicalJson.encode(invalid));
    }
    @Test void canonical_scope_has_independent_fixed_digest() {
        String prefix = "0198e4c0-0000-7000-8000-00000000000";
        String canonical = CanonicalJson.encode(Map.of("bindings", List.of(Map.of("name", "leadAssignmentId", "value", prefix+"4"), Map.of("name", "leadAssignmentRevision", "value", 5)), "commandType", "RECORD_CONTACT_RESULT", "lead", Map.of("id", prefix+"3", "revision", 9007199254740991L, "type", "lead.lead"), "profile", "R1_COMMAND_SCOPE_V1", "taskId", prefix+"2", "tenantId", prefix+"1"));
        assertEquals("61f1239c8e8e1d03bde88452a61321bbfa66cabbc72e32242d87de9cf58f89ca", HexFormat.of().formatHex(CanonicalJson.digest(canonical)));
    }
    @Test void task_scope_rejects_extra_bindings_and_canonicalizes_uuid_selectors() {
        UUID tenant=UUID.fromString("0198e4c0-0000-7000-8000-000000000001"),task=UUID.fromString("0198e4c0-0000-7000-8000-000000000002"),leadId=UUID.fromString("0198e4c0-0000-7000-8000-000000000003");
        var lead=new io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject("lead.lead",leadId,9007199254740991L,null);
        var scope=CommandScope.task(tenant,CommandEnvelope.Type.RECORD_CONTACT_RESULT,task,lead,Map.of("leadAssignmentId",UUID.fromString("0198e4c0-0000-7000-8000-000000000004"),"leadAssignmentRevision",5L));
        assertEquals("61f1239c8e8e1d03bde88452a61321bbfa66cabbc72e32242d87de9cf58f89ca",HexFormat.of().formatHex(scope.digest()));
        assertThrows(IllegalArgumentException.class,()->CommandScope.task(tenant,CommandEnvelope.Type.COMPLETE_LEAD_INGRESS,task,lead,Map.of("extra",1L)));
    }
    @Test void envelope_defensively_freezes_payload_before_digest_and_handler_observe_it() {
        var fields=new HashMap<String,Object>();var values=new ArrayList<Object>();values.add(1L);fields.put("values",values);
        UUID id=UUID.randomUUID();var actor=new io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor(id,id,id,null,null);
        var envelope=new CommandEnvelope(CommandEnvelope.Type.COMPLETE_LEAD_INGRESS,id,id,actor,fields);
        values.add(2L);fields.put("extra","secret");
        assertEquals("{\"values\":[1]}",CanonicalJson.encode(envelope.payload()));
    }
    @Test void recovery_scope_separates_command_types_and_rejects_unsafe_increment() {
        UUID tenant=UUID.randomUUID(),task=UUID.randomUUID(),wait=UUID.randomUUID();String hash=Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        assertNotEquals(CommandScope.reopen(tenant,CommandEnvelope.Type.REOPEN_DUE_CONTACT_TASKS,task,wait,hash).canonical(),CommandScope.reopen(tenant,CommandEnvelope.Type.REOPEN_DUE_ROUTING_REVIEW_TASKS,task,wait,hash).canonical());
        assertThrows(java.sql.SQLException.class,()->CommandHandler.nextRevision(9007199254740991L));
        assertEquals(9007199254740991L,assertDoesNotThrow(()->CommandHandler.nextRevision(9007199254740990L)));
    }
    @Test void capture_and_draft_scopes_use_only_their_frozen_bindings() {
        UUID id=UUID.fromString("0198e4c0-0000-7000-8000-000000000001");String hash=Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        assertEquals("{\"profile\":\"R1_CAPTURE_SCOPE_V1\",\"sourceAccountCode\":\"SOURCE_A\",\"sourceRecordKeyDigest\":\""+hash+"\",\"tenantId\":\""+id+"\"}",CommandScope.capture(id,"SOURCE_A",hash).canonical());
        assertEquals("{\"actionCode\":\"RECORD_CONTACT_RESULT\",\"profile\":\"R1_DRAFT_SCOPE_V1\",\"taskId\":\""+id+"\",\"tenantId\":\""+id+"\"}",CommandScope.draft(id,id,CommandEnvelope.Type.RECORD_CONTACT_RESULT).canonical());
        assertThrows(IllegalArgumentException.class,()->CommandScope.draft(id,id,CommandEnvelope.Type.CAPTURE_LEAD));
    }
}
