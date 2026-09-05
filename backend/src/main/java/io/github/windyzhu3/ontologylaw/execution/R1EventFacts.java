package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;

/** Named read contract for persisted event sources. Downstream Owners compose these facts. */
public interface R1EventFacts {
    record Task(Subject selector, Subject lead, UUID owner, String purpose, String primaryCommand, String state,
            String slaCode, long slaSeconds, Instant slaDue, Subject completion, Draft draft) {}
    record Draft(Subject selector, UUID taskId, String action, String schema, int version, String state) {}
    record Contact(Subject selector, UUID leadId, UUID assignmentId, UUID taskId, long contactNo, String code) {}
    record Assignment(Subject selector, UUID leadId, UUID owner) {}
    record Opportunity(Subject selector, UUID leadId, UUID assignmentId, UUID contactId, UUID owner) {}
    record Decision(Subject selector, UUID taskId, Subject subject, String contract, int version, String code) {}
    record Wait(Subject selector, UUID taskId, long taskRevision, String profile, int version, Instant resumeDue) {}
    Subject lead(Connection c, UUID tenant, UUID id) throws SQLException;
    Subject capturedLead(Connection c, UUID tenant, String account, String digest) throws SQLException;
    Task task(Connection c, UUID tenant, UUID id) throws SQLException;
    Contact contact(Connection c, UUID tenant, UUID id) throws SQLException;
    boolean contactExistsForTask(Connection c, UUID tenant, UUID taskId) throws SQLException;
    Assignment assignment(Connection c, UUID tenant, UUID id) throws SQLException;
    Opportunity opportunityForContact(Connection c, UUID tenant, UUID contactId) throws SQLException;
    Decision decision(Connection c, UUID tenant, UUID id) throws SQLException;
    Wait latestWait(Connection c, UUID tenant, UUID taskId) throws SQLException;
}
