package io.github.windyzhu3.ontologylaw.transfer;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.util.*;
/** Transfer-owned exact downstream existence read. */
public interface OpportunityTransferReader {
    List<Subject> forOpportunity(Connection c,UUID tenant,UUID opportunity)throws SQLException;
    /** Complete bounded review basis; never silently truncates downstream facts. */
    List<Subject> reviewFactsForOpportunity(Connection c,UUID tenant,UUID opportunity)throws SQLException;
    static OpportunityTransferReader databaseBacked(){return new io.github.windyzhu3.ontologylaw.transfer.internal.persistence.JdbcOpportunityTransferReader();}
}
