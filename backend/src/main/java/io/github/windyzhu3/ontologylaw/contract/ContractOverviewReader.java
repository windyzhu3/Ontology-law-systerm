package io.github.windyzhu3.ontologylaw.contract;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.time.Instant;
import java.util.*;
/** First completed formation facts, without document bodies or authority decisions. */
public interface ContractOverviewReader {
 record Candidate(UUID id,UUID opportunityId,Instant occurredAt,List<Subject> facts){public Candidate{facts=List.copyOf(facts);}}
 List<Candidate> scan(Connection c,UUID tenant,Instant from,Instant until,UUID after,int limit)throws SQLException;
 static ContractOverviewReader databaseBacked(){return new io.github.windyzhu3.ontologylaw.contract.internal.persistence.JdbcContractOverviewReader();}
}
