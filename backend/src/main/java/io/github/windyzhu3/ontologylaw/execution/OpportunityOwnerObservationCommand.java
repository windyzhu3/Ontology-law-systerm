package io.github.windyzhu3.ontologylaw.execution;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationSnapshot;
import java.sql.*;
/** Closed observation preparation; runtime persists evidence before its business rollback boundary. */
public interface OpportunityOwnerObservationCommand extends CommandHandler {
 Result executeObserved(Connection c,CommandEnvelope e,Context context,AuditAppender.OwnerValidationEntry evidence)throws SQLException;
 AuditAppender.OwnerValidationEntry prepareObservation(Connection c,CommandEnvelope e,Context context,AuthorizationSnapshot authorization)throws SQLException;
}
