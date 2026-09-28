package io.github.windyzhu3.ontologylaw.opportunity;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.util.UUID;

/** Named Owner reads for internal command composition, not a public disclosure endpoint. */
public interface OpportunityCommandReader {
    record Header(Subject selector,UUID owner,boolean closed) {}
    record Progress(Subject selector,UUID opportunity,String canonicalBody) {
        @Override public String toString(){return "Progress[protected]";}
    }
    Header header(Connection c,UUID tenant,UUID id)throws SQLException;
    void lock(Connection c,UUID tenant,UUID id)throws SQLException;
    Progress progress(Connection c,UUID tenant,UUID id)throws SQLException;
    static OpportunityCommandReader databaseBacked(OpportunityProgressProtection protection){return new io.github.windyzhu3.ontologylaw.opportunity.internal.persistence.JooqOpportunityCommandReader(protection);}
}
