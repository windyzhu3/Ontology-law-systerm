package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.util.UUID;

/** Execution-owned, read-only verification of the exact current worker claim. */
public interface R1ProjectionClaimReader {
    record Token(UUID outboxId,UUID eventId,Long revision,String leaseOwner,Long fencingToken) {}
    record Notification(CommandHandler.Event type,Subject source) {}
    Notification read(Connection c,UUID tenant,Token token)throws SQLException;
    static R1ProjectionClaimReader databaseBacked(){return new io.github.windyzhu3.ontologylaw.execution.internal.persistence.JooqR1ProjectionClaimReader();}
}
