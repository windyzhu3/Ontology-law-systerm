package io.github.windyzhu3.ontologylaw.responsibility;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;import java.util.*;
/** Responsibility-owned decision on one exact reviewed transfer snapshot. */
public interface TransferIntakeDecisions {
 Subject record(Connection c,UUID tenant,UUID task,UUID actor,Subject snapshot,String outcome,String explanation)throws SQLException;
 static TransferIntakeDecisions databaseBacked(){return new io.github.windyzhu3.ontologylaw.responsibility.internal.persistence.JdbcTransferIntakeDecisions();}
}
