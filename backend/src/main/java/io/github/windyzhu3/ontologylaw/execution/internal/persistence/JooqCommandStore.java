package io.github.windyzhu3.ontologylaw.execution.internal.persistence;

import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.nio.ByteBuffer;
import org.jooq.*;
import org.jooq.impl.DSL;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.jooq.Tables.*;

public final class JooqCommandStore {
    private final Connection connection;private final DSLContext db;
    public JooqCommandStore(Connection connection){this.connection=connection;db=DSL.using(connection,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false));}
    public UUID newId()throws SQLException{try(var p=connection.prepareStatement("select uuidv7()");var r=p.executeQuery()){r.next();return r.getObject(1,UUID.class);}}
    public OffsetDateTime now()throws SQLException{try(var p=connection.prepareStatement("select clock_timestamp()");var r=p.executeQuery()){r.next();return r.getObject(1,OffsetDateTime.class);}}
    public void lockCommand(CommandEnvelope e)throws SQLException {
        long key=ByteBuffer.wrap(CanonicalJson.digest("R1_COMMAND_UUID_LOCK_V1:"+e.actor().tenantId()+":"+e.commandId())).getLong();
        try(var p=connection.prepareStatement("select pg_advisory_xact_lock(?)")){p.setLong(1,key);p.execute();}
    }
    public CommandOutcome existing(CommandEnvelope e,CommandScope scope,byte[] payloadDigest)throws SQLException {
        var s=COMMAND_EXECUTION_SLOT;var r=COMMAND_RECEIPT;
        var slots=db.selectFrom(s).where(s.TENANT_ID.eq(e.actor().tenantId())).and(s.COMMAND_ID.eq(e.commandId())).fetch();
        if(slots.isEmpty())return null;
        if(slots.size()!=1)throw new SQLException("Ambiguous command identity","23000");
        var slot=slots.getFirst();
        var receipt=db.selectFrom(r).where(r.TENANT_ID.eq(e.actor().tenantId())).and(r.COMMAND_EXECUTION_SLOT_ID.eq(slot.get(s.COMMAND_EXECUTION_SLOT_ID))).fetchOne();
        if(receipt==null)throw new SQLException("Orphan command slot","23000");
        boolean conflict=!e.type().envelope().name().equals(slot.get(s.ENVELOPE_TYPE)) || !e.type().name().equals(slot.get(s.COMMAND_TYPE))
                || !Arrays.equals(scope.digest(),slot.get(s.COMMAND_SCOPE_DIGEST)) || !Arrays.equals(payloadDigest,slot.get(s.PAYLOAD_DIGEST));
        Subject fact=receipt.get(r.RESULT_FACT_TYPE)==null?null:new Subject(receipt.get(r.RESULT_FACT_TYPE),receipt.get(r.RESULT_FACT_ID),receipt.get(r.RESULT_FACT_REVISION),receipt.get(r.RESULT_FACT_HASH)==null?null:Base64.getUrlEncoder().withoutPadding().encodeToString(receipt.get(r.RESULT_FACT_HASH)));
        return new CommandOutcome(receipt.get(r.COMMAND_RECEIPT_ID),CommandOutcome.Status.valueOf(receipt.get(r.OUTCOME)),fact,receipt.get(r.REJECTION_CODE),conflict);
    }
    public UUID occupy(CommandEnvelope e,CommandScope scope,byte[] payload)throws SQLException {
        UUID id=newId();var s=COMMAND_EXECUTION_SLOT;
        db.insertInto(s).set(s.TENANT_ID,e.actor().tenantId()).set(s.COMMAND_EXECUTION_SLOT_ID,id).set(s.COMMAND_ID,e.commandId()).set(s.ENVELOPE_TYPE,e.type().envelope().name()).set(s.COMMAND_TYPE,e.type().name()).set(s.COMMAND_SCOPE_DIGEST,scope.digest()).set(s.PAYLOAD_DIGEST,payload).set(s.OCCUPIED_AT,now()).execute();return id;
    }
    public CommandOutcome receipt(CommandEnvelope e,UUID slot,CommandOutcome.Status status,Subject fact,String rejection)throws SQLException {
        UUID id=newId();var r=COMMAND_RECEIPT;
        db.insertInto(r).set(r.TENANT_ID,e.actor().tenantId()).set(r.COMMAND_RECEIPT_ID,id).set(r.COMMAND_EXECUTION_SLOT_ID,slot).set(r.OUTCOME,status.name()).set(r.REJECTION_CODE,rejection).set(r.COMPLETED_AT,now())
                .set(r.RESULT_FACT_TYPE,fact==null?null:fact.type()).set(r.RESULT_FACT_ID,fact==null?null:fact.id()).set(r.RESULT_FACT_REVISION,fact==null?null:fact.revision()).set(r.RESULT_FACT_HASH,fact==null || fact.hash()==null?null:Base64.getUrlDecoder().decode(fact.hash())).execute();
        return new CommandOutcome(id,status,fact,rejection,false);
    }
    public void event(CommandEnvelope e,CommandHandler.Result result)throws SQLException {
        for(var notification:result.notifications())event(e,notification);
    }
    private void event(CommandEnvelope e,CommandHandler.Notification notification)throws SQLException {
        UUID id=newId();var d=DOMAIN_EVENT;var o=DOMAIN_EVENT_OUTBOX;var fact=notification.sourceFact();var now=now();String payload="{}";
        db.insertInto(d).set(d.TENANT_ID,e.actor().tenantId()).set(d.DOMAIN_EVENT_ID,id).set(d.EVENT_TYPE,notification.event().name()).set(d.EVENT_SCHEMA_VERSION,notification.event().schemaVersion()).set(d.EVENT_PAYLOAD,JSONB.valueOf(payload)).set(d.PAYLOAD_DIGEST,CanonicalJson.digest(payload))
                .set(d.COMMAND_ID,e.commandId()).set(d.CORRELATION_ID,e.correlationId()).set(d.OCCURRED_AT,now).set(d.SOURCE_FACT_TYPE,fact.type()).set(d.SOURCE_FACT_ID,fact.id()).set(d.SOURCE_FACT_REVISION,fact.revision()).set(d.SOURCE_FACT_HASH,fact.hash()==null?null:Base64.getUrlDecoder().decode(fact.hash())).execute();
        for(var owner:notification.event().queueOwners())db.insertInto(o).set(o.TENANT_ID,e.actor().tenantId()).set(o.DOMAIN_EVENT_OUTBOX_ID,newId()).set(o.DOMAIN_EVENT_ID,id).set(o.QUEUE_OWNER,owner.name()).set(o.STATUS,"PENDING").set(o.AVAILABLE_AT,now).execute();
    }
}
