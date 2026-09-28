package io.github.windyzhu3.ontologylaw.lead;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class LeadIntakeSourcesIT extends LeadBusinessFixture {
    private LeadIntakeSources.Source source(String account, String name) {
        return new LeadIntakeSources.Source(account, name, "MANUAL", "CONSULTATION", "CN", "NORMAL");
    }
    private LeadIntakeSources catalog() {
        return new LeadIntakeSources(sources, List.of(source("FIXTURE", "客户转介绍")));
    }
    private List<LeadIntakeSources.Source> read(LeadIntakeSources catalog, Actor actor) throws Exception {
        try (var c = database.apiConnection()) { return inTransaction(c, Capability.QUERY, x -> catalog.read(x, actor)); }
    }
    @Test void authorized_intake_owner_reads_business_labels_without_creating_business_facts() throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL, false);
        var before = counts();
        assertEquals(List.of(source("FIXTURE", "客户转介绍")), read(catalog(), seed.request().actor()));
        assertEquals(before, counts());
    }
    @Test void revoked_capture_authority_disappears_on_the_next_read() throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL, false);
        var catalog = catalog(); assertEquals(1, read(catalog, seed.request().actor()).size());
        try (var c = database.apiConnection()) { inTransaction(c, Capability.COMMAND, x -> {
            sql(x, "update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='TEST',revision=revision+1 where tenant_id=? and authority_code='LEAD_CAPTURE'", seed.tenant()); return null;
        }); }
        assertTrue(read(catalog, seed.request().actor()).isEmpty());
    }
    @Test void inactive_appointment_and_mismatched_principal_cannot_see_sources() throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL, false);
        var actor = seed.request().actor();
        assertTrue(read(catalog(), new Actor(actor.tenantId(), UUID.randomUUID(), actor.appointmentId(), null, null)).isEmpty());
        try (var c = database.apiConnection()) { inTransaction(c, Capability.COMMAND, x -> {
            sql(x, "update identity.appointment set state='SUSPENDED',revision=revision+1 where tenant_id=? and appointment_id=?", seed.tenant(), seed.appointment()); return null;
        }); }
        assertTrue(read(catalog(), actor).isEmpty());
    }
    @Test void catalog_is_tenant_scoped_and_refuses_service_or_represented_actors() throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL, false);
        var actor = seed.request().actor();
        assertTrue(read(catalog(), new Actor(UUID.randomUUID(), actor.principalId(), actor.appointmentId(), null, null)).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> read(catalog(), new Actor(actor.tenantId(), actor.principalId(), actor.appointmentId(), null, null, PrincipalKind.SERVICE)));
        assertThrows(IllegalArgumentException.class, () -> read(catalog(), new Actor(actor.tenantId(), actor.principalId(), actor.appointmentId(), actor.principalId(), actor.appointmentId())));
    }
    @Test void unresolved_source_root_is_not_exposed_and_configuration_is_validated() throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL, false);
        var missing = new R1SourcePolicyRegistry(Map.of("OTHER", new R1SourcePolicyRegistry.SourcePolicy(R1SourcePolicyRegistry.AssignmentMode.MANUAL, List.of("ROOT"), "ROOT", "MISSING", "Asia/Shanghai")));
        assertTrue(read(new LeadIntakeSources(missing, List.of(source("OTHER", "渠道录入"))), seed.request().actor()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new LeadIntakeSources(sources, List.of(source("UNKNOWN", "未知来源"))));
        assertThrows(IllegalArgumentException.class, () -> new LeadIntakeSources(sources, List.of(source("FIXTURE", "甲"), source("FIXTURE", "乙"))));
        assertThrows(IllegalArgumentException.class, () -> source("FIXTURE", "标签\n多行"));
        assertThrows(IllegalArgumentException.class, () -> source("FIXTURE", " "));
    }
    @Test void query_requires_callers_transaction_and_returns_an_immutable_result() throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL, false);
        try (var c = database.apiConnection()) { assertThrows(java.sql.SQLException.class, () -> catalog().read(c, seed.request().actor())); }
        assertThrows(UnsupportedOperationException.class, () -> read(catalog(), seed.request().actor()).clear());
    }
    @Test void selected_source_captures_once_and_creates_the_existing_contact_responsibility() throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.AUTOMATIC, true);
        var source = read(catalog(), seed.request().actor()).getFirst();
        var values = captureValues("selected-source", true);
        values.put("sourceAccountCode", source.sourceAccountCode());
        values.put("sourceChannelCode", source.sourceChannelCode());
        values.put("serviceCategoryCode", source.serviceCategoryCode());
        values.put("jurisdictionCode", source.jurisdictionCode());
        values.put("urgencyCode", source.urgencyCode());
        var command = new CommandEnvelope(CommandEnvelope.Type.CAPTURE_LEAD, UUID.randomUUID(), UUID.randomUUID(), seed.request().actor(), values);
        var result = run(command);
        assertEquals(CommandOutcome.Status.SUCCEEDED, result.status());
        var contact = task("CONTACT_LEAD");
        assertEquals(seed.appointment(), contact.owner());
        assertEquals(result.resultFact(), contact.lead());
        var after = counts();
        assertEquals(result, run(command));
        assertEquals(after, counts());
    }
    @Test void a_previously_visible_source_does_not_bypass_revocation_at_capture() throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.AUTOMATIC, true);
        assertEquals(1, read(catalog(), seed.request().actor()).size());
        mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='TEST',revision=revision+1 where tenant_id=? and authority_code='LEAD_CAPTURE'", seed.tenant());
        rejectedBefore(capture("after-revocation", true), "NOT_AUTHORIZED");
    }
}
