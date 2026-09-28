package io.github.windyzhu3.ontologylaw.contract;

import java.sql.*;
import java.util.*;

/** Metadata-only Contract Owner read. Caller must authorize and audit before disclosure.
 * A returned archive basis is not execution permission. A mutation must re-read it under
 * its root lock and validate current negotiation, authority, task and execution conditions. */
public interface ContractExecutionSourceReader {
    /** Stable business trigger time, never the worker discovery/restart time. */
    record Source(ContractExecutionConditions.Basis basis,UUID opportunityId,java.time.Instant handoffAt) {
        public Source {Objects.requireNonNull(basis);Objects.requireNonNull(opportunityId);Objects.requireNonNull(handoffAt);}
    }
    /** Bounded keyset scan; caller authorizes each exact source before taking action.
     * The returned source alone does not prove it is unconsumed or currently actionable. */
    List<Source> page(Connection connection,UUID tenantId,int limit,UUID after)throws SQLException;
    Optional<ContractExecutionConditions.Basis> find(Connection connection,UUID tenantId,UUID handoffId)throws SQLException;
    static ContractExecutionSourceReader databaseBacked(){
        return new io.github.windyzhu3.ontologylaw.contract.internal.persistence.JdbcContractExecutionSourceReader();
    }
}
