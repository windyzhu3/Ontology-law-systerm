package io.github.windyzhu3.ontologylaw.opportunity.internal.persistence;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationIdentityReader;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.identity.R1AuthorityReader;
import io.github.windyzhu3.ontologylaw.opportunity.OpportunityTaskActivationService;
import io.github.windyzhu3.ontologylaw.opportunity.OpportunityResponsibilityReader;
import io.github.windyzhu3.ontologylaw.identity.OpportunityOwnerExceptionAuthorityReader;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import static io.github.windyzhu3.ontologylaw.opportunity.internal.persistence.jooq.Tables.OPPORTUNITY_;

/** Owns only Opportunity SQL. The caller retains the transaction, capability, audit and entry authorization. */
public final class JooqOpportunityTaskActivationService implements OpportunityTaskActivationService {
    private final OpeningSourceReader sources;
    private final InitialResponsibility responsibility;
    private final AuthorizationService authorization = AuthorizationService.databaseBacked();
    private final AuthorizationIdentityReader identities = AuthorizationIdentityReader.databaseBacked();
    private final R1AuthorityReader authorities = R1AuthorityReader.databaseBacked();

    public JooqOpportunityTaskActivationService(OpeningSourceReader sources, InitialResponsibility responsibility) {
        this.sources = Objects.requireNonNull(sources);
        this.responsibility = Objects.requireNonNull(responsibility);
    }

    public Subject activate(Connection c, UUID tenant, Subject expected, ZoneId zone, Instant createdAt) throws SQLException {
        Objects.requireNonNull(tenant); Objects.requireNonNull(zone); Objects.requireNonNull(createdAt);
        if (expected == null || !"opportunity.opportunity".equals(expected.type()) || expected.revision() == null)
            throw new IllegalArgumentException("Exact Opportunity selector required");
        if (c.getAutoCommit() || c.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED)
            throw new SQLException("Opportunity handoff requires READ COMMITTED transaction", "25001");
        var db = DSL.using(c, SQLDialect.POSTGRES, new org.jooq.conf.Settings().withExecuteLogging(false));
        var o = OPPORTUNITY_;
        var opening = db.select(o.REVISION, o.SOURCE_LEAD_ID, o.SOURCE_ASSIGNMENT_ID, o.SOURCE_CONTACT_RESULT_ID,
                        o.OWNER_APPOINTMENT_ID, o.CLOSED_AT)
                .from(o).where(o.TENANT_ID.eq(tenant)).and(o.OPPORTUNITY_ID.eq(expected.id())).forUpdate().fetchOne();
        require(opening != null, "OPPORTUNITY_NOT_FOUND");
        require(expected.revision().equals(opening.get(o.REVISION)), "STALE_OPPORTUNITY");
        require(opening.get(o.CLOSED_AT) == null, "OPPORTUNITY_CLOSED");
        require(!io.github.windyzhu3.ontologylaw.opportunity.OpportunityActivationCandidates.databaseBacked().salesProgressed(c,tenant,expected.id()), "STALE_OPPORTUNITY");
        UUID frozenOwner = opening.get(o.OWNER_APPOINTMENT_ID);
        var origin = sources.read(c, tenant, opening.get(o.SOURCE_ASSIGNMENT_ID), opening.get(o.SOURCE_CONTACT_RESULT_ID));
        validateOrigin(origin, opening.get(o.SOURCE_LEAD_ID), opening.get(o.SOURCE_ASSIGNMENT_ID),
                opening.get(o.SOURCE_CONTACT_RESULT_ID), frozenOwner);

        // Take the identity lock before ALL current Owner/organization reads; keep it until caller commit.
        authorization.lockForEvaluation(c, tenant);
        var current=OpportunityResponsibilityReader.databaseBacked().current(c,tenant,expected);
        UUID ownerId=current.appointmentId();
        Instant checkedAt = db.select(DSL.field("clock_timestamp()", OffsetDateTime.class)).fetchOne(0, OffsetDateTime.class).toInstant();
        var owner = identities.owner(c, tenant, ownerId, checkedAt);
        var registration = identities.registration(c, tenant, ownerId);
        require(owner != null && owner.active() && registration != null && registration.principalKind() == PrincipalKind.HUMAN,
                "OPPORTUNITY_OWNER_UNAVAILABLE");
        var actor = new Actor(tenant, owner.principalId(), ownerId, null, null, PrincipalKind.HUMAN);
        UUID organization=OpportunityOwnerExceptionAuthorityReader.databaseBacked().historicalOrganization(c,tenant,frozenOwner);
        require(organization!=null,"OPPORTUNITY_OPENING_SOURCE_INVALID");
        var request = authorities.select(c, actor, expected, organization, "OPPORTUNITY_OWNER", "SALES_OPPORTUNITY_OWNER");
        require(request != null && authorization.evaluate(c, request, true).allowed(), "OPPORTUNITY_OWNER_UNAVAILABLE");
        var basisRequest=new Request(actor,current.basis(),organization,request.requirement());
        require(authorization.evaluate(c,basisRequest,true).allowed(),"OPPORTUNITY_OWNER_UNAVAILABLE");
        var task = responsibility.ensureCurrent(c, tenant, ownerId, expected, current.basis(), zone, createdAt);
        if (task == null || !"responsibility.task_occurrence".equals(task.type()) || task.revision() == null)
            throw new IllegalStateException("Initial responsibility Owner returned an invalid selector");
        // A time-bounded grant may expire while Responsibility waits on its own initial-identity lock.
        require(authorization.evaluate(c, request, true).allowed(), "OPPORTUNITY_OWNER_UNAVAILABLE");
        require(authorization.evaluate(c,basisRequest,true).allowed(),"OPPORTUNITY_OWNER_UNAVAILABLE");
        return task;
    }

    private static void validateOrigin(OpeningSources origin, UUID lead, UUID assignmentId, UUID contactId, UUID owner) {
        require(origin != null && origin.contact() != null && origin.assignment() != null && origin.task() != null,
                "OPPORTUNITY_OPENING_SOURCE_INVALID");
        var contact = origin.contact(); var assignment = origin.assignment(); var task = origin.task();
        boolean valid = hashed(contact.selector(), "lead.lead_contact_result", contactId)
                && revisioned(assignment.selector(), "lead.lead_assignment", assignmentId)
                && revisioned(task.selector(), "responsibility.task_occurrence", contact.taskId())
                && revisioned(task.subject(), "lead.lead", lead)
                && lead.equals(contact.leadId()) && assignmentId.equals(contact.assignmentId())
                && lead.equals(assignment.leadId()) && owner.equals(assignment.owner()) && owner.equals(task.owner())
                && "CONNECTED_VALID".equals(contact.resultCode()) && "CONTACT_LEAD".equals(task.purpose())
                && "RECORD_CONTACT_RESULT".equals(task.command()) && "DONE".equals(task.state())
                && contact.selector().equals(task.completion());
        require(valid, "OPPORTUNITY_OPENING_SOURCE_INVALID");
    }

    private static boolean revisioned(Subject selector, String type, UUID id) {
        return selector != null && type.equals(selector.type()) && selector.id().equals(id) && selector.revision() != null;
    }
    private static boolean hashed(Subject selector, String type, UUID id) {
        return selector != null && type.equals(selector.type()) && selector.id().equals(id) && selector.hash() != null;
    }
    private static void require(boolean valid, String code) { if (!valid) throw new Blocked(code); }
}
