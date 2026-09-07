package io.github.windyzhu3.ontologylaw.execution.internal.persistence;
import io.github.windyzhu3.ontologylaw.execution.R1ProjectionOutboxPort;
import java.sql.*;
import java.util.*;
import java.time.OffsetDateTime;
import org.jooq.*;
import org.jooq.impl.DSL;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.jooq.Tables.DOMAIN_EVENT_OUTBOX;
public final class JooqR1ProjectionOutboxPort implements R1ProjectionOutboxPort {
    private static final long MAX=9007199254740991L;
    private static final int[] DELAYS={1,5,30,120,600,1800,7200};
    private static final Set<String> ERRORS=Set.of("NETWORK_ERROR","HTTP_TIMEOUT","RATE_LIMITED","INTERNAL_ERROR","SERVICE_UNAVAILABLE","LEASE_EXPIRED","PROJECTION_EVENT_INVALID","VALIDATION_FAILED","NOT_FOUND");
    private final Connections connections;
    public JooqR1ProjectionOutboxPort(Connections connections) {this.connections=Objects.requireNonNull(connections);}
    private static DSLContext db(Connection c){return DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false));}
    private static Field<OffsetDateTime> now(){return DSL.field("clock_timestamp()",OffsetDateTime.class);}
    private <T>T transaction(SqlWork<T> work)throws SQLException{try(var c=connections.open()){return inTransaction(c,Capability.WORKER,work);}}
    private static void bound(int limit,int max){if(limit<1||limit>max)throw new IllegalArgumentException("Invalid bounded limit");}
    private static void incrementable(long value)throws SQLException{if(value<0||value>=MAX)throw new SQLException("Technical counter exhausted","22003");}
    private static void error(String code){if(!ERRORS.contains(code))throw new IllegalArgumentException("Unregistered safe error");}
    private static Claim claim(org.jooq.Record row){var o=DOMAIN_EVENT_OUTBOX;return new Claim(row.get(o.TENANT_ID),row.get(o.DOMAIN_EVENT_OUTBOX_ID),row.get(o.DOMAIN_EVENT_ID),row.get(o.REVISION),row.get(o.LEASE_OWNER),row.get(o.FENCING_TOKEN),row.get(o.ATTEMPT_COUNT),row.get(o.LEASE_UNTIL).toInstant());}
    private static Condition exact(Claim claim,boolean expired){
        var o=DOMAIN_EVENT_OUTBOX;
        return o.TENANT_ID.eq(claim.tenantId()).and(o.DOMAIN_EVENT_OUTBOX_ID.eq(claim.outboxId())).and(o.DOMAIN_EVENT_ID.eq(claim.eventId()))
            .and(o.QUEUE_OWNER.eq("R1_PROJECTION")).and(o.STATUS.eq("CLAIMED")).and(o.REVISION.eq(claim.revision()))
            .and(o.LEASE_OWNER.eq(claim.leaseOwner())).and(o.FENCING_TOKEN.eq(claim.fencingToken()))
            .and(expired?o.LEASE_UNTIL.le(now()):o.LEASE_UNTIL.gt(now()));
    }
    public List<Claim> claim(UUID tenant,String owner,int limit)throws SQLException{
        Objects.requireNonNull(tenant);bound(limit,4);
        if(owner==null||!owner.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}"))throw new IllegalArgumentException("Invalid lease owner");
        return transaction(c->{var d=db(c);var o=DOMAIN_EVENT_OUTBOX;var result=new ArrayList<Claim>();
            var rows=d.selectFrom(o).where(o.TENANT_ID.eq(tenant)).and(o.QUEUE_OWNER.eq("R1_PROJECTION")).and(o.STATUS.eq("PENDING"))
                .and(o.AVAILABLE_AT.le(now())).and(o.ATTEMPT_COUNT.lt(8)).orderBy(o.AVAILABLE_AT,o.DOMAIN_EVENT_OUTBOX_ID).limit(limit).forUpdate().skipLocked().fetch();
            for(var row:rows){incrementable(row.get(o.REVISION));incrementable(row.get(o.FENCING_TOKEN));
                var changed=d.update(o).set(o.STATUS,"CLAIMED").set(o.REVISION,row.get(o.REVISION)+1).set(o.FENCING_TOKEN,row.get(o.FENCING_TOKEN)+1)
                    .set(o.ATTEMPT_COUNT,row.get(o.ATTEMPT_COUNT)+1).set(o.LEASE_OWNER,owner)
                    .set(o.LEASE_UNTIL,DSL.field("clock_timestamp() + interval '60 seconds'",OffsetDateTime.class))
                    .where(o.TENANT_ID.eq(tenant)).and(o.DOMAIN_EVENT_OUTBOX_ID.eq(row.get(o.DOMAIN_EVENT_OUTBOX_ID))).and(o.STATUS.eq("PENDING")).and(o.REVISION.eq(row.get(o.REVISION)))
                    .returning().fetchOne();
                if(changed==null)throw new SQLException("Claim compare-and-set failed","40001");result.add(claim(changed));
            }return List.copyOf(result);
        });
    }
    public boolean ack(Claim claim)throws SQLException{
        return transaction(c->{var o=DOMAIN_EVENT_OUTBOX;var d=db(c);var current=d.selectFrom(o).where(exact(claim,false)).forUpdate().fetchOne();
            if(current==null)return false;incrementable(current.get(o.REVISION));
            return d.update(o).set(o.STATUS,"DELIVERED").set(o.LEASE_OWNER,(String)null).set(o.LEASE_UNTIL,(OffsetDateTime)null)
                .set(o.LAST_ERROR_CODE,(String)null).set(o.DELIVERED_AT,now()).set(o.REVISION,current.get(o.REVISION)+1).where(exact(claim,false)).execute()==1;
        });
    }
    public boolean retry(Claim claim,String code)throws SQLException{error(code);return transaction(c->finish(db(c),claim,code,false,false));}
    public boolean exhaust(Claim claim,String code)throws SQLException{error(code);return transaction(c->finish(db(c),claim,code,true,false));}
    private static boolean finish(DSLContext d,Claim claim,String code,boolean permanent,boolean expired)throws SQLException{
        var o=DOMAIN_EVENT_OUTBOX;var current=d.selectFrom(o).where(exact(claim,expired)).forUpdate().fetchOne();
        if(current==null)return false;incrementable(current.get(o.REVISION));int attempt=current.get(o.ATTEMPT_COUNT);
        if(attempt<1||attempt>8)throw new SQLException("Invalid cumulative attempt","22003");
        boolean exhausted=permanent||attempt==8;
        var update=d.update(o).set(o.STATUS,exhausted?"EXHAUSTED":"PENDING").set(o.LEASE_OWNER,(String)null).set(o.LEASE_UNTIL,(OffsetDateTime)null)
            .set(o.LAST_ERROR_CODE,code).set(o.REVISION,current.get(o.REVISION)+1);
        if(!exhausted)update.set(o.AVAILABLE_AT,DSL.field("clock_timestamp() + {0} * interval '1 second'",OffsetDateTime.class,DSL.val(DELAYS[attempt-1])));
        return update.where(exact(claim,expired)).execute()==1;
    }
    public ReapResult reap(UUID tenant,int limit)throws SQLException{
        Objects.requireNonNull(tenant);bound(limit,100);
        return transaction(c->{var d=db(c);var o=DOMAIN_EVENT_OUTBOX;int changed=0,exhausted=0;
            var rows=d.selectFrom(o).where(o.TENANT_ID.eq(tenant)).and(o.QUEUE_OWNER.eq("R1_PROJECTION")).and(o.STATUS.eq("CLAIMED"))
                .and(o.LEASE_UNTIL.le(now())).orderBy(o.LEASE_UNTIL,o.DOMAIN_EVENT_OUTBOX_ID).limit(limit).forUpdate().skipLocked().fetch();
            for(var row:rows)if(finish(d,claim(row),"LEASE_EXPIRED",false,true)){changed++;if(row.get(o.ATTEMPT_COUNT)==8)exhausted++;}return new ReapResult(changed,exhausted);
        });
    }
    public Counts counts(UUID tenant,int limit)throws SQLException{
        Objects.requireNonNull(tenant);bound(limit,1000);
        return transaction(c->{var d=db(c);var o=DOMAIN_EVENT_OUTBOX;var counts=new ArrayList<Integer>();
            for(var state:List.of("PENDING","CLAIMED","DELIVERED","EXHAUSTED"))counts.add(d.fetchCount(d.select(o.DOMAIN_EVENT_OUTBOX_ID).from(o)
                .where(o.TENANT_ID.eq(tenant)).and(o.QUEUE_OWNER.eq("R1_PROJECTION")).and(o.STATUS.eq(state)).limit(limit)));
            return new Counts(counts.get(0),counts.get(1),counts.get(2),counts.get(3));
        });
    }
}
