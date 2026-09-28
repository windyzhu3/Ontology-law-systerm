package io.github.windyzhu3.ontologylaw.opportunity;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Owner writes inside the caller's command transaction. Ports are trusted application assembly, never request data. */
public interface OpportunityOwnerExceptionService {
    enum Reason { OWNER_INACTIVE, OWNER_AUTHORITY_MISSING, OWNER_DENIED, SUPERVISOR_UNRESOLVED, SOURCE_INCONSISTENT }
    enum State { ACTIVE, COORDINATING, RESOLVED, NO_LONGER_APPLICABLE }
    record Snapshot(Subject selector, Subject opportunity, UUID frozenOwner, OpportunityResponsibilityReader.Responsibility responsibility,
            Subject task, Subject waitReceipt, Set<Reason> reasons, State state, Instant firstObservedAt, Instant lastObservedAt,
            UUID lastDispositionId, Instant reviewDueAt, String resolutionKind, Subject resolution) {
        public Snapshot { reasons=Set.copyOf(reasons); }
    }
    /** Probe must validate source, current owner, routing and every existing cycle blocker at the database observation time. */
    record Observation(Set<Reason> reasons, Subject task, Subject waitReceipt, Subject validationEvidence) {
        public Observation { reasons=Set.copyOf(reasons); }
    }
    @FunctionalInterface interface ObservationProbe {
        Observation inspect(Connection c, UUID tenant, Subject opportunity, OpportunityResponsibilityReader.Responsibility responsibility, Instant databaseNow) throws SQLException;
    }
    @FunctionalInterface interface ReceiverGuard {
        void verify(Connection c, UUID tenant, Subject opportunity, UUID receiver, Subject task, Subject wait) throws SQLException;
    }
    record TaskHandoffResult(Subject newTask, Instant originalDueAt, Subject originalWait, Subject newWait) {}
    @FunctionalInterface interface TaskHandoffPort {
        TaskHandoffResult handoff(Connection c, UUID tenant, Subject handoff, Subject opportunity,
                OpportunityResponsibilityReader.Responsibility prior, Subject expectedTask, Subject expectedWait,
                UUID receiver, UUID newTaskId, UUID actor, ZoneId zone) throws SQLException;
    }
    record Decision(Subject exception, Subject opportunity, Subject expectedBasis, Subject expectedTask, Subject expectedWait,
            UUID actor, String reason) {}
    record TransferResult(Snapshot exception, Subject disposition, Subject handoff, Subject newTask, boolean changed) {}
    Optional<Snapshot> observe(Connection c, UUID tenant, Subject opportunity) throws SQLException;
    Snapshot coordinate(Connection c, UUID tenant, Decision decision, Instant reviewDueAt) throws SQLException;
    TransferResult transfer(Connection c, UUID tenant, Decision decision, UUID receiver, ZoneId zone) throws SQLException;
    record Disposition(Subject selector,Subject exception,String kind,UUID actor,String reason,Instant reviewDueAt,UUID receiver,Subject handoff) {}
    Disposition disposition(Connection c,UUID tenant,UUID id)throws SQLException;
    Snapshot active(Connection c,UUID tenant,UUID opportunityId)throws SQLException;
    Snapshot current(Connection c,UUID tenant,UUID exceptionId) throws SQLException;
    Snapshot read(Connection c, UUID tenant, Subject exception) throws SQLException;
    static OpportunityOwnerExceptionService databaseBacked(ObservationProbe probe, ReceiverGuard receivers, TaskHandoffPort tasks) {
        return new io.github.windyzhu3.ontologylaw.opportunity.internal.persistence.JooqOpportunityOwnerExceptionService(probe,receivers,tasks);
    }
}
