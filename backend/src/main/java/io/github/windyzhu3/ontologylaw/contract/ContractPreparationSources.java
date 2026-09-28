package io.github.windyzhu3.ontologylaw.contract;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * Revalidates durable sources in the caller's authorized, audited READ COMMITTED command transaction.
 * This is not an authorization API. The caller must revalidate authority and all protected facts,
 * and persist the version in the same transaction; the returned value is not a reusable approval.
 */
public interface ContractPreparationSources {
    sealed interface Selection permits AcceptedResponse,DirectDecision {}
    record AcceptedResponse(UUID responseId) implements Selection {
        public AcceptedResponse { Objects.requireNonNull(responseId); }
    }
    record DirectDecision(UUID decisionId) implements Selection {
        public DirectDecision { Objects.requireNonNull(decisionId); }
    }
    /** Trusted composition port; authenticated plaintext is verified before commercial decoding. */
    interface QuoteBody {
        String decrypt(UUID tenant,UUID opportunity,UUID quote,byte[] encrypted);
        ContractVersionInput.CommercialTerms commercial(String clear,ContractPreparationSource.Basis expected,Instant validUntil);
    }
    final class Unavailable extends IllegalStateException {
        public Unavailable() { super("Contract preparation source unavailable or changed"); }
    }
    ContractPreparationSource resolve(Connection connection,ContractPreparationSource.Basis expected,Selection selection)throws SQLException;
    static ContractPreparationSources databaseBacked(QuoteBody body) {
        return new io.github.windyzhu3.ontologylaw.contract.internal.persistence.JdbcContractPreparationSources(body);
    }
}
