package io.github.windyzhu3.ontologylaw.opportunity;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** Internal named read port. Authorization and complete opening-source protection remain with the caller. */
public interface OpportunityResponsibilityReader {
    record Responsibility(Subject basis, UUID appointmentId) {}
    Responsibility current(Connection c, UUID tenant, Subject opportunity) throws SQLException;
    static OpportunityResponsibilityReader databaseBacked() {
        return new io.github.windyzhu3.ontologylaw.opportunity.internal.persistence.JooqOpportunityResponsibilityReader();
    }
}
