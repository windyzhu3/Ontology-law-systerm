package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;

/** Named Owner read contract. Implementations compose public Owner ports; no authority decisions or callbacks. */
public interface R1AuthorizationFacts {
    record MaterialFact(Subject selector,Subject opportunity,Subject basis,UUID owner,Subject confirmation,Subject previous,Subject upload,Instant createdAt){}
    default MaterialFact materialFact(Connection c,UUID tenant,Subject exact)throws SQLException{return null;}
    record Contracts(Subject opportunity,java.util.List<Subject> protectedFacts,UUID organization,UUID owner){public Contracts{protectedFacts=java.util.List.copyOf(protectedFacts);}}
    default Contracts transfers(Connection c,UUID tenant,CommandAuthorizationBinding.Transfers binding,CommandEnvelope.Type type)throws SQLException{return null;}
    default Contracts contractRecovery(Connection c,UUID tenant,CommandAuthorizationBinding.ContractRecovery binding)throws SQLException{return null;}
    default Contracts contracts(Connection c,UUID tenant,CommandAuthorizationBinding.Contracts binding)throws SQLException{return null;}
    record FollowupAttempt(java.util.List<Subject> protectedFacts,UUID organization,UUID owner){public FollowupAttempt{protectedFacts=java.util.List.copyOf(protectedFacts);}}
    default FollowupAttempt followupAttempt(Connection c,UUID tenant,CommandAuthorizationBinding.FollowupAttempt binding,Subject receipt)throws SQLException{return null;}
    record Quotes(Subject opportunity,java.util.List<Subject> protectedFacts,UUID organization,UUID owner){public Quotes{protectedFacts=java.util.List.copyOf(protectedFacts);}}
    default Quotes quotes(Connection c,UUID tenant,CommandAuthorizationBinding.Quotes binding)throws SQLException{return null;}
    record Materials(Subject opportunity,java.util.List<Subject> protectedFacts,UUID organization,UUID owner){public Materials{protectedFacts=java.util.List.copyOf(protectedFacts);}}
    default Materials materials(Connection c,UUID tenant,CommandAuthorizationBinding.Materials binding,boolean receipt)throws SQLException{return null;}
    record CustomerVersion(Subject selector,Subject opportunity,Subject responsibility,UUID owner,Subject draft,Subject previous,java.time.Instant createdAt){}
    default CustomerVersion customerRequirementVersion(Connection c,UUID tenant,Subject exact)throws SQLException{return null;}

    record CustomerRequirements(Subject opportunity,java.util.List<Subject> protectedFacts,UUID organization,UUID owner) {public CustomerRequirements {protectedFacts=java.util.List.copyOf(protectedFacts);}}
    default CustomerRequirements customerRequirements(Connection c,UUID tenant,CommandAuthorizationBinding.CustomerRequirements binding,boolean receipt)throws SQLException{return null;}
    record OpportunityClosure(Subject currentOpportunity,java.util.List<Subject> protectedFacts,UUID organization,UUID closureActor) {public OpportunityClosure {protectedFacts=java.util.List.copyOf(protectedFacts);}}
    default OpportunityClosure opportunityClosure(Connection c,UUID tenant,CommandAuthorizationBinding.OpportunityClosure binding,Subject receipt)throws SQLException{return null;}
    default CommandReceiptReader.Receipt commandReceipt(Connection c,UUID tenant,UUID command)throws SQLException{return null;}
    record OpportunityReceiptTask(Task task,java.util.List<Subject> protectedFacts){public OpportunityReceiptTask {protectedFacts=java.util.List.copyOf(protectedFacts);}}
    default OpportunityReceiptTask opportunityReceiptTask(Connection c,UUID tenant,UUID task,Instant at)throws SQLException{return null;}
    record OwnerException(Subject currentOpportunity,java.util.List<Subject> protectedFacts,UUID organization) {public OwnerException {protectedFacts=java.util.List.copyOf(protectedFacts);}}
    default OwnerException ownerException(Connection c,UUID tenant,CommandAuthorizationBinding.OwnerException binding,Subject receipt)throws SQLException{return null;}
    record Capture(Subject organization, Subject existingLead) {}
    record Evidence(Subject submission,Subject binding,Subject target,boolean active) {}
    default Evidence evidence(Connection connection,UUID tenant,UUID submission)throws SQLException {return null;}
    /** R2 composition supplies the current separately authorized source Lead selector. */
    default Subject opportunitySource(Connection connection,UUID tenant,UUID opportunity)throws SQLException {return null;}
    /** Stable business scope, independently read from the frozen Opportunity source owner. */
    default UUID opportunityOrganization(Connection connection,UUID tenant,UUID opportunity)throws SQLException {return null;}
    record Opportunity(Subject selector,Subject source,Owner owner,Subject initialTask) {}
    default Opportunity opportunity(Connection c,UUID tenant,UUID id,Instant at)throws SQLException{return null;}
    default Subject opportunityRecoverySource(Connection c,UUID tenant,UUID task)throws SQLException{return null;}
    record Task(Subject selector, UUID ownerAppointmentId, String taskType, String primaryCommand,
            Subject lead, Subject currentLead, Draft draft, Owner owner) {}
    record Draft(Subject selector, UUID taskId, String actionCode, String schemaCode, int schemaVersion) {}
    record Owner(UUID appointmentId, UUID principalId, UUID organizationId, boolean active, String evidence) {}
    Capture capture(Connection connection, UUID tenant, String sourceAccountCode, String sourceRecordKeyDigest) throws SQLException;
    /** Trusted source registration decision; older compositions deliberately have no SERVICE binding. */
    default boolean serviceSourceAllowed(Connection connection,io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor actor,String sourceAccountCode)throws SQLException {return false;}
    Task task(Connection connection, UUID tenant, UUID taskId, Instant checkedAt) throws SQLException;
}
