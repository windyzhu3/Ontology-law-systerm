package io.github.windyzhu3.ontologylaw.opportunity;
import java.sql.*;
import java.time.Instant;
import java.util.*;
/** Bounded Owner scan; caller separately checks Responsibility existence and current authorization. */
public interface OpportunityActivationCandidates {
    record Position(Instant at,UUID id) {}
    boolean currentOpen(Connection c,UUID tenant,io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject opportunity)throws SQLException;
    /** A persisted quote or accepted contract source has already taken over ordinary followup. */
    boolean salesProgressed(Connection c,UUID tenant,UUID opportunity)throws SQLException;
    List<Position> scan(Connection c,UUID tenant,Set<UUID> owners,Instant observed,Position after,int limit)throws SQLException;
    static OpportunityActivationCandidates databaseBacked(){return new io.github.windyzhu3.ontologylaw.opportunity.internal.persistence.JooqOpportunityActivationCandidates();}
}
