package io.github.windyzhu3.ontologylaw.opportunity;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.time.Instant;
import java.util.*;
/** Metadata candidates only. Aggregate/detail callers must authorize and audit the exact sources. */
public interface OpportunityOverviewReader {
 record Candidate(Subject opportunity,Instant occurredAt){}
 List<Candidate> scan(Connection c,UUID tenant,Instant from,Instant until,UUID after,int limit)throws SQLException;
 static OpportunityOverviewReader databaseBacked(){return new io.github.windyzhu3.ontologylaw.opportunity.internal.persistence.JdbcOpportunityOverviewReader();}
}
