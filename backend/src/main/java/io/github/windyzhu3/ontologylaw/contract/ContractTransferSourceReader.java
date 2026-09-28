package io.github.windyzhu3.ontologylaw.contract;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Exact executed-contract metadata for transfer. Not authority to submit or accept a case.
 * Commands must re-read under the contract lock and independently validate current materials,
 * customer basis, reviewer authority and intake responsibilities. */
public interface ContractTransferSourceReader {
 record Source(UUID tenantId, UUID opportunityId, UUID contractId, UUID revisionId,
               UUID executionId, UUID verificationId, UUID archiveId,
               Instant activatedAt, String activationDigest) {
  public Source {
   Objects.requireNonNull(tenantId);Objects.requireNonNull(opportunityId);Objects.requireNonNull(contractId);
   Objects.requireNonNull(revisionId);Objects.requireNonNull(executionId);Objects.requireNonNull(verificationId);
   Objects.requireNonNull(archiveId);Objects.requireNonNull(activatedAt);
   if(activationDigest==null||!activationDigest.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Exact activation digest required");
  }
 }
 Optional<Source> forOpportunity(Connection c,UUID tenant,UUID opportunity)throws SQLException;
 Optional<Source> find(Connection connection, UUID tenantId, UUID contractId) throws SQLException;
 /** Bounded keyset scan. Consumed sources remain visible; the transfer Owner enforces uniqueness. */
 List<Source> page(Connection connection, UUID tenantId, int limit, UUID afterContractId) throws SQLException;
 static ContractTransferSourceReader databaseBacked(){return new io.github.windyzhu3.ontologylaw.contract.internal.persistence.JdbcContractTransferSourceReader();}
}
