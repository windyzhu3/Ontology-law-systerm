package io.github.windyzhu3.ontologylaw.contract;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;import java.util.*;
/** Minimal immutable contract version label; never returns protected contract contents. */
public interface ContractLedgerBasisReader {
 record Basis(int version,List<Subject> facts){}
 Basis revision(Connection c,UUID tenant,UUID revision)throws SQLException;
 Basis executed(Connection c,UUID tenant,UUID contract)throws SQLException;
 static ContractLedgerBasisReader databaseBacked(){return new io.github.windyzhu3.ontologylaw.contract.internal.persistence.JdbcContractLedgerBasisReader();}
}
