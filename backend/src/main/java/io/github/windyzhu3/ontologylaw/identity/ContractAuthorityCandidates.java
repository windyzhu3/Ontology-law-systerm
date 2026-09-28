package io.github.windyzhu3.ontologylaw.identity;
import java.sql.*;import java.util.*;
/** Identity-owned discovery only. Every candidate still requires current four-axis authorization. */
public interface ContractAuthorityCandidates {
 List<UUID> appointments(Connection c,UUID tenant,String authority,UUID after,int limit)throws SQLException;
 static ContractAuthorityCandidates databaseBacked(){return new io.github.windyzhu3.ontologylaw.identity.internal.persistence.JdbcContractAuthorityCandidates();}
}
