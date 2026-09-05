package io.github.windyzhu3.ontologylaw.execution.internal.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;

import io.github.windyzhu3.ontologylaw.testing.PostgresIntegrationTest;
import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

class CapabilityRoleExecutorIT extends PostgresIntegrationTest {
    private static String scalar(Connection connection, String query) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(query)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    @Test void switches_capabilities_on_one_read_committed_transaction_and_restores_connection() throws Exception {
        try (var connection = database.apiConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            assertEquals("law_api_login", scalar(connection, "select current_user"));
            inTransaction(connection, Capability.COMMAND, c -> {
                assertSame(connection, c);
                assertEquals(Connection.TRANSACTION_READ_COMMITTED, c.getTransactionIsolation());
                assertEquals("law_app_command", scalar(c, "select current_user"));
                String transaction = scalar(c, "select pg_current_xact_id()");
                setLocalRole(c, Capability.QUERY);
                assertEquals("law_app_query", scalar(c, "select current_user"));
                assertEquals(transaction, scalar(c, "select pg_current_xact_id()"));
                setLocalRole(c, Capability.AUDIT);
                assertEquals("law_audit_append", scalar(c, "select current_user"));
                return null;
            });
            assertEquals("law_api_login", scalar(connection, "select current_user"));
            assertTrue(connection.getAutoCommit());
            assertEquals(Connection.TRANSACTION_SERIALIZABLE, connection.getTransactionIsolation());
            assertFalse(connection.isClosed());
        }
    }

    @Test void rollback_restores_role_and_does_not_commit_failed_writes() throws Exception {
        try (var connection = database.apiConnection()) {
            assertThrows(IllegalStateException.class, () -> inTransaction(connection, Capability.COMMAND, c -> {
                try (var sql = c.createStatement()) {
                    sql.execute("INSERT INTO identity.tenant VALUES ('00000000-0000-0000-0000-000000000099','rollback','rollback','ACTIVE',now(),NULL,0)");
                }
                setLocalRole(c, Capability.QUERY);
                setLocalRole(c, Capability.AUDIT);
                throw new IllegalStateException("abort");
            }));
            assertEquals("law_api_login", scalar(connection, "select current_user"));
            assertTrue(connection.getAutoCommit());
            assertEquals("0", inTransaction(connection, Capability.QUERY,
                    c -> scalar(c, "select count(*) from identity.tenant where tenant_code='rollback'")));
        }
    }

    @Test void rejects_autocommit_role_switch_and_nested_transactions() throws Exception {
        try (var c = database.apiConnection()) {
            assertThrows(SQLException.class, () -> setLocalRole(c, Capability.COMMAND));
            c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            c.setAutoCommit(false);
            assertThrows(SQLException.class, () -> setLocalRole(c, Capability.COMMAND));
            assertThrows(SQLException.class, () -> inTransaction(c, Capability.COMMAND, x -> null));
            c.rollback();
        }
    }

    @Test void failed_rollback_never_restores_autocommit_and_caller_discard_cannot_commit_the_write() throws Exception {
        try (var actual = database.apiConnection()) {
            Connection failingRollback = (Connection) java.lang.reflect.Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("rollback")) throw new SQLException("Injected rollback transport failure", "08006");
                        try { return method.invoke(actual, arguments); }
                        catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                    });
            var failure = assertThrows(IllegalStateException.class, () -> inTransaction(failingRollback, Capability.COMMAND, c -> {
                try (var sql = c.createStatement()) {
                    sql.execute("INSERT INTO identity.tenant VALUES ('00000000-0000-0000-0000-000000000088','rollback-failure','rollback failure','ACTIVE',now(),NULL,0)");
                }
                throw new IllegalStateException("application failure");
            }));
            assertEquals("08006", ((SQLException) failure.getSuppressed()[0]).getSQLState());
            assertFalse(actual.getAutoCommit(), "Restoring auto-commit here would commit the failed work");
            assertEquals("1", scalar(actual, "select count(*) from identity.tenant where tenant_code='rollback-failure'"));
            // Caller owns this connection and must discard it after rollback failure, not return it to a pool.
        }
        try (var observer = database.apiConnection()) {
            assertEquals("0", inTransaction(observer, Capability.QUERY,
                    c -> scalar(c, "select count(*) from identity.tenant where tenant_code='rollback-failure'")));
        }
    }

    @Test void runtime_logins_have_only_explicit_capabilities_and_never_inherit_union() throws Exception {
        try (var api = database.apiConnection(); var worker = database.workerConnection()) {
            assertEquals("law_app_command,law_app_query,law_audit_append", scalar(api,
                    "select string_agg(r.rolname,',' order by r.rolname) from pg_auth_members m join pg_roles r on r.oid=m.roleid join pg_roles u on u.oid=m.member where u.rolname=current_user"));
            assertEquals("law_app_worker", scalar(worker,
                    "select r.rolname from pg_auth_members m join pg_roles r on r.oid=m.roleid join pg_roles u on u.oid=m.member where u.rolname=current_user"));
            assertEquals("42501", assertThrows(SQLException.class,
                    () -> scalar(api, "select 1 from lead.lead limit 0")).getSQLState());
            assertEquals("42501", assertThrows(SQLException.class,
                    () -> inTransaction(worker, Capability.COMMAND, c -> null)).getSQLState());
            for (String table : new String[]{"lead.lead", "responsibility.task_occurrence", "audit.audit_entry"}) {
                assertEquals("42501", assertThrows(SQLException.class, () -> inTransaction(worker, Capability.WORKER, c -> {
                    try (var sql = c.createStatement()) {
                        sql.execute("insert into " + table + " (tenant_id) select null::uuid where false");
                    }
                    return null;
                })).getSQLState());
            }
            assertEquals("42501", assertThrows(SQLException.class, () -> inTransaction(api, Capability.AUDIT,
                    c -> scalar(c, "select 1 from audit.audit_entry limit 0"))).getSQLState());
        }
    }

    @Test void migrations_retain_all_54_tables_owned_by_migrator_and_restricted_logins() throws Exception {
        try (var c = database.adminConnection()) {
            assertEquals("54", scalar(c, "select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace where c.relkind='r' and n.nspname not in ('pg_catalog','information_schema') and n.nspname not like 'pg_toast%'"));
            assertEquals("0", scalar(c, "select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace join pg_roles r on r.oid=c.relowner where c.relkind='r' and n.nspname not in ('pg_catalog','information_schema') and n.nspname not like 'pg_toast%' and r.rolname <> 'law_schema_migrator'"));
            assertEquals("0", scalar(c, "select count(*) from pg_roles where rolname in ('law_api_login','law_worker_login') and (rolinherit or rolsuper or rolcreaterole or rolcreatedb or rolreplication or rolbypassrls)"));
        }
    }

    @Test void commits_jooq_writes_and_preserves_bigint_binding_without_precision_loss() throws Exception {
        var tenant = io.github.windyzhu3.ontologylaw.identity.internal.persistence.jooq.tables.Tenant.TENANT;
        var id = java.util.UUID.randomUUID();
        try (var c = database.apiConnection()) {
            inTransaction(c, Capability.COMMAND, connection -> {
                DSL.using(connection, SQLDialect.POSTGRES).insertInto(tenant)
                        .set(tenant.TENANT_ID, id).set(tenant.TENANT_CODE, "bigint")
                        .set(tenant.DISPLAY_NAME, "bigint").set(tenant.STATE, "ACTIVE")
                        .set(tenant.CREATED_AT, java.time.OffsetDateTime.now())
                        .set(tenant.REVISION, 0L).execute();
                return null;
            });
            assertEquals("42501", assertThrows(SQLException.class,
                    () -> scalar(c, "select 1 from identity.tenant limit 0")).getSQLState());
        }
        try (var observer = database.apiConnection()) {
            Long revision = inTransaction(observer, Capability.QUERY, c -> DSL.using(c, SQLDialect.POSTGRES)
                    .select(tenant.REVISION).from(tenant).where(tenant.TENANT_ID.eq(id)).fetchSingle(tenant.REVISION));
            assertEquals(0L, revision);
            Long beyondWireLimit = inTransaction(observer, Capability.QUERY, c -> DSL.using(c, SQLDialect.POSTGRES)
                    .select(DSL.val(9007199254740993L, tenant.REVISION.getDataType())).fetchSingle(0, Long.class));
            assertEquals(9007199254740993L, beyondWireLimit);
        }
    }

    @Test void audit_append_has_no_returning_or_mutation_privileges_and_query_cannot_write() throws Exception {
        try (var c = database.apiConnection()) {
            inTransaction(c, Capability.AUDIT, connection -> {
                try (var statement = connection.createStatement()) {
                    assertEquals(0, statement.executeUpdate("insert into audit.audit_entry (tenant_id) select null::uuid where false"));
                }
                return null;
            });
            for (String denied : new String[]{
                    "insert into audit.audit_entry (tenant_id) select null::uuid where false returning tenant_id",
                    "update audit.audit_entry set tenant_id=tenant_id where false",
                    "delete from audit.audit_entry where false"}) {
                assertEquals("42501", assertThrows(SQLException.class, () -> inTransaction(c, Capability.AUDIT, connection -> {
                    try (var statement = connection.createStatement()) { statement.execute(denied); }
                    return null;
                })).getSQLState());
                assertEquals("law_api_login", scalar(c, "select current_user"));
                assertTrue(c.getAutoCommit());
            }
            assertEquals("42501", assertThrows(SQLException.class, () -> inTransaction(c, Capability.QUERY, connection -> {
                try (var statement = connection.createStatement()) {
                    statement.execute("insert into lead.lead (tenant_id) select null::uuid where false");
                }
                return null;
            })).getSQLState());
        }
    }
}
