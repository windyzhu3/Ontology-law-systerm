package io.github.windyzhu3.ontologylaw.lead;

import io.github.windyzhu3.ontologylaw.execution.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class CommandReceiptMetadataIT extends LeadBusinessFixture {
    @Test void malformed_recovery_metadata_is_rejected_before_real_audit_insert_and_rolls_back_command() throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL, false);
        var appender = io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked("METADATA_FAULT_IT");
        runtime = new CommandRuntime(new LeadCommands(sources,LeadProtectionTest.protection()).handlers(),
                io.github.windyzhu3.ontologylaw.identity.AuthorizationService.databaseBacked(), (c,e) -> {
                    String changed = e.summary().replace("\"kind\":\"CAPTURE\"", "\"kind\":\"CAPTURE\",\"payload\":\"forbidden\"");
                    appender.append(c,new io.github.windyzhu3.ontologylaw.audit.AuditAppender.Entry(e.id(),e.commandId(),e.commandType(),e.correlationId(),e.result(),e.authorization(),changed,CanonicalJson.digest(changed),e.schemaVersion()));
                }, R1AuthorizationReaders.databaseBacked(sources), R1EventReaders.databaseBacked());
        var before = counts();
        try (var c = database.apiConnection()) {
            assertThrows(IllegalArgumentException.class, () -> runtime.execute(c,capture("invalid-metadata",false)));
        }
        assertEquals(before,counts());
    }

    @Test void new_public_terminal_audit_preserves_exact_recovery_scope_and_replay_writes_nothing() throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL, false);
        var command = capture("receipt-metadata", false);
        var outcome = run(command);
        var row = audit(command);
        assertEquals("R1_COMMAND_AUDIT_V2", row.get("schema"));
        assertEquals(2, row.get("version"));
        var summary = JsonMapper.builder().build().readTree((String) row.get("summary"));
        assertEquals(Set.of("result", "authorizationEvidence", "receiptRecovery"), summary.propertyNames());
        var recovery = summary.path("receiptRecovery");
        assertEquals("R1_COMMAND_RECEIPT_RECOVERY_V1", recovery.path("profile").asString());
        assertEquals("CAPTURE", recovery.path("binding").path("kind").asString());
        assertEquals("FIXTURE", recovery.path("scope").path("sourceAccountCode").asString());
        assertFalse(recovery.toString().contains("receipt-metadata"));
        var before = counts();
        assertEquals(outcome, run(command));
        assertEquals(before, counts());
    }

    @Test void post_slot_rejected_capture_retains_recovery_scope_after_new_lead_rollback() throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL, false);
        mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_code='LEAD_INGRESS_COMPLETE'", seed.tenant());
        var command = capture("rejected-without-lead", false);
        var outcome = run(command);
        assertEquals(CommandOutcome.Status.REJECTED, outcome.status());
        assertNull(outcome.resultFact());
        var row = audit(command);
        assertEquals("R1_COMMAND_AUDIT_V2", row.get("schema"));
        var summary = JsonMapper.builder().build().readTree((String) row.get("summary"));
        assertEquals("REJECTED", summary.path("result").asString());
        assertEquals("CAPTURE", summary.path("receiptRecovery").path("binding").path("kind").asString());
        assertEquals("0", scalar("select count(*) from lead.lead where tenant_id=?", seed.tenant()));
    }

    private Map<String,Object> audit(CommandEnvelope command) throws Exception {
        try (var c = database.adminConnection(); var p = c.prepareStatement("select summary_schema_code,summary_schema_version,change_summary::text from audit.audit_entry where tenant_id=? and command_id=?")) {
            p.setObject(1, seed.tenant()); p.setObject(2, command.commandId());
            try (var r = p.executeQuery()) {
                assertTrue(r.next());
                var row = Map.<String,Object>of("schema", r.getString(1), "version", r.getInt(2), "summary", r.getString(3));
                assertFalse(r.next()); return row;
            }
        }
    }
}
