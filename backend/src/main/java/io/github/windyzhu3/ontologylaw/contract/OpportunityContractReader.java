package io.github.windyzhu3.ontologylaw.contract;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.util.*;
/** Contract-owned exact downstream existence read. */
public interface OpportunityContractReader {
    record Negotiation(Subject selector,String kind,Subject assignment,java.time.Instant dueAt){}
    /** Metadata only; the caller must authorize and audit these exact facts before displaying them. */
    default Negotiation negotiation(Connection c,UUID tenant,UUID opportunity)throws SQLException{return null;}
    List<Subject> forOpportunity(Connection c,UUID tenant,UUID opportunity)throws SQLException;
    /** Includes preparation responsibility before a contract anchor is formed. No protected body reads. */
    List<Subject> takeoverFacts(Connection c,UUID tenant,UUID opportunity)throws SQLException;
    static OpportunityContractReader databaseBacked(){return new io.github.windyzhu3.ontologylaw.contract.internal.persistence.JdbcOpportunityContractReader();}
}
