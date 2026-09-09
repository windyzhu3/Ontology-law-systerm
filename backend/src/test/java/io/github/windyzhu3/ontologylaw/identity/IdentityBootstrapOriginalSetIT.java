package io.github.windyzhu3.ontologylaw.identity;

import io.github.windyzhu3.ontologylaw.api.security.KeycloakDirectoryReader;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import io.github.windyzhu3.ontologylaw.execution.IdentityBootstrapRuntime;
import io.github.windyzhu3.ontologylaw.testing.KeycloakFixture;
import io.github.windyzhu3.ontologylaw.testing.PostgresIntegrationTest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.Capability.COMMAND;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.inTransaction;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Isolated verifier boundary coverage. SQL fixtures create later facts or corrupt the original set;
 * they are not evidence of an end-to-end identity administration or onboarding path.
 */
class IdentityBootstrapOriginalSetIT extends PostgresIntegrationTest {
    private static final List<String> MANAGEMENT_CODES = List.of(
            "IDENTITY_PRINCIPAL_MANAGE",
            "IDENTITY_ORGANIZATION_MANAGE",
            "IDENTITY_APPOINTMENT_MANAGE",
            "IDENTITY_AUTHORITY_MANAGE");

    private KeycloakFixture idp;

    @BeforeAll
    void startIdentityProvider() throws Exception {
        idp = new KeycloakFixture().start();
    }

    @AfterAll
    void stopIdentityProvider() {
        if (idp != null) {
            idp.close();
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "HUMAN_PRINCIPAL",
            "SERVICE_PRINCIPAL",
            "ORGANIZATION",
            "APPOINTMENT",
            "BUSINESS_AUTHORITY_GRANT",
            "DELEGATION_GRANT",
            "OBJECT_ACCESS_GRANT",
            "COMMAND_CLOSURE"
    })
    void later_tenant_rows_do_not_invalidate_the_unchanged_original_bootstrap_set(String addition)
            throws Exception {
        var scenario = createScenario(0);
        var original = originalIds(scenario.tenant());
        insertLaterRow(scenario.tenant(), original, addition);
        var before = databaseSnapshot(scenario.tenant());

        try (var connection = database.apiConnection()) {
            var verified = scenario.runtime().run(connection, scenario.manifest(), false);
            assertEquals("VERIFIED_ORIGINAL", verified.mode());
            assertEquals(scenario.receiptId(), verified.receiptId());
            assertEquals(Map.of(), verified.plannedDelta());
        }

        assertEquals(before, databaseSnapshot(scenario.tenant()));
    }

    @Test
    void expired_candidate_and_unavailable_identity_provider_still_verify_an_extended_complete_original_set()
            throws Exception {
        var scenario = createScenario(295);
        var original = originalIds(scenario.tenant());
        for (String addition : List.of(
                "SERVICE_PRINCIPAL",
                "ORGANIZATION",
                "APPOINTMENT",
                "BUSINESS_AUTHORITY_GRANT",
                "DELEGATION_GRANT",
                "OBJECT_ACCESS_GRANT",
                "COMMAND_CLOSURE")) {
            insertLaterRow(scenario.tenant(), original, addition);
        }
        awaitCandidateExpiry(scenario.candidateExpiresAt());
        var before = databaseSnapshot(scenario.tenant());

        idp.unavailable(() -> {
            try (var connection = database.apiConnection()) {
                var verified = scenario.runtime().verifyOriginal(connection, scenario.manifest());
                assertEquals("VERIFIED_ORIGINAL", verified.mode());
                assertEquals(scenario.receiptId(), verified.receiptId());
                assertEquals(Map.of(), verified.plannedDelta());
            } catch (Exception failure) {
                throw new AssertionError("Original verification unexpectedly required the expired candidate or IdP", failure);
            }
        });

        assertEquals(before, databaseSnapshot(scenario.tenant()));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "MISSING_PRINCIPAL",
            "MISSING_AUTHORITY_GRANT",
            "CHANGED_TENANT_FIELDS",
            "CHANGED_ORGANIZATION_REVISION",
            "CHANGED_APPOINTMENT_LIFECYCLE",
            "SWAPPED_AUTHORITY_CODES"
    })
    void missing_or_altered_original_fact_is_rejected_without_writes(String corruption) throws Exception {
        var scenario = createScenario(0);
        var original = originalIds(scenario.tenant());
        corruptOriginal(scenario.tenant(), original, corruption);
        var before = databaseSnapshot(scenario.tenant());

        try (var connection = database.apiConnection()) {
            var failure = assertThrows(SQLException.class,
                    () -> scenario.runtime().verifyOriginal(connection, scenario.manifest()));
            assertEquals("BOOTSTRAP_ORIGINAL_STATE_CONFLICT", failure.getMessage());
        }

        assertEquals(before, databaseSnapshot(scenario.tenant()));
    }

    private Scenario createScenario(int candidateAgeSeconds) throws Exception {
        UUID tenant = UUID.randomUUID();
        byte[] candidateKey = new byte[32];
        byte[] subjectKey = new byte[32];
        new java.security.SecureRandom().nextBytes(candidateKey);
        new java.security.SecureRandom().nextBytes(subjectKey);
        var directory = KeycloakDirectoryReader.isolatedLoopback(
                new KeycloakDirectoryReader.Trust(idp.issuer(), "task92-directory", idp.directorySecret));
        var candidates = new BootstrapCandidateProtection("task96a-bootstrap-v1",
                Map.of("task96a-bootstrap-v1", candidateKey));
        var binding = new BootstrapCandidateProtection.Binding(
                "Approved synthetic Task 9.6a verifier operator",
                "T" + tenant.toString().replace("-", ""),
                "TASK96A",
                idp.issuer());
        Instant issuedAt = Instant.now().minusSeconds(candidateAgeSeconds);
        String selector = candidates.issue(binding, idp.username, directory, issuedAt);
        var manifest = new IdentityBootstrapService.Manifest(
                "R1_IDENTITY_BOOTSTRAP_V1",
                UUID.randomUUID(),
                binding.tenantCode(),
                "Synthetic verifier tenant",
                "ROOT",
                "Synthetic verifier root",
                binding.provider(),
                binding.issuer(),
                selector,
                "Synthetic verifier founder",
                Instant.now().minusSeconds(60),
                binding.operatorAssertion());
        var runtime = new IdentityBootstrapRuntime(
                tenant,
                binding,
                candidates,
                new ExternalSubjectProtection(ignored -> subjectKey),
                directory,
                AuditAppender.databaseBacked("TASK96A_BOOTSTRAP_IT"));
        IdentityBootstrapRuntime.Outcome created;
        try (var connection = database.apiConnection()) {
            created = runtime.run(connection, manifest, false);
        }
        assertEquals("CREATED", created.mode());
        return new Scenario(tenant, manifest, runtime, created.receiptId(), issuedAt.plusSeconds(300));
    }

    private OriginalIds originalIds(UUID tenant) throws Exception {
        try (var connection = database.migratorConnection()) {
            UUID root = onlyId(connection,
                    "select organization_unit_id from identity.organization_unit where tenant_id=?", tenant);
            UUID principal = onlyId(connection,
                    "select principal_id from identity.principal where tenant_id=?", tenant);
            UUID appointment = onlyId(connection,
                    "select appointment_id from identity.appointment where tenant_id=?", tenant);
            var grants = new ArrayList<Grant>();
            try (var statement = connection.prepareStatement(
                    "select authority_grant_id,authority_code from identity.authority_grant where tenant_id=?")) {
                statement.setObject(1, tenant);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        grants.add(new Grant(rows.getObject(1, UUID.class), rows.getString(2)));
                    }
                }
            }
            assertEquals(4, grants.size());
            assertEquals(MANAGEMENT_CODES.stream().sorted().toList(),
                    grants.stream().map(Grant::code).sorted().toList());
            return new OriginalIds(root, principal, appointment, grants);
        }
    }

    private UUID onlyId(Connection connection, String sql, UUID tenant) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, tenant);
            try (var rows = statement.executeQuery()) {
                rows.next();
                UUID id = rows.getObject(1, UUID.class);
                assertFalse(rows.next(), "Expected one original bootstrap row");
                return id;
            }
        }
    }

    private void insertLaterRow(UUID tenant, OriginalIds original, String addition) throws Exception {
        try (var connection = database.apiConnection()) {
            inTransaction(connection, COMMAND, transaction -> {
                switch (addition) {
                    case "HUMAN_PRINCIPAL", "SERVICE_PRINCIPAL" -> AuthorizationServiceIT.sql(transaction,
                            "insert into identity.principal (tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) values (?,?,?,?,decode(repeat('31',32),'hex'),?,'ACTIVE',clock_timestamp())",
                            tenant, UUID.randomUUID(), addition.startsWith("SERVICE") ? "SERVICE" : "HUMAN",
                            "LATER_" + UUID.randomUUID().toString().replace("-", ""), "Later principal");
                    case "ORGANIZATION" -> AuthorizationServiceIT.sql(transaction,
                            "insert into identity.organization_unit (tenant_id,organization_unit_id,unit_code,display_name,parent_organization_unit_id,state,created_at) values (?,?,?,'Later organization',?,'ACTIVE',clock_timestamp())",
                            tenant, UUID.randomUUID(), "LATER_" + UUID.randomUUID().toString().replace("-", ""), original.root());
                    case "APPOINTMENT" -> AuthorizationServiceIT.sql(transaction,
                            "insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'SERVICE',clock_timestamp()-interval '1 hour','ACTIVE',clock_timestamp())",
                            tenant, UUID.randomUUID(), original.principal(), original.root());
                    case "BUSINESS_AUTHORITY_GRANT" -> AuthorizationServiceIT.sql(transaction,
                            "insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,'LEAD_CAPTURE',clock_timestamp()-interval '1 hour','ACTIVE',clock_timestamp())",
                            tenant, UUID.randomUUID(), original.appointment(), original.appointment(), original.root());
                    case "DELEGATION_GRANT" -> insertDelegationFixture(transaction, tenant, original);
                    case "OBJECT_ACCESS_GRANT" -> AuthorizationServiceIT.sql(transaction,
                            "insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values (?,?,?,?,'IDENTITY_PRINCIPAL_MANAGE','ALLOW',clock_timestamp()-interval '1 hour','ACTIVE',clock_timestamp(),'identity.principal',?,0)",
                            tenant, UUID.randomUUID(), original.principal(), original.appointment(), original.principal());
                    case "COMMAND_CLOSURE" -> insertCommandClosureFixture(transaction, tenant);
                    default -> throw new IllegalArgumentException("Unsupported addition " + addition);
                }
                return null;
            });
        }
    }

    private void insertDelegationFixture(Connection connection, UUID tenant, OriginalIds original)
            throws SQLException {
        UUID delegateAppointment = UUID.randomUUID();
        AuthorizationServiceIT.sql(connection,
                "insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'DELEGATE',clock_timestamp()-interval '1 hour','ACTIVE',clock_timestamp())",
                tenant, delegateAppointment, original.principal(), original.root());
        AuthorizationServiceIT.sql(connection,
                "insert into identity.delegation_grant (tenant_id,delegation_grant_id,source_authority_grant_id,delegator_appointment_id,delegate_appointment_id,scope_organization_unit_id,valid_from,state,created_at) values (?,?,?,?,?,?,clock_timestamp()-interval '1 hour','ACTIVE',clock_timestamp())",
                tenant, UUID.randomUUID(), original.grants().getFirst().id(), original.appointment(),
                delegateAppointment, original.root());
    }

    private void insertCommandClosureFixture(Connection connection, UUID tenant) throws SQLException {
        UUID slot = UUID.randomUUID();
        AuthorizationServiceIT.sql(connection,
                "insert into execution.command_execution_slot (tenant_id,command_execution_slot_id,command_id,envelope_type,command_type,command_scope_digest,payload_digest,occupied_at) values (?,?,?,'INTERNAL_TASK','COMPLETE_LEAD_INGRESS',decode(repeat('41',32),'hex'),decode(repeat('42',32),'hex'),clock_timestamp())",
                tenant, slot, UUID.randomUUID());
        AuthorizationServiceIT.sql(connection,
                "insert into execution.command_receipt (tenant_id,command_receipt_id,command_execution_slot_id,outcome,completed_at,result_fact_type,result_fact_id,result_fact_revision) values (?,?,?,'NO_CHANGE',clock_timestamp(),'identity.tenant',?,0)",
                tenant, UUID.randomUUID(), slot, tenant);
    }

    private void corruptOriginal(UUID tenant, OriginalIds original, String corruption) throws Exception {
        try (var connection = database.adminConnection()) {
            connection.setAutoCommit(false);
            AuthorizationServiceIT.sql(connection, "set local session_replication_role=replica");
            switch (corruption) {
                case "MISSING_PRINCIPAL" -> AuthorizationServiceIT.sql(connection,
                        "delete from identity.principal where tenant_id=? and principal_id=?", tenant, original.principal());
                case "MISSING_AUTHORITY_GRANT" -> AuthorizationServiceIT.sql(connection,
                        "delete from identity.authority_grant where tenant_id=? and authority_grant_id=?",
                        tenant, original.grants().getFirst().id());
                case "CHANGED_TENANT_FIELDS" -> AuthorizationServiceIT.sql(connection,
                        "update identity.tenant set display_name='Corrupted tenant',revision=revision+1 where tenant_id=?",
                        tenant);
                case "CHANGED_ORGANIZATION_REVISION" -> AuthorizationServiceIT.sql(connection,
                        "update identity.organization_unit set revision=revision+1 where tenant_id=? and organization_unit_id=?",
                        tenant, original.root());
                case "CHANGED_APPOINTMENT_LIFECYCLE" -> AuthorizationServiceIT.sql(connection,
                        "update identity.appointment set state='SUSPENDED',revision=revision+1 where tenant_id=? and appointment_id=?",
                        tenant, original.appointment());
                case "SWAPPED_AUTHORITY_CODES" -> {
                    Grant first = original.grants().get(0);
                    Grant second = original.grants().get(1);
                    AuthorizationServiceIT.sql(connection,
                            "update identity.authority_grant set authority_code=case authority_grant_id when ? then ? else ? end where tenant_id=? and authority_grant_id in (?,?)",
                            first.id(), second.code(), first.code(), tenant, first.id(), second.id());
                }
                default -> throw new IllegalArgumentException("Unsupported corruption " + corruption);
            }
            connection.commit();
        }
    }

    private Map<String, String> databaseSnapshot(UUID tenant) throws Exception {
        var snapshot = new LinkedHashMap<String, String>();
        try (var connection = database.migratorConnection();
             var tables = connection.prepareStatement(
                     "select c.table_schema,c.table_name from information_schema.columns c "
                             + "join information_schema.tables t on t.table_schema=c.table_schema and t.table_name=c.table_name "
                             + "where c.column_name='tenant_id' and t.table_type='BASE TABLE' "
                             + "and c.table_schema not in ('pg_catalog','information_schema') "
                             + "order by c.table_schema,c.table_name");
             ResultSet rows = tables.executeQuery()) {
            while (rows.next()) {
                String schema = rows.getString(1);
                String table = rows.getString(2);
                String qualified = '"' + schema.replace("\"", "\"\"") + "\".\""
                        + table.replace("\"", "\"\"") + '"';
                try (var statement = connection.prepareStatement(
                        "select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text),'[]'::jsonb)::text "
                                + "from " + qualified + " t where tenant_id=?")) {
                    statement.setObject(1, tenant);
                    try (var content = statement.executeQuery()) {
                        content.next();
                        snapshot.put(schema + "." + table, content.getString(1));
                    }
                }
            }
        }
        return snapshot;
    }

    private void awaitCandidateExpiry(Instant expiresAt) throws InterruptedException {
        Instant deadline = expiresAt.plusSeconds(10);
        while (Instant.now().isBefore(expiresAt) && Instant.now().isBefore(deadline)) {
            long remainingMillis = Math.max(1, Duration.between(Instant.now(), expiresAt).toMillis());
            Thread.sleep(Math.min(50, remainingMillis));
        }
        assertFalse(Instant.now().isBefore(expiresAt), "Candidate did not reach its exact expiry");
    }

    private record Scenario(
            UUID tenant,
            IdentityBootstrapService.Manifest manifest,
            IdentityBootstrapRuntime runtime,
            UUID receiptId,
            Instant candidateExpiresAt) {
    }

    private record OriginalIds(UUID root, UUID principal, UUID appointment, List<Grant> grants) {
    }

    private record Grant(UUID id, String code) {
    }
}
