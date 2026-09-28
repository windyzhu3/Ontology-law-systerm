package io.github.windyzhu3.ontologylaw.responsibility;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;import java.sql.*;import java.util.*;
/** Responsibility-owned explicit blocking decision on one exact conflict finding. */
public interface ContractConflictDecisions {
 Subject block(Connection c,UUID tenant,UUID opportunity,UUID actor,Subject finding,String reason)throws SQLException;
 static ContractConflictDecisions databaseBackedForTransfer(){return new io.github.windyzhu3.ontologylaw.responsibility.internal.persistence.JdbcContractConflictDecisions(true);}
 static ContractConflictDecisions databaseBacked(){return new io.github.windyzhu3.ontologylaw.responsibility.internal.persistence.JdbcContractConflictDecisions();}
}
