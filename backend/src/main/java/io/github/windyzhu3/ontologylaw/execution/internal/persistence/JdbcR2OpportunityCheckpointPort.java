package io.github.windyzhu3.ontologylaw.execution.internal.persistence;

import io.github.windyzhu3.ontologylaw.execution.R2OpportunityCheckpointPort;
import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.nio.ByteBuffer;
import java.util.Objects;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;

/** Owns a physical session; connection suppliers must not return transaction-pooled connections. */
public final class JdbcR2OpportunityCheckpointPort implements R2OpportunityCheckpointPort {
    private static final String WHERE="tenant_id=? and principal_id=? and appointment_id=? and scan_kind=?";
    private final Connections connections;
    public JdbcR2OpportunityCheckpointPort(Connections connections){this.connections=Objects.requireNonNull(connections);}
    private static long lockId(Key key) {
        try { return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(("R2_OPPORTUNITY_CHECKPOINT_V1:"+key.tenantId()+":"+key.principalId()+":"+key.appointmentId()+":"+key.kind()).getBytes(StandardCharsets.UTF_8))).getLong(); }
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static void bind(PreparedStatement q, Key key, int offset)throws SQLException {
        q.setObject(offset,key.tenantId());q.setObject(offset+1,key.principalId());q.setObject(offset+2,key.appointmentId());q.setString(offset+3,key.kind());
    }
    public Session tryOpen(Key key)throws SQLException {
        Objects.requireNonNull(key);var c=connections.open();long lock=lockId(key);
        try {
            if(!c.getAutoCommit())throw new SQLException("Checkpoint requires an owned idle session","25001");
            boolean acquired=inTransaction(c,Capability.WORKER,x->{try(var q=x.prepareStatement("select pg_try_advisory_lock(?)")){q.setLong(1,lock);try(var r=q.executeQuery()){r.next();return r.getBoolean(1);}}});
            if(!acquired){c.close();return null;}
            return new Session(){
                long revision=-1;boolean loaded,closed;
                private void open()throws SQLException{if(closed||c.isClosed())throw new SQLException("Checkpoint session closed","08003");}
                public byte[] load()throws SQLException {
                    open();return inTransaction(c,Capability.WORKER,x->{try(var q=x.prepareStatement("select revision,checkpoint_body from platform_meta.r2_opportunity_checkpoint where "+WHERE)){
                        bind(q,key,1);try(var r=q.executeQuery()){loaded=true;if(!r.next()){revision=-1;return null;}revision=r.getLong(1);return r.getBytes(2);}
                    }});
                }
                public void save(byte[] body)throws SQLException {
                    open();if(!loaded||body==null||body.length<1||body.length>65536||revision>=9007199254740991L)throw new SQLException("Invalid checkpoint write","22023");
                    final long previous=revision;
                    inTransaction(c,Capability.WORKER,x->{
                        String sql=previous<0?"insert into platform_meta.r2_opportunity_checkpoint(tenant_id,principal_id,appointment_id,scan_kind,checkpoint_body,revision,updated_at) values (?,?,?,?,?,0,clock_timestamp())":"update platform_meta.r2_opportunity_checkpoint set checkpoint_body=?,revision=revision+1,updated_at=clock_timestamp() where "+WHERE+" and revision=?";
                        try(var q=x.prepareStatement(sql)){
                            if(previous<0){bind(q,key,1);q.setBytes(5,body);}else{q.setBytes(1,body);bind(q,key,2);q.setLong(6,previous);}
                            if(q.executeUpdate()!=1)throw new SQLException("Checkpoint compare-and-set failed","40001");
                        }return null;
                    });revision=previous+1;
                }
                public void close()throws SQLException {
                    if(closed)return;closed=true;
                    try { if(!c.isClosed())try(var q=c.prepareStatement("select pg_advisory_unlock(?)")){q.setLong(1,lock);q.execute();} }
                    finally { c.close(); }
                }
            };
        } catch(SQLException|RuntimeException failure){try{c.close();}catch(SQLException close){failure.addSuppressed(close);}throw failure;}
    }
}
