package io.github.windyzhu3.ontologylaw.identity;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Identity-owned current qualification and historical organization reads for T01. No implicit grants. */
public interface OpportunityOwnerExceptionAuthorityReader {
    record Assessment(boolean active, boolean authorized, boolean denied, List<AuthorizationSnapshot> evidence) {
        public Assessment { evidence=List.copyOf(evidence); }
    }
    String restrictedOrganizationLabel(Connection c,UUID tenant,UUID organization)throws SQLException;
    boolean hasAuthority(Connection c,Actor actor,String authority,Instant now)throws SQLException;
    List<UUID> receiverAppointments(Connection c,UUID tenant,UUID after,int limit)throws SQLException;
    UUID historicalOrganization(Connection c,UUID tenant,UUID appointment)throws SQLException;
    Assessment receiver(Connection c,UUID tenant,UUID appointment,UUID organization,List<Subject> protectedFacts,Instant now)throws SQLException;
    Assessment receiver(Connection c,UUID tenant,UUID appointment,UUID organization,List<Subject> protectedFacts,Instant now,String taskAuthority)throws SQLException;
    boolean permitted(Connection c,Actor actor,UUID organization,List<Subject> protectedFacts,String authority)throws SQLException;
    List<UUID> supervisors(Connection c,UUID tenant,UUID organization,List<Subject> protectedFacts)throws SQLException;
    static OpportunityOwnerExceptionAuthorityReader databaseBacked(){return new io.github.windyzhu3.ontologylaw.identity.internal.persistence.JooqOpportunityOwnerExceptionAuthorityReader();}
}
