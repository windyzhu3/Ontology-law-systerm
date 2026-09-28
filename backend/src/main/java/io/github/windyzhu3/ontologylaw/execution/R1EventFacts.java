package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;

/** Named read contract for persisted event sources. Downstream Owners compose these facts. */
public interface R1EventFacts {
    default boolean transferResult(Connection c,CommandEnvelope e,CommandAuthorizationBinding.Transfers binding,Subject exact)throws SQLException{return false;}
    default boolean opportunityRepairResult(Connection c,CommandEnvelope e,CommandAuthorizationBinding.OpportunityRepair b,Subject exact)throws SQLException{return false;}
    default boolean followupAttemptResult(Connection c,CommandEnvelope e,CommandAuthorizationBinding.FollowupAttempt b,Subject exact)throws SQLException{return false;}
    default boolean contractRecoveryResult(Connection c,CommandEnvelope envelope,CommandAuthorizationBinding.ContractRecovery binding,Subject exact,boolean created)throws SQLException{return false;}
    default boolean contractResult(Connection c,CommandEnvelope e,CommandAuthorizationBinding.Contracts binding,Subject exact)throws SQLException{return false;}
    default boolean quoteResult(Connection c,CommandEnvelope e,CommandAuthorizationBinding.Quotes binding,Subject exact)throws SQLException{return false;}
    default R1AuthorizationFacts.MaterialFact materialFact(Connection c,UUID tenant,Subject exact)throws SQLException{return null;}
    default R1AuthorizationFacts.CustomerVersion customerRequirements(Connection c,UUID tenant,Subject exact)throws SQLException{return null;}

    record Closure(Subject selector,Subject opportunity,Subject responsibility,Subject task,Subject waitReceipt,UUID actor,String reasonCode,Instant closedAt) {}
    default Closure opportunityClosure(Connection c,UUID tenant,Subject exact)throws SQLException{return null;}
    default Subject opportunityTaskCancellation(Connection c,UUID tenant,UUID task)throws SQLException{return null;}
    record OwnerException(Subject selector,Subject opportunity,Subject basis,UUID owner,Subject task,Subject waitReceipt,String state,Subject resolution,Subject disposition,String dispositionKind,Subject decidedException,UUID actor,String reason,Instant reviewDueAt,UUID receiver) {}
    default OwnerException activeOwnerException(Connection c,UUID tenant,UUID opportunity)throws SQLException{return null;}
    default OwnerException ownerException(Connection c,UUID tenant,Subject exact)throws SQLException{return null;}
    record InitialResponsibility(Task task,Instant createdAt,Instant observedAt) {}
    default InitialResponsibility initialOpportunityTask(Connection c,UUID tenant,UUID id)throws SQLException{return null;}
    record LeadAnchor(Subject selector,String sourceAccount,UUID currentAssignment) {}
    record ContactAnchor(UUID leadId,UUID assignmentId,UUID taskId) {}
    LeadAnchor leadAnchor(Connection c,UUID tenant,UUID id)throws SQLException;
    ContactAnchor contactAnchor(Connection c,UUID tenant,UUID id)throws SQLException;
    Opportunity opportunity(Connection c,UUID tenant,UUID id)throws SQLException;
    java.util.Set<UUID> retainedAssignmentOwners(Connection c,UUID tenant) throws SQLException;
    record Task(Subject selector, Subject lead, UUID owner, String purpose, String primaryCommand, String state,
            String slaCode, long slaSeconds, Instant slaDue, Subject completion, Draft draft) {}
    record Draft(Subject selector, UUID taskId, String action, String schema, int version, String state) {}
    record Contact(Subject selector, UUID leadId, UUID assignmentId, UUID taskId, long contactNo, String code) {}
    record Assignment(Subject selector, UUID leadId, UUID owner) {}
    record Opportunity(Subject selector, UUID leadId, UUID assignmentId, UUID contactId, UUID owner) {}
    record Decision(Subject selector, UUID taskId, Subject subject, String contract, int version, String code) {}
    record Wait(Subject selector, UUID taskId, long taskRevision, String profile, int version, Instant resumeDue) {}
    record Progress(Subject selector,UUID opportunityId,UUID taskId) {}
    default Progress progress(Connection c,UUID tenant,UUID id)throws SQLException{return null;}
    Subject lead(Connection c, UUID tenant, UUID id) throws SQLException;
    Subject capturedLead(Connection c, UUID tenant, String account, String digest) throws SQLException;
    Task task(Connection c, UUID tenant, UUID id) throws SQLException;
    Contact contact(Connection c, UUID tenant, UUID id) throws SQLException;
    Subject reviewTrigger(Connection c,UUID tenant,UUID reviewTaskId)throws SQLException;
    boolean contactExistsForTask(Connection c, UUID tenant, UUID taskId) throws SQLException;
    Assignment assignment(Connection c, UUID tenant, UUID id) throws SQLException;
    Opportunity opportunityForContact(Connection c, UUID tenant, UUID contactId) throws SQLException;
    Decision decision(Connection c, UUID tenant, UUID id) throws SQLException;
    Wait latestWait(Connection c, UUID tenant, UUID taskId) throws SQLException;
}
