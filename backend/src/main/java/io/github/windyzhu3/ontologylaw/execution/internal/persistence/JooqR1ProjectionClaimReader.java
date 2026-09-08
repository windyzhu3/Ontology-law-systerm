package io.github.windyzhu3.ontologylaw.execution.internal.persistence;

import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.util.*;
import org.jooq.*;
import org.jooq.impl.DSL;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.jooq.Tables.*;

public final class JooqR1ProjectionClaimReader implements R1ProjectionClaimReader {
    public Notification read(Connection c,UUID tenant,Token token) {
        if(token==null||token.outboxId()==null||token.eventId()==null||token.revision()==null||token.revision()<0||token.revision()>9007199254740991L||token.fencingToken()==null||token.fencingToken()<1||token.fencingToken()>9007199254740991L||token.leaseOwner()==null||!token.leaseOwner().matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}"))throw failure(400,"VALIDATION_FAILED");
        var db=DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false));var o=DOMAIN_EVENT_OUTBOX;var e=DOMAIN_EVENT;
        var row=db.selectFrom(o).where(o.TENANT_ID.eq(tenant)).and(o.DOMAIN_EVENT_OUTBOX_ID.eq(token.outboxId())).fetchOne();
        if(row==null)throw failure(404,"NOT_FOUND");
        if(!Objects.equals(row.get(o.DOMAIN_EVENT_ID),token.eventId())||!"R1_PROJECTION".equals(row.get(o.QUEUE_OWNER)))throw failure(422,"PROJECTION_EVENT_INVALID");
        if(!"CLAIMED".equals(row.get(o.STATUS))||!Objects.equals(row.get(o.REVISION),token.revision())||!Objects.equals(row.get(o.LEASE_OWNER),token.leaseOwner())||!Objects.equals(row.get(o.FENCING_TOKEN),token.fencingToken())
            ||!db.fetchExists(DSL.selectOne().from(o).where(o.TENANT_ID.eq(tenant)).and(o.DOMAIN_EVENT_OUTBOX_ID.eq(token.outboxId())).and(o.STATUS.eq("CLAIMED")).and(o.REVISION.eq(token.revision())).and(o.LEASE_OWNER.eq(token.leaseOwner())).and(o.FENCING_TOKEN.eq(token.fencingToken())).and(o.LEASE_UNTIL.gt(DSL.field("clock_timestamp()",java.time.OffsetDateTime.class)))))throw failure(409,"STALE_OUTBOX_CLAIM");
        var event=db.selectFrom(e).where(e.TENANT_ID.eq(tenant)).and(e.DOMAIN_EVENT_ID.eq(token.eventId())).fetchOne();if(event==null)throw failure(404,"NOT_FOUND");
        try {
            var type=CommandHandler.Event.valueOf(event.get(e.EVENT_TYPE));var revision=event.get(e.SOURCE_FACT_REVISION);var hash=event.get(e.SOURCE_FACT_HASH);
            if(event.get(e.EVENT_SCHEMA_VERSION)!=1||!"{}".equals(event.get(e.EVENT_PAYLOAD).data())||!Arrays.equals(event.get(e.PAYLOAD_DIGEST),HexFormat.of().parseHex("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"))||!type.sourceFactType().equals(event.get(e.SOURCE_FACT_TYPE))||event.get(e.SOURCE_FACT_ID)==null)throw failure(422,"PROJECTION_EVENT_INVALID");
            if(type.sourceSelector().startsWith("hash:")?(revision!=null||hash==null||hash.length!=32):(hash!=null||revision==null||revision<0||revision>9007199254740991L||type.sourceSelector().equals("revision:0")&&revision!=0))throw failure(422,"PROJECTION_EVENT_INVALID");
            return new Notification(type,new Subject(type.sourceFactType(),event.get(e.SOURCE_FACT_ID),revision,hash==null?null:Base64.getUrlEncoder().withoutPadding().encodeToString(hash)));
        }catch(IllegalArgumentException invalid){throw failure(422,"PROJECTION_EVENT_INVALID");}
    }
    private static R1ServiceReadRuntime.Failure failure(int status,String code){return new R1ServiceReadRuntime.Failure(status,code);}
}
