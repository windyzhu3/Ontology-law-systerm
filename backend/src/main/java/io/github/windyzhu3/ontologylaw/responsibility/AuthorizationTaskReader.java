package io.github.windyzhu3.ontologylaw.responsibility;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.util.UUID;

public interface AuthorizationTaskReader {
    record Task(Subject selector, UUID ownerAppointmentId, String taskType, String primaryCommand, Subject lead, Draft draft) {}
    record Draft(Subject selector, UUID taskId, String actionCode, String schemaCode, int schemaVersion) {}
    Task read(Connection connection, UUID tenant, UUID taskId) throws SQLException;
    static AuthorizationTaskReader databaseBacked() {
        return new io.github.windyzhu3.ontologylaw.responsibility.internal.persistence.JooqAuthorizationTaskReader();
    }
}
