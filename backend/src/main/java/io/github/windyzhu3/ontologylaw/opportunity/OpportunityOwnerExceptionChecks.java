package io.github.windyzhu3.ontologylaw.opportunity;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationSnapshot;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Production qualification logic with explicit cross-Owner reads supplied by the application assembly. */
public interface OpportunityOwnerExceptionChecks extends OpportunityOwnerExceptionService.ObservationProbe,OpportunityOwnerExceptionService.ReceiverGuard {
    record TaskState(Subject task,UUID owner,String state,Subject waitReceipt,List<Subject> protectedSources,boolean lineageValid,String authorityCode) {
        public TaskState(Subject task,UUID owner,String state,Subject waitReceipt,List<Subject> protectedSources,boolean lineageValid){this(task,owner,state,waitReceipt,protectedSources,lineageValid,"SALES_OPPORTUNITY_OWNER");}
        public TaskState { protectedSources=List.copyOf(protectedSources);if(!Set.of("SALES_OPPORTUNITY_OWNER","QUOTE_PREPARE","QUOTE_DELIVER","QUOTE_RESPONSE").contains(authorityCode))throw new IllegalArgumentException("Unsupported owner authority"); }
    }
    @FunctionalInterface interface TaskStateReader {
        TaskState current(Connection c,UUID tenant,Subject opportunity,OpportunityResponsibilityReader.Responsibility responsibility)throws SQLException;
    }
    /** Read-only exact evidence lookup. Null means no durable validation evidence exists; resolution then fails closed. */
    @FunctionalInterface interface ValidationEvidenceReader {
        Subject existing(Connection c,UUID tenant,Subject opportunity,OpportunityResponsibilityReader.Responsibility responsibility,List<AuthorizationSnapshot> authorizationEvidence,Instant observedAt)throws SQLException;
    }
    boolean canDiscover(Connection c,Actor service,Subject opportunity,Set<UUID> authorizedOrganizations)throws SQLException;
    static OpportunityOwnerExceptionChecks databaseBacked(OpportunityTaskActivationService.OpeningSourceReader sources,TaskStateReader tasks,ValidationEvidenceReader evidence) {
        return new io.github.windyzhu3.ontologylaw.opportunity.internal.persistence.JooqOpportunityOwnerExceptionChecks(sources,tasks,evidence);
    }
}
