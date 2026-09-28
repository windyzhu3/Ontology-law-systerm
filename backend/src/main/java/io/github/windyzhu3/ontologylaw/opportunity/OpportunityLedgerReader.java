package io.github.windyzhu3.ontologylaw.opportunity;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
/** Public Owner port: metadata first; protected progress body only after caller's exact authorization. */
public interface OpportunityLedgerReader {
 record Position(Instant at,UUID id){}
 record Header(Subject selector,boolean closed){}
 record Progress(Subject selector,Instant occurredAt){}
 List<Position> scan(Connection c,UUID tenant,Instant observed,Position after,int limit)throws SQLException;
 Header header(Connection c,UUID tenant,UUID id)throws SQLException;
 Progress latestProgress(Connection c,UUID tenant,UUID opportunity)throws SQLException;
 /** Bounded immutable confirmed records, newest first. Never returns drafts or attempts. */
 List<Progress> progressHistory(Connection c,UUID tenant,UUID opportunity,int limit)throws SQLException;
 String progressBody(Connection c,UUID tenant,UUID opportunity,Subject exact)throws SQLException;
 static OpportunityLedgerReader databaseBacked(OpportunityProgressProtection p){return new io.github.windyzhu3.ontologylaw.opportunity.internal.persistence.JooqOpportunityLedgerReader(p);}
}
