package io.github.windyzhu3.ontologylaw.contract;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Owner contract workflow; callers authorize all exact facts before body reads and audit atomically. */
public interface ContractWorkflowService {
    record Metadata(UUID opportunityId,UUID actorAppointmentId,Instant createdAt,boolean createdInCurrentTransaction) {}
    record RecoveryCandidate(Subject opportunity,Subject basis,Subject source,Subject workflow,UUID owner) {}
    record RecoveryPage(List<RecoveryCandidate> candidates,UUID lastScannedSource,boolean exhausted) {public RecoveryPage{candidates=List.copyOf(candidates);}}
    record Responsibility(Subject basis,UUID owner,UUID organization) {}
    record DocumentObject(UUID objectVersion,String sha256) {}
    record Generation(DocumentObject template,String basisDigest,Map<String,String> fields,Instant expiresAt) {
        public Generation {fields=Map.copyOf(fields);}
        @Override public String toString(){return "ContractGeneration[protected]";}
    }
    record Task(Subject selector,UUID owner,String type,Subject subject,String state,Instant createdAt,Subject completion) {}
    record ApprovalPolicy(UUID id,String digest,List<UUID> approvers) {
        public ApprovalPolicy {approvers=List.copyOf(approvers);}
    }
    final class Blocked extends RuntimeException {
        private final String code;
        public Blocked(String code){super(code);this.code=code;}
        public String code(){return code;}
    }
    interface Ports {
        default Subject executePayment(Connection c,String action,Actor actor,Subject opportunity,UUID contract,UUID version,Map<String,Object> values)throws SQLException{throw new Blocked("NOT_AUTHORIZED");}
        default RecoveryCandidate paymentRecovery(Connection c,Actor actor,Subject opportunity,Responsibility owner)throws SQLException{return null;}
        default Subject reconcilePayment(Connection c,Actor actor,Subject opportunity,Map<String,Object> payload)throws SQLException{throw new Blocked("NOT_AUTHORIZED");}
        default List<Map<String,Object>> payments(Connection c,Actor actor,Subject opportunity)throws SQLException{return List.of();}

        default DocumentObject documentObject(Connection c,UUID tenant,UUID materialVersion)throws SQLException{throw new UnsupportedOperationException();}
        default String generationParties(Connection c,UUID tenant,UUID opportunity)throws SQLException{return customerName(c,tenant,opportunity);}
        default List<Map<String,Object>> signingParties(Connection c,Actor actor,UUID opportunity)throws SQLException{return List.of();}
        default boolean signingPartyCurrent(Connection c,UUID tenant,UUID party,long revision)throws SQLException{return false;}
        default String signingPartyName(Connection c,UUID tenant,UUID profileVersion)throws SQLException{return "已批准律所主体";}
        Instant now(Connection c)throws SQLException;
        Instant signatureDue(Instant readyAt,ZoneId zone);
        Responsibility responsibility(Connection c,UUID tenant,Subject opportunity)throws SQLException;
        List<Subject> sourceFacts(Connection c,UUID tenant,UUID opportunity)throws SQLException;
        List<Subject> transferFacts(Connection c,UUID tenant,UUID opportunity)throws SQLException;
        boolean permitted(Connection c,Actor actor,UUID organization,List<Subject> facts,String authority)throws SQLException;
        List<UUID> eligible(Connection c,UUID tenant,UUID organization,List<Subject> facts,String authority)throws SQLException;
        default Optional<UUID> routingTarget(UUID tenantId,UUID sourceOrganizationId,String stageCode){return Optional.empty();}
        default boolean routingEnabled(UUID tenantId){return false;}
        default UUID responsibilityOwner(Connection c,UUID tenant,UUID organization,List<Subject> facts,String stage,String authority,UUID incumbent)throws SQLException{
            var qualified=eligible(c,tenant,organization,facts,authority);
            if(incumbent!=null&&qualified.contains(incumbent))return incumbent;
            if(routingEnabled(tenant))return routingTarget(tenant,organization,stage).filter(qualified::contains).orElse(null);
            return qualified.size()==1?qualified.getFirst():null;
        }
        default UUID preparationDecisionOwner(Connection c,UUID tenant,UUID organization,List<Subject> facts)throws SQLException{
            var qualified=eligible(c,tenant,organization,facts,"CONTRACT_PREPARATION_DECIDE");
            if(routingEnabled(tenant)){
                var policy=approvalPolicy(c,tenant,organization,facts);
                if(policy==null)return null;
                qualified=policy.approvers().stream().filter(qualified::contains).distinct().toList();
            }
            return qualified.size()==1?qualified.getFirst():null;
        }
        ApprovalPolicy approvalPolicy(Connection c,UUID tenant,UUID organization,List<Subject> facts)throws SQLException;
        Task read(Connection c,UUID tenant,UUID id)throws SQLException;
        Task currentTask(Connection c,UUID tenant,UUID originalId)throws SQLException;
        List<Task> active(Connection c,UUID tenant,Subject opportunity)throws SQLException;
        Task create(Connection c,UUID tenant,String type,UUID owner,Subject opportunity,ZoneId zone,Instant now)throws SQLException;
        default Task createSignatureTask(Connection c,UUID tenant,String type,UUID owner,Subject opportunity,ZoneId zone,Instant now,Instant dueAt)throws SQLException{throw new UnsupportedOperationException("Signature deadline port required");}
        Task createContractTakingOver(Connection c,UUID tenant,String type,UUID owner,Subject opportunity,Task prior,ZoneId zone,Instant now)throws SQLException;
        void complete(Connection c,UUID tenant,Task task,Subject fact,Instant now)throws SQLException;
        default Task waitForReceipt(Connection c,UUID tenant,Task task,UUID actor,Instant now,Subject version)throws SQLException{throw new UnsupportedOperationException("Exact execution receipt wait port required");}
        default Task resumeReceipt(Connection c,UUID tenant,Task task,Subject version)throws SQLException{throw new UnsupportedOperationException("Exact execution receipt resumption port required");}
        void cancelForContract(Connection c,UUID tenant,Task task,String reason,Instant now)throws SQLException;
        default Subject negotiationWait(Connection c,UUID tenant,Task task)throws SQLException{throw new UnsupportedOperationException("Exact negotiation wait port required");}
        default void cancelForNegotiation(Connection c,UUID tenant,Task task,Subject disposition,Instant now)throws SQLException{throw new UnsupportedOperationException("Exact negotiation cancellation port required");}
        default Task createTerminationReview(Connection c,UUID tenant,UUID owner,Subject opportunity,Instant now,Instant due)throws SQLException{throw new UnsupportedOperationException("Termination review task port required");}
        default Task resumeAfterNegotiation(Connection c,UUID tenant,Task prior,Subject disposition,UUID cancelledMember,Instant now)throws SQLException{throw new UnsupportedOperationException("Exact negotiation resumption port required");}
        byte[] document(Connection c,Actor actor,UUID opportunity,UUID materialVersion)throws SQLException;
        List<Subject> documentFacts(Connection c,UUID tenant,UUID materialVersion)throws SQLException;
        boolean documentUsable(Connection c,UUID tenant,UUID materialVersion,String expectedSha)throws SQLException;
        String customerName(Connection c,UUID tenant,UUID opportunity)throws SQLException;
        List<Map<String,Object>> documents(Connection c,Actor actor,UUID opportunity)throws SQLException;
        boolean reviewScopeComplete(Connection c,UUID tenant,UUID opportunity)throws SQLException;
        Subject blockFinding(Connection c,UUID tenant,UUID opportunity,UUID actor,Subject finding,String reason)throws SQLException;
        Map<String,Object> acceptedQuote(Connection c,UUID tenant,UUID opportunity)throws SQLException;
    }
    /** Only inside a query transaction holding the shared business fence; caches facts, never authority. */
    default ReadScope lockedLedgerFacts(Connection c,UUID tenant)throws SQLException{return ()->{};}
    default void prepareLedgerCandidates(Connection c,UUID tenant,List<UUID> candidates,int batchSize)throws SQLException{}
    List<Subject> protectedFacts(Connection c,UUID tenant,UUID opportunity)throws SQLException;
    List<Subject> disclosureFacts(Connection c,Actor actor,UUID opportunity)throws SQLException;
    /** Bounded candidate scan; visibility and encrypted-label filters are applied after authorization. */
    List<UUID> ledgerOpportunities(Connection c,UUID tenant,int limit,UUID after)throws SQLException;
    List<RecoveryCandidate> recoveryCandidates(Connection c,Actor actor,int limit,UUID after)throws SQLException;
    RecoveryPage recoveryPage(Connection c,Actor actor,int limit,UUID after)throws SQLException;
    Subject reconcile(Connection c,Actor actor,Map<String,Object> payload)throws SQLException;
    Map<String,Object> ledgerContext(Connection c,Actor actor,UUID opportunity)throws SQLException;
    Map<String,Object> context(Connection c,Actor actor,UUID opportunity)throws SQLException;
    Subject execute(Connection c,String action,Actor actor,Map<String,Object> payload)throws SQLException;
    byte[] document(Connection c,Actor actor,UUID opportunity,UUID contract,UUID version)throws SQLException;
    default Generation generation(Connection c,Actor actor,Map<String,Object> payload)throws SQLException{throw new UnsupportedOperationException();}
    static ContractWorkflowService databaseBacked(ContractProtection protection,ContractPreparationSources sources,ContractPreparationRepository.Codec codec,Ports ports) {
        return new io.github.windyzhu3.ontologylaw.contract.internal.persistence.JdbcContractWorkflowService(protection,sources,codec,ports);
    }
    static ApprovalPolicy approvalPolicy(Connection c,UUID tenant,UUID organization)throws SQLException {
        return io.github.windyzhu3.ontologylaw.contract.internal.persistence.JdbcContractWorkflowService.approvalPolicy(c,tenant,organization);
    }
    /** Caller must authorize the exact selector before calling and audit before returning text. */
    static String confirmedReason(Connection c,UUID tenant,UUID opportunity,Subject exact,ContractProtection protection,ContractPreparationRepository.Codec codec)throws SQLException {
        return io.github.windyzhu3.ontologylaw.contract.internal.persistence.JdbcContractWorkflowHistory.confirmedReason(c,tenant,opportunity,exact,protection,codec);
    }
    static Metadata metadata(Connection c,UUID tenant,Subject exact)throws SQLException {
        return io.github.windyzhu3.ontologylaw.contract.internal.persistence.JdbcContractWorkflowMetadata.read(c,tenant,exact);
    }
}

