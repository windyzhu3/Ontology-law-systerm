package io.github.windyzhu3.ontologylaw.execution.internal.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/** JDBC transaction boundary; the caller owns and closes the connection. */
public final class CapabilityRoleExecutor {
    private CapabilityRoleExecutor() {}
    public enum Capability {
        COMMAND("law_app_command"), QUERY("law_app_query"), AUDIT("law_audit_append"), WORKER("law_app_worker");
        private final String role;
        Capability(String role) { this.role = role; }
    }
    @FunctionalInterface public interface SqlWork<T> { T run(Connection connection) throws SQLException; }

    /**
     * Runs one READ COMMITTED transaction. Nested transactions are rejected before any changes.
     * Work must not commit, roll back, close, or alter connection state itself. On completion the
     * original isolation and auto-commit are restored; PostgreSQL restores the prior role.
     * If rollback fails the connection is left untouched for the caller to discard, never committed.
     */
    public static <T> T inTransaction(Connection connection, Capability capability, SqlWork<T> work) throws SQLException {
        Objects.requireNonNull(capability);
        Objects.requireNonNull(work);
        if (!connection.getAutoCommit()) throw new SQLException("Nested transactions are not supported", "25001");
        int isolation = connection.getTransactionIsolation();
        Throwable failure = null;
        boolean ended = false;
        try {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            setLocalRole(connection, capability);
            T result = work.run(connection);
            connection.commit();
            ended = true;
            return result;
        } catch (SQLException | RuntimeException | Error exception) {
            failure = exception;
            try {
                connection.rollback();
                ended = true;
            } catch (SQLException rollbackFailure) {
                exception.addSuppressed(rollbackFailure);
            }
            throw exception;
        } finally {
            if (ended) {
                try {
                    connection.setAutoCommit(true);
                    connection.setTransactionIsolation(isolation);
                } catch (SQLException restoreFailure) {
                    if (failure != null) failure.addSuppressed(restoreFailure);
                    else throw restoreFailure;
                }
            }
        }
    }

    /** Switches only within an existing READ COMMITTED transaction; never commits it. */
    public static void setLocalRole(Connection connection, Capability capability) throws SQLException {
        Objects.requireNonNull(capability);
        if (connection.getAutoCommit() || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
            throw new SQLException("Capability requires an active READ COMMITTED transaction", "25001");
        }
        try (var statement = connection.createStatement()) {
            statement.execute("SET LOCAL ROLE " + capability.role);
        }
    }
}
