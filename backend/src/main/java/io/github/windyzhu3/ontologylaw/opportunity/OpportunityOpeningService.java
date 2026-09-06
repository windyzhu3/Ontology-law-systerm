package io.github.windyzhu3.ontologylaw.opportunity;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;

/** Caller supplies already validated exact source facts and protected confirmed legal need. */
public interface OpportunityOpeningService {
    Subject open(Connection c,UUID tenant,Subject lead,Subject assignment,Subject contact,UUID owner,byte[] legalNeed,byte[] digest,Instant now)throws SQLException;
    static OpportunityOpeningService databaseBacked(){return new io.github.windyzhu3.ontologylaw.opportunity.internal.persistence.JooqOpportunityRepository();}
}
