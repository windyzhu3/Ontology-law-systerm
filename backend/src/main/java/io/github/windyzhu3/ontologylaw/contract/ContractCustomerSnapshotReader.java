package io.github.windyzhu3.ontologylaw.contract;
import java.sql.*;import java.util.UUID;
/** Revalidates the confirmed customer parties through the Contract Owner, without exposing persistence to API composition. */
public final class ContractCustomerSnapshotReader {
 private final ContractVersionRepository versions;
 private ContractCustomerSnapshotReader(ContractVersionRepository versions){this.versions=versions;}
 public static ContractCustomerSnapshotReader databaseBacked(ContractProtection protection,ContractPreparationSources sources,ContractPreparationRepository.Codec codec){return new ContractCustomerSnapshotReader(ContractVersionRepository.databaseBacked(protection,sources,codec));}
 public String digest(Connection c,UUID tenant,UUID confirmation)throws SQLException{return versions.partySnapshotDigest(c,tenant,confirmation);}
}
