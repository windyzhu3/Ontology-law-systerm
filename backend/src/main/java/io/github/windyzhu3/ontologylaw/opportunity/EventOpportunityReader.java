package io.github.windyzhu3.ontologylaw.opportunity;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.util.UUID;

/** Read the immutable creation path without requiring a current business-state projection. */
public interface EventOpportunityReader {
    record Opportunity(Subject selector, UUID leadId, UUID assignmentId, UUID contactId, UUID owner) {}
    Opportunity forContact(Connection c, UUID tenant, UUID contactId) throws SQLException;
    static EventOpportunityReader databaseBacked(){return new io.github.windyzhu3.ontologylaw.opportunity.internal.persistence.JooqEventOpportunityReader();}
}
