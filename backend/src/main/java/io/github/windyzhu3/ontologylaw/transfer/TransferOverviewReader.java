package io.github.windyzhu3.ontologylaw.transfer;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.time.Instant;
import java.util.*;
/** First completed formation facts, without document bodies or authority decisions. */
public interface TransferOverviewReader {
 record Candidate(UUID id,UUID opportunityId,Instant occurredAt,List<Subject> facts,UUID requestId,UUID fromOrganization,UUID toOrganization){public Candidate{facts=List.copyOf(facts);}}
 List<Candidate> scan(Connection c,UUID tenant,Instant from,Instant until,UUID after,int limit)throws SQLException;
 static TransferOverviewReader databaseBacked(){return new io.github.windyzhu3.ontologylaw.transfer.internal.persistence.JdbcTransferOverviewReader();}
}
