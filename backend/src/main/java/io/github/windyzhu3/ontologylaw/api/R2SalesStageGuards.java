package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.contract.OpportunityContractReader;
import io.github.windyzhu3.ontologylaw.opportunity.OpportunityActivationCandidates;
import java.sql.*;
import java.util.UUID;

/** Stage facts remain with their Owners; this composition never changes business state. */
final class R2SalesStageGuards {
    private R2SalesStageGuards() {}
    static boolean initialFollowupAllowed(Connection c,UUID tenant,UUID opportunity)throws SQLException {
        return !OpportunityActivationCandidates.databaseBacked().salesProgressed(c,tenant,opportunity)
            && !contractTakenOver(c,tenant,opportunity);
    }
    static boolean contractTakenOver(Connection c,UUID tenant,UUID opportunity)throws SQLException {
        return !OpportunityContractReader.databaseBacked().takeoverFacts(c,tenant,opportunity).isEmpty();
    }
}
