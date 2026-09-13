package io.github.windyzhu3.ontologylaw.execution;

import java.sql.*;

/** Deployment assembly only. It cannot execute a business command or expose an HTTP callback. */
public final class R1AssemblyValidationRuntime {
    @FunctionalInterface public interface Validation<T> {T validate(Connection connection)throws SQLException;}
    public <T> T validate(RuntimeDatabase database,Validation<T> validation)throws SQLException {
        try(var connection=database.open()) {
            connection.setReadOnly(true);
            return io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.inTransaction(connection,
                    io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.Capability.QUERY,validation::validate);
        }
    }
}
