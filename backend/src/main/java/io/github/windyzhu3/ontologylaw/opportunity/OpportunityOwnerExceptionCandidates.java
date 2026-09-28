package io.github.windyzhu3.ontologylaw.opportunity;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Internal bounded scan. Position is technical cursor state, never an unprotected client-facing business selector. */
public interface OpportunityOwnerExceptionCandidates {
    record Position(Instant at,UUID id) {}
    record Candidate(Subject opportunity,Subject responsibilityBasis,Set<OpportunityOwnerExceptionService.Reason> reasons) {
        public Candidate { reasons=Set.copyOf(reasons); }
    }
    record Page(List<Candidate> candidates,Position lastScanned,int scanned,int restrictedDiagnostics,boolean exhausted) {
        public Page { candidates=List.copyOf(candidates); }
    }
    Page scan(Connection c,Actor service,Instant observed,Position after,int limit)throws SQLException;
    static OpportunityOwnerExceptionCandidates databaseBacked(OpportunityOwnerExceptionChecks checks){return new io.github.windyzhu3.ontologylaw.opportunity.internal.persistence.JooqOpportunityOwnerExceptionCandidates(checks);}
}
