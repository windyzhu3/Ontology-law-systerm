package io.github.windyzhu3.ontologylaw.identity;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor;
import java.sql.*;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
/** Exact selected SERVICE appointment coverage; object grants and delegation cannot substitute. */
public interface R2OpportunityServiceScopeReader {
    R1ServiceAuthorityReader.DueScope opportunityScope(Connection c,Actor actor,String authority,Instant checkedAt)throws SQLException;
    /** Coverage only. Callers still authorize every source and exact object before disclosure or mutation. */
    record OwnerExceptionScope(boolean authorized, Set<UUID> organizations) {
        public OwnerExceptionScope { organizations=Set.copyOf(organizations); }
    }
    OwnerExceptionScope ownerExceptionScope(Connection c, Actor actor, Instant checkedAt) throws SQLException;
    static R2OpportunityServiceScopeReader databaseBacked(){return new io.github.windyzhu3.ontologylaw.identity.internal.persistence.JooqR1ServiceAuthorityReader();}
}
