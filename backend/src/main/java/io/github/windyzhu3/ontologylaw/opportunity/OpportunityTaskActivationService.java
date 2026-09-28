package io.github.windyzhu3.ontologylaw.opportunity;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.Set;

/** Dormant Owner handoff; trusted application assembly supplies source and Responsibility ports. */
public interface OpportunityTaskActivationService {
    record Contact(Subject selector, UUID leadId, UUID assignmentId, UUID taskId, String resultCode) {}
    record Assignment(Subject selector, UUID leadId, UUID owner) {}
    record ContactTask(Subject selector, Subject subject, UUID owner, String purpose,
            String command, String state, Subject completion) {}
    record OpeningSources(Contact contact, Assignment assignment, ContactTask task) {}
    @FunctionalInterface interface OpeningSourceReader {
        OpeningSources read(Connection c, UUID tenant, UUID assignmentId, UUID contactId) throws SQLException;
    }
    @FunctionalInterface interface InitialResponsibility {
        Subject ensure(Connection c, UUID tenant, UUID owner, Subject opportunity, ZoneId zone, Instant now) throws SQLException;
        /** A handoff already owns a task. Its adapter must return that verified current task without creating an initial identity. */
        default Subject ensureCurrent(Connection c,UUID tenant,UUID owner,Subject opportunity,Subject basis,ZoneId zone,Instant now)throws SQLException {
            if(!basis.equals(opportunity))throw new Blocked("OPPORTUNITY_OWNER_UNAVAILABLE");
            return ensure(c,tenant,owner,opportunity,zone,now);
        }
    }
    final class Blocked extends RuntimeException {
        private final String code;
        public Blocked(String code) {
            super("Opportunity responsibility handoff blocked");
            if (!Set.of("OPPORTUNITY_NOT_FOUND", "STALE_OPPORTUNITY", "OPPORTUNITY_CLOSED",
                    "OPPORTUNITY_OPENING_SOURCE_INVALID", "OPPORTUNITY_OWNER_UNAVAILABLE").contains(code))
                throw new IllegalArgumentException("Unregistered Opportunity handoff blockage");
            this.code = code;
        }
        public String code() { return code; }
    }
    Subject activate(Connection c, UUID tenant, Subject opportunity, ZoneId zone, Instant now) throws SQLException;
    static OpportunityTaskActivationService databaseBacked(OpeningSourceReader sources, InitialResponsibility responsibility) {
        return new io.github.windyzhu3.ontologylaw.opportunity.internal.persistence.JooqOpportunityTaskActivationService(sources, responsibility);
    }
}
