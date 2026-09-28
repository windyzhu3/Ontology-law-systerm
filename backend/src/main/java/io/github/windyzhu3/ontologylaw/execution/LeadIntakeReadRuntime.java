package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor;
import java.sql.Connection;
import java.sql.SQLException;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;

/** Read-only configured source metadata; never reads customer or lead facts. */
public final class LeadIntakeReadRuntime {
    @FunctionalInterface public interface Sources<T> { T read(Connection connection) throws SQLException; }
    public <T> T read(Connection connection, Actor actor, Sources<T> sources) throws SQLException {
        return inTransaction(connection, Capability.QUERY, c -> {
            R1BusinessFence.databaseBacked().shared(c, actor.tenantId());
            return sources.read(c);
        });
    }
}
