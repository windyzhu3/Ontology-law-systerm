package io.github.windyzhu3.ontologylaw.execution.internal.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;

import io.github.windyzhu3.ontologylaw.testing.PostgresIntegrationTest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.nio.file.Files;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;

class IngressQueryCapabilityIT extends PostgresIntegrationTest {
    private static final List<String> ALLOWED = List.of(
            "ingress_completion_phone_hmac", "ingress_completion_email_hmac",
            "ingress_completion_phone_ciphertext", "ingress_completion_email_ciphertext");
    private static final List<String> DENIED = List.of(
            "ingress_completion_source_code", "ingress_completion_source_summary_ciphertext",
            "ingress_completed_by_appointment_id", "ingress_completed_at", "ingress_completion_digest");

    @Test void fresh_migration_allows_exactly_four_completion_columns_under_query() throws Exception {
        try (var connection = database.apiConnection()) {
            for (String column : ALLOWED) {
                assertDoesNotThrow(() -> inTransaction(connection, Capability.QUERY, c -> {
                    try { execute(c, "SELECT " + column + " FROM lead.lead WHERE false"); }
                    catch (SQLException failure) { fail("Expected allowed column " + column + ", SQLSTATE=" + failure.getSQLState(), failure); }
                    return null;
                }), column);
            }
            for (String column : DENIED) denied(connection, "SELECT " + column + " FROM lead.lead WHERE false");
            denied(connection, "SELECT * FROM lead.lead WHERE false");
            for (String sql : List.of(
                    "INSERT INTO lead.lead (tenant_id) SELECT NULL::uuid WHERE false",
                    "UPDATE lead.lead SET ingress_completion_phone_hmac=NULL WHERE false",
                    "DELETE FROM lead.lead WHERE false", "TRUNCATE lead.lead")) denied(connection, sql);
        }
        try (var connection = database.adminConnection()) {
            for (String column : ALLOWED) assertEquals("false", scalar(connection,
                    "SELECT has_column_privilege('law_app_query','lead.lead','" + column + "','SELECT WITH GRANT OPTION')::text"));
        }
    }

    @Test void v850_historical_stage_denies_all_nine_completion_columns() throws Exception {
        try (var old = Database.start("850"); var api = old.apiConnection()) {
            for (String column : ALLOWED) denied(api, "SELECT " + column + " FROM lead.lead WHERE false");
            for (String column : DENIED) denied(api, "SELECT " + column + " FROM lead.lead WHERE false");
        }
    }

    @Test void runtime_assertions_accept_successor_and_fail_closed_for_inventory_version_and_extra_grants() throws Exception {
        String schemaSql = Files.readString(repositoryRoot().resolve("database/schema-contract-52-plus-2/runtime/sql/assert_schema_contract.sql"));
        String capabilitySql = Files.readString(repositoryRoot().resolve("database/schema-contract-52-plus-2/runtime/sql/assert_capabilities.sql"));
        try (var admin = database.adminConnection()) {
            execute(admin, schemaSql);
            execute(admin, capabilitySql);
        }
        for (String fault : List.of("inventory", "version", "extra_grant", "grant_option")) {
            try (var admin = database.adminConnection()) {
                admin.setAutoCommit(false);
                try {
                    if (fault.equals("inventory")) execute(admin, "DELETE FROM platform_meta.flyway_schema_history WHERE version='860'");
                    if (fault.equals("version")) {
                        execute(admin, "ALTER TABLE platform_meta.deployment_state DISABLE TRIGGER USER");
                        execute(admin, "UPDATE platform_meta.deployment_state SET schema_contract_version='52-plus-2-v1.1'");
                        execute(admin, "ALTER TABLE platform_meta.deployment_state ENABLE TRIGGER USER");
                    }
                    if (fault.equals("extra_grant")) execute(admin, "GRANT SELECT(ingress_completion_digest) ON lead.lead TO law_app_query");
                    if (fault.equals("grant_option")) execute(admin, "GRANT SELECT(ingress_completion_phone_hmac) ON lead.lead TO law_app_query WITH GRANT OPTION");
                    assertEquals("P0001", assertThrows(SQLException.class, () -> execute(admin, schemaSql)).getSQLState(), fault);
                } finally { admin.rollback(); }
                execute(admin, schemaSql);
                admin.rollback();
            }
        }
    }

    @Test void upgrade_preserves_business_rows_and_other_acl_memberships_then_restart_is_noop() throws Exception {
        try (var old = Database.start("850")) {
            var seed = AuthorizationServiceIT.seed(old);
            try (var api = old.apiConnection()) {
                inTransaction(api, Capability.COMMAND, c -> {
                    sql(c, "insert into lead.lead (tenant_id,lead_id,source_channel_code,source_account_code,source_record_key_digest,captured_at,service_category_code,jurisdiction_code,urgency_code,legal_need_summary_ciphertext,captured_content_digest,party_resolution_code,disposition_code,created_at) values (?,?,'FIXTURE','FIXTURE',decode(repeat('00',32),'hex'),clock_timestamp(),'FIXTURE','FIXTURE','FIXTURE',decode('01','hex'),decode(repeat('00',32),'hex'),'UNRESOLVED','CAPTURED',clock_timestamp())", seed.tenant(), seed.subject());
                    sql(c, "update lead.lead set ingress_completion_phone_ciphertext=decode('0102','hex'),ingress_completion_phone_hmac=decode(repeat('11',32),'hex'),ingress_completion_email_ciphertext=decode('0304','hex'),ingress_completion_email_hmac=decode(repeat('22',32),'hex'),ingress_completion_source_code='OWNER_CONFIRMED',ingress_completion_source_summary_ciphertext=decode('05','hex'),ingress_completed_by_appointment_id=?,ingress_completed_at=clock_timestamp(),ingress_completion_digest=decode(repeat('33',32),'hex'),revision=revision+1 where tenant_id=? and lead_id=?", seed.appointment(), seed.tenant(), seed.subject());
                    return null;
                });
            }
            String business, permissions, deployment;
            try (var admin = old.adminConnection()) {
                business = business(admin); permissions = permissions(admin);
                deployment = scalar(admin, "SELECT schema_contract_version||':'||revision FROM platform_meta.deployment_state");
            }
            assertEquals("52-plus-2-v1.1:1", deployment);
            assertEquals(1, old.migrations(null).migrate().migrationsExecuted);
            try (var admin = old.adminConnection(); var api = old.apiConnection(); var worker = old.workerConnection()) {
                assertTrue(business.equals(business(admin)), "Upgrade changed retained business rows");
                assertEquals(permissions, permissions(admin));
                assertEquals("52-plus-2-v1.2:2", scalar(admin, "SELECT schema_contract_version||':'||revision FROM platform_meta.deployment_state"));
                String actual = inTransaction(api, Capability.QUERY, c -> scalar(c, "select encode(ingress_completion_phone_hmac,'hex')||':'||encode(ingress_completion_email_hmac,'hex')||':'||encode(ingress_completion_phone_ciphertext,'hex')||':'||encode(ingress_completion_email_ciphertext,'hex') from lead.lead"));
                assertTrue(("11".repeat(32) + ":" + "22".repeat(32) + ":0102:0304").equals(actual), "QUERY returned incorrect protected values");
                assertEquals("42501", assertThrows(SQLException.class, () -> execute(api, "SELECT ingress_completion_phone_hmac FROM lead.lead")).getSQLState());
                assertEquals("42501", assertThrows(SQLException.class, () -> inTransaction(worker, Capability.QUERY, c -> null)).getSQLState());
            }
            old.migrations(null).validate();
            assertEquals(0, old.migrations(null).migrate().migrationsExecuted);
            try (var admin = old.adminConnection()) {
                assertTrue(business.equals(business(admin)), "No-op restart changed retained business rows");
                assertEquals("52-plus-2-v1.2:2", scalar(admin, "SELECT schema_contract_version||':'||revision FROM platform_meta.deployment_state"));
            }
        }
    }

    @Test void wrong_prior_version_rolls_back_grant_and_state_and_can_retry_without_repair() throws Exception {
        try (var old = Database.start("850")) {
            try (var admin = old.adminConnection()) {
                execute(admin, "ALTER TABLE platform_meta.deployment_state DISABLE TRIGGER USER");
                execute(admin, "UPDATE platform_meta.deployment_state SET schema_contract_version='invalid-prior'");
                execute(admin, "ALTER TABLE platform_meta.deployment_state ENABLE TRIGGER USER");
            }
            String before;
            try (var admin = old.adminConnection()) { before = scalar(admin, "SELECT to_jsonb(d)::text FROM platform_meta.deployment_state d"); }
            var failure = assertThrows(FlywayException.class, () -> old.migrations(null).migrate());
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            assertEquals("55000", assertInstanceOf(SQLException.class, cause).getSQLState());
            try (var admin = old.adminConnection(); var api = old.apiConnection()) {
                assertEquals(before, scalar(admin, "SELECT to_jsonb(d)::text FROM platform_meta.deployment_state d"));
                for (String column : ALLOWED) denied(api, "SELECT " + column + " FROM lead.lead WHERE false");
                assertEquals("0", scalar(admin, "SELECT count(*) FROM platform_meta.flyway_schema_history WHERE version='860'"));
                execute(admin, "ALTER TABLE platform_meta.deployment_state DISABLE TRIGGER USER");
                execute(admin, "UPDATE platform_meta.deployment_state SET schema_contract_version='52-plus-2-v1.1'");
                execute(admin, "ALTER TABLE platform_meta.deployment_state ENABLE TRIGGER USER");
            }
            assertEquals(1, old.migrations(null).migrate().migrationsExecuted);
            old.migrations(null).validate();
            assertEquals(0, old.migrations(null).migrate().migrationsExecuted);
        }
    }

    private static String business(Connection c) throws SQLException {
        var result = new StringBuilder();
        for (String table : List.of("identity.tenant", "identity.principal", "identity.organization_unit", "identity.appointment", "identity.authority_grant", "lead.lead"))
            result.append(scalar(c, "SELECT jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text FROM " + table + " t"));
        return result.toString();
    }

    private static String permissions(Connection c) throws SQLException {
        // Retain all direct relation/column/function/schema grants except the four approved QUERY additions.
        return scalar(c, """
                WITH grants AS (
                  SELECT 'relation:'||oid::text object, x.* FROM pg_class CROSS JOIN LATERAL aclexplode(relacl) x
                  UNION ALL SELECT 'column:'||attrelid||':'||attnum, x.* FROM pg_attribute CROSS JOIN LATERAL aclexplode(attacl) x
                  UNION ALL SELECT 'function:'||oid, x.* FROM pg_proc CROSS JOIN LATERAL aclexplode(proacl) x
                  UNION ALL SELECT 'schema:'||oid, x.* FROM pg_namespace CROSS JOIN LATERAL aclexplode(nspacl) x
                ) SELECT coalesce(jsonb_agg(to_jsonb(g) ORDER BY to_jsonb(g)::text),'[]')::text FROM grants g
                WHERE NOT (g.object IN (SELECT 'column:'||attrelid||':'||attnum FROM pg_attribute WHERE attrelid='lead.lead'::regclass AND attname IN ('ingress_completion_phone_hmac','ingress_completion_email_hmac','ingress_completion_phone_ciphertext','ingress_completion_email_ciphertext'))
                  AND grantee='law_app_query'::regrole AND privilege_type='SELECT' AND NOT is_grantable)
                """) + scalar(c, "SELECT jsonb_agg(to_jsonb(m) ORDER BY to_jsonb(m)::text)::text FROM pg_auth_members m")
                + scalar(c, "SELECT jsonb_agg(to_jsonb(r) ORDER BY rolname)::text FROM pg_roles r WHERE rolname LIKE 'law_%'");
    }

    private static void denied(Connection connection, String sql) {
        assertEquals("42501", assertThrows(SQLException.class, () ->
                inTransaction(connection, Capability.QUERY, c -> { execute(c, sql); return null; })).getSQLState(), sql);
    }
    private static void execute(Connection c, String sql) throws SQLException {
        try (var statement = c.createStatement()) { statement.execute(sql); }
    }
    private static String scalar(Connection c, String sql) throws SQLException {
        try (var statement = c.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next()); return rows.getString(1);
        }
    }
}
