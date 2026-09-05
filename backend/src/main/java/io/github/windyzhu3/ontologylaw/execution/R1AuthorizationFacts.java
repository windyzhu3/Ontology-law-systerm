package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;

/** Named Owner read contract. Implementations compose public Owner ports; no authority decisions or callbacks. */
public interface R1AuthorizationFacts {
    record Capture(Subject organization, Subject existingLead) {}
    record Task(Subject selector, UUID ownerAppointmentId, String taskType, String primaryCommand,
            Subject lead, Subject currentLead, Draft draft, Owner owner) {}
    record Draft(Subject selector, UUID taskId, String actionCode, String schemaCode, int schemaVersion) {}
    record Owner(UUID appointmentId, UUID principalId, UUID organizationId, boolean active, String evidence) {}
    Capture capture(Connection connection, UUID tenant, String sourceAccountCode, String sourceRecordKeyDigest) throws SQLException;
    Task task(Connection connection, UUID tenant, UUID taskId, Instant checkedAt) throws SQLException;
}
