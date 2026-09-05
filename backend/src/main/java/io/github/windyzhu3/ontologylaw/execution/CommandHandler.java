package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService;
import java.sql.*;
import java.util.*;

/** Trusted static owner port. Implementations own SQL for their facts; all phases share one connection.
 * No phase may commit, switch capability, close the connection, call remote services, or mutate identity.
 */
public interface CommandHandler {
    /** Owner handlers use this for every proposed CAS, before mutating any fact. */
    static long nextRevision(long current)throws SQLException{
        if(current<0 || current>=9007199254740991L)throw new SQLException("Revision cannot be safely incremented","22003");
        return current+1;
    }
    record Context(CommandScope scope, AuthorizationService.Request authorization) {
        public Context {Objects.requireNonNull(scope);Objects.requireNonNull(authorization);}
    }
    enum QueueOwner { R1_PROJECTION }
    enum Event {
        LeadDuplicateResolutionRecordedV1("responsibility.decision_record"), LeadIngressCompletedV1("lead.lead"), LeadAssignedV1("lead.lead_assignment"),
        LeadRoutingDispositionRecordedV1("responsibility.decision_record"), SourceIntakeStopRequestedV1("responsibility.decision_record"), SourceIntakeStopRequestAcknowledgedV1("responsibility.decision_record"),
        LeadContactResultRecordedV1("lead.lead_contact_result"), LeadContactRetryExhaustedV1("lead.lead_contact_result"), LeadValidityReviewedV1("responsibility.decision_record");
        private final String sourceFactType;
        Event(String sourceFactType){this.sourceFactType=sourceFactType;}
        public String sourceFactType(){return sourceFactType;}
        public int schemaVersion(){return 1;}
        public Set<QueueOwner> queueOwners(){return Set.of(QueueOwner.R1_PROJECTION);}
    }
    record Notification(Event event,AuthorizationService.Subject sourceFact) {
        public Notification {Objects.requireNonNull(event);Objects.requireNonNull(sourceFact);}
    }
    record Result(CommandOutcome.Status status,AuthorizationService.Subject fact,List<Notification> notifications) {
        public Result {
            Objects.requireNonNull(status);
            notifications=List.copyOf(notifications);
            if(status==CommandOutcome.Status.REJECTED || fact==null || (status==CommandOutcome.Status.SUCCEEDED)!=(!notifications.isEmpty()))throw new IllegalArgumentException("Invalid handler result");
        }
        public static Result succeeded(AuthorizationService.Subject fact,Event event){return new Result(CommandOutcome.Status.SUCCEEDED,fact,List.of(new Notification(event,fact)));}
        public static Result noChange(AuthorizationService.Subject fact){return new Result(CommandOutcome.Status.NO_CHANGE,fact,List.of());}
    }
    /** Only safe code, never a payload or exception message. */
    final class Rejected extends RuntimeException {
        private final String code;
        public Rejected(String code){super("Command rejected");if(!code.matches("[A-Z][A-Z0-9_]{0,63}"))throw new IllegalArgumentException("Invalid rejection code");this.code=code;}
        public String code(){return code;}
    }
    CommandEnvelope.Type type();
    /** QUERY: input/static schema, visibility and Draft existence; no locks/writes or mutable-state CAS rejection. */
    Context resolve(Connection connection,CommandEnvelope envelope) throws SQLException;
    /** COMMAND: lock Lead (or capture source key) then Task; do not reject advanced state before replay lookup. */
    void lockRoots(Connection connection,CommandEnvelope envelope,Context context) throws SQLException;
    /** Only recovery commands: NEW keys pass eligibility under Lead/Task/Command locks before slot insertion. */
    void recoveryEligibility(Connection connection,CommandEnvelope envelope,Context context) throws SQLException;
    /** COMMAND: current subject/Task/Draft/owner and safe revision increment, before any fact mutation. */
    void validateBeforeWork(Connection connection,CommandEnvelope envelope,Context context) throws SQLException;
    Result execute(Connection connection,CommandEnvelope envelope,Context context) throws SQLException;
    /** QUERY under held locks: exact result and every notification source fact, subject CAS and responsibility ownership after own writes. */
    void validateBeforeCommit(Connection connection,CommandEnvelope envelope,Context context,Result result) throws SQLException;
}
