package io.github.windyzhu3.ontologylaw.execution.internal.persistence;

import io.github.windyzhu3.ontologylaw.execution.R1BusinessFence;
import java.sql.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

public final class JooqR1BusinessFence implements R1BusinessFence {
    public void shared(Connection c,UUID tenant)throws SQLException {lock(c,tenant,true);}
    public void exclusive(Connection c,UUID tenant)throws SQLException {lock(c,tenant,false);}
    private static void lock(Connection c,UUID tenant,boolean shared)throws SQLException {
        Objects.requireNonNull(tenant);
        if(c.getAutoCommit() || c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)
            throw new SQLException("R1 business fence requires READ COMMITTED transaction","25001");
        final long key;
        try {key=ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(
                ("R1_BUSINESS_TENANT_LOCK_V1:"+tenant).getBytes(StandardCharsets.UTF_8))).getLong();}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        try(var p=c.prepareStatement(shared?"select pg_advisory_xact_lock_shared(?)":"select pg_advisory_xact_lock(?)")) {
            p.setLong(1,key);p.execute();
        }
    }
}
