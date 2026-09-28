package io.github.windyzhu3.ontologylaw.execution;

import java.sql.*;
import java.util.*;

/** Technical checkpoint only; no business SQL or HTTP authorization is granted by this port. */
public interface R2OpportunityCheckpointPort {
    record Key(UUID tenantId, UUID principalId, UUID appointmentId, String kind) {
        public Key { Objects.requireNonNull(tenantId);Objects.requireNonNull(principalId);Objects.requireNonNull(appointmentId);if(!Set.of("INITIAL","DUE","OWNER_EXCEPTION","CONTRACT_PREPARATION").contains(kind))throw new IllegalArgumentException("Invalid scan kind"); }
    }
    interface Session extends AutoCloseable {
        byte[] load() throws SQLException;
        void save(byte[] body) throws SQLException;
        @Override void close() throws SQLException;
    }
    @FunctionalInterface interface Connections { Connection open() throws SQLException; }
    /** Null means another process holds the checkpoint; unavailable storage throws. */
    Session tryOpen(Key key) throws SQLException;
    static R2OpportunityCheckpointPort databaseBacked(Connections connections) {
        return new io.github.windyzhu3.ontologylaw.execution.internal.persistence.JdbcR2OpportunityCheckpointPort(connections);
    }
}
