package io.github.windyzhu3.ontologylaw.execution;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Tenant-bound technical queue capability; each operation owns a short WORKER transaction. */
public interface R1ProjectionOutboxPort {
    @FunctionalInterface interface Connections {Connection open()throws SQLException;}
    record Claim(UUID tenantId,UUID outboxId,UUID eventId,long revision,String leaseOwner,long fencingToken,int attempt,Instant leaseUntil) {}
    record Counts(int pending,int claimed,int delivered,int exhausted) {}
    /** Counts only transitions whose WORKER transaction completed successfully. */
    record ReapResult(int reaped,int exhausted) {}
    List<Claim> claim(UUID tenant,String owner,int limit)throws SQLException;
    boolean ack(Claim claim)throws SQLException;
    boolean retry(Claim claim,String code)throws SQLException;
    boolean exhaust(Claim claim,String code)throws SQLException;
    ReapResult reap(UUID tenant,int limit)throws SQLException;
    Counts counts(UUID tenant,int limit)throws SQLException;
    static R1ProjectionOutboxPort databaseBacked(Connections connections){return new io.github.windyzhu3.ontologylaw.execution.internal.persistence.JooqR1ProjectionOutboxPort(connections);}
}
