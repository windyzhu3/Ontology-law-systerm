package io.github.windyzhu3.ontologylaw.execution.internal.persistence;

import io.github.windyzhu3.ontologylaw.execution.CanonicalJson;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.jooq.*;
import org.jooq.impl.DSL;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.jooq.Tables.*;

/** Bootstrap Slot/Receipt closure, deliberately independent of business Actor/CommandEnvelope. */
public final class JooqIdentityBootstrapStore {
    private final DSLContext db;private final Connection connection;
    public JooqIdentityBootstrapStore(Connection c)throws SQLException {if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw new SQLException("Bootstrap requires transaction","25001");connection=c;db=DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false));}
    public void tenantCodeFence(String code)throws SQLException{lock("R1_IDENTITY_BOOTSTRAP_TENANT_CODE_V1:"+code);}
    public void commandFence(UUID tenant,UUID command)throws SQLException{lock("R1_COMMAND_UUID_LOCK_V1:"+tenant+":"+command);}
    private void lock(String purpose)throws SQLException {try(var p=connection.prepareStatement("select pg_advisory_xact_lock(?)")){p.setLong(1,java.nio.ByteBuffer.wrap(CanonicalJson.digest(purpose)).getLong());p.execute();}}
    public UUID original(UUID tenant,UUID command,byte[] scope,byte[] digest)throws SQLException {
        var s=COMMAND_EXECUTION_SLOT;var r=COMMAND_RECEIPT;
        var slots=db.selectFrom(s).where(s.TENANT_ID.eq(tenant)).and(s.COMMAND_ID.eq(command)).limit(2).fetch();if(slots.isEmpty())return null;if(slots.size()!=1)throw invalid();var slot=slots.getFirst();
        var rows=db.selectFrom(r).where(r.TENANT_ID.eq(tenant)).and(r.COMMAND_EXECUTION_SLOT_ID.eq(slot.get(s.COMMAND_EXECUTION_SLOT_ID))).limit(2).fetch();if(rows.size()!=1)throw invalid();var receipt=rows.getFirst();
        if(!"INTERNAL_ADMIN".equals(slot.get(s.ENVELOPE_TYPE))||!"BOOTSTRAP_IDENTITY_ADMIN".equals(slot.get(s.COMMAND_TYPE))||!java.security.MessageDigest.isEqual(scope,slot.get(s.COMMAND_SCOPE_DIGEST))||!java.security.MessageDigest.isEqual(digest,slot.get(s.PAYLOAD_DIGEST))
                ||!"SUCCEEDED".equals(receipt.get(r.OUTCOME))||receipt.get(r.REJECTION_CODE)!=null||!"identity.tenant".equals(receipt.get(r.RESULT_FACT_TYPE))||!tenant.equals(receipt.get(r.RESULT_FACT_ID))||!Long.valueOf(0).equals(receipt.get(r.RESULT_FACT_REVISION))||receipt.get(r.RESULT_FACT_HASH)!=null)throw invalid();
        return receipt.get(r.COMMAND_RECEIPT_ID);
    }
    public UUID write(UUID tenant,UUID command,byte[] scope,byte[] digest,Instant created)throws SQLException {
        var s=COMMAND_EXECUTION_SLOT;var r=COMMAND_RECEIPT;UUID slot=UUID.randomUUID(),receipt=UUID.randomUUID();var at=OffsetDateTime.ofInstant(created,ZoneOffset.UTC);
        db.insertInto(s).set(s.TENANT_ID,tenant).set(s.COMMAND_EXECUTION_SLOT_ID,slot).set(s.COMMAND_ID,command).set(s.ENVELOPE_TYPE,"INTERNAL_ADMIN").set(s.COMMAND_TYPE,"BOOTSTRAP_IDENTITY_ADMIN").set(s.COMMAND_SCOPE_DIGEST,scope).set(s.PAYLOAD_DIGEST,digest).set(s.OCCUPIED_AT,at).execute();
        db.insertInto(r).set(r.TENANT_ID,tenant).set(r.COMMAND_RECEIPT_ID,receipt).set(r.COMMAND_EXECUTION_SLOT_ID,slot).set(r.OUTCOME,"SUCCEEDED").set(r.RESULT_FACT_TYPE,"identity.tenant").set(r.RESULT_FACT_ID,tenant).set(r.RESULT_FACT_REVISION,0L).set(r.COMPLETED_AT,at).execute();return receipt;
    }
    private static SQLException invalid(){return new SQLException("BOOTSTRAP_COMMAND_CONFLICT","23000");}
}
