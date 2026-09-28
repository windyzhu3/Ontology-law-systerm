package io.github.windyzhu3.ontologylaw.identity.internal.persistence;

import io.github.windyzhu3.ontologylaw.identity.*;
import java.sql.*;
import java.util.*;
import java.time.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import org.jooq.Record;
import org.jooq.*;
import org.jooq.impl.DSL;
import static io.github.windyzhu3.ontologylaw.identity.internal.persistence.jooq.Tables.*;

public final class JooqAuthorizationService implements AuthorizationService {
    private record FactKey(String table,String idName,UUID id) {}
    private static final class ReadFacts {
        final Connection connection;final UUID tenant;final Map<FactKey,Record> rows=new HashMap<>();final Map<Object,Object> queries=new HashMap<>();
        ReadFacts(Connection connection,UUID tenant){this.connection=connection;this.tenant=tenant;}
    }
    // Shared across reader instances, never across threads, connections, tenants or read scopes.
    private record Denials(UUID principal,UUID represented,Subject subject,String authority) {}
    private record DenialCatalog(UUID principal,UUID represented,String authority) {}
    @SuppressWarnings("unchecked") private static <T>T cached(ReadFacts facts,Object key,java.util.function.Supplier<T> load) {
        if(facts==null)return load.get();
        if(facts.queries.containsKey(key))return (T)facts.queries.get(key);
        T value=load.get();facts.queries.put(key,value);return value;
    }
    static <T>T lockedFacts(Connection connection,UUID tenant,Object key,java.util.function.Supplier<T> load) {
        return cached(facts(connection,tenant),key,load);
    }
    private static final ThreadLocal<ReadFacts> READ_FACTS=new ThreadLocal<>();
    public ReadScope lockedReadScope(Connection connection,UUID tenantId)throws SQLException {
        if(READ_FACTS.get()!=null)throw new SQLException("Nested identity read scope","25001");
        lockForEvaluation(connection,tenantId);
        READ_FACTS.set(new ReadFacts(connection,tenantId));
        return ()->READ_FACTS.remove();
    }

    public AuthorizationSnapshot evaluate(Connection connection, Request request, boolean finalCheck) throws SQLException {
        requireTransaction(connection);
        if(finalCheck) lock(connection,request.actor().tenantId(),true);
        return checked(connection,request,databaseTime(connection)).value();
    }
    public List<AuthorizationSnapshot> evaluateAll(Connection connection,List<Request> requests,boolean finalCheck)throws SQLException {
        requireTransaction(connection);if(requests.isEmpty())return List.of();
        UUID tenant=requests.getFirst().actor().tenantId();
        if(facts(connection,tenant)==null||requests.stream().anyMatch(r->!tenant.equals(r.actor().tenantId())))return AuthorizationService.super.evaluateAll(connection,requests,finalCheck);
        // The scope already owns the identity lock. Re-evaluate all time conditions and
        // reject/retry if any boundary is crossed while assembling this exact batch.
        return StableAuthorizationBatch.evaluate(()->databaseTime(connection),at->{
            var snapshots=new ArrayList<AuthorizationSnapshot>();Instant boundary=null;
            for(var request:requests){var result=checked(connection,request,at);snapshots.add(result.value());if(result.nextBoundary()!=null&&(boundary==null||result.nextBoundary().isBefore(boundary)))boundary=result.nextBoundary();}
            return new StableAuthorizationBatch.Result<>(List.copyOf(snapshots),boundary);
        });
    }
    private static Instant databaseTime(Connection connection)throws SQLException {
        try(var p=connection.prepareStatement("select clock_timestamp()");var rs=p.executeQuery()){rs.next();return rs.getObject(1,OffsetDateTime.class).toInstant();}
    }
    private StableAuthorizationBatch.Result<AuthorizationSnapshot> checked(Connection connection,Request request,Instant now)throws SQLException {
        var check=new Check(DSL.using(connection,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false)),request,now,facts(connection,request.actor().tenantId()));
        String rejection=check.evaluate();
        String evidence="R1_AUTHORIZATION_SNAPSHOT_V1\n"+request+"\n"+now+"\n"+check.evidence+"\n"+(rejection==null?"ALLOW":rejection);
        String stable="R1_AUTHORIZATION_DEPENDENCIES_V1\n"+request+"\n"+check.evidence+"\n"+(rejection==null?"ALLOW":rejection);
        return new StableAuthorizationBatch.Result<>(new AuthorizationSnapshot(request,now,rejection==null,rejection,check.selectedFact,evidence,hash(evidence),stable),check.nextBoundary);
    }
    static boolean lockedReadActive(Connection c,UUID tenant){return facts(c,tenant)!=null;}
    private static ReadFacts facts(Connection connection,UUID tenant) {
        var facts=READ_FACTS.get();return facts!=null&&facts.connection==connection&&facts.tenant.equals(tenant)?facts:null;
    }
    public void lockForMutation(Connection connection, UUID tenantId) throws SQLException {
        if(READ_FACTS.get()!=null)throw new SQLException("Mutation in identity read scope","25001");
        requireTransaction(connection); lock(connection,tenantId,false);
    }
    public void lockForEvaluation(Connection connection, UUID tenantId) throws SQLException { requireTransaction(connection); lock(connection,tenantId,true); }
    private static void requireTransaction(Connection c) throws SQLException {
        if(c.getAutoCommit())throw new SQLException("Authorization requires READ COMMITTED transaction","25001");
        // lockedReadScope validated isolation before acquiring its lock. Its caller
        // owns the transaction and must not alter connection state until scope close.
        // PgConnection.getTransactionIsolation performs a server round trip, so do
        // not repeat it for every exact authorization inside that same read scope.
        var scope=READ_FACTS.get();
        if((scope==null||scope.connection!=c)&&c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw new SQLException("Authorization requires READ COMMITTED transaction","25001");
    }
    private static byte[] hash(String value) {
        try{return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static void lock(Connection c,UUID tenant,boolean shared) throws SQLException {
        // The scope already holds this transaction-level shared lock. Re-acquiring it
        // per disclosed fact adds a network round trip without extending its lifetime.
        if(shared && facts(c,tenant)!=null)return;
        // Separate namespace from command locks. Hash collisions only serialize extra tenants.
        long key=ByteBuffer.wrap(hash("R1_IDENTITY_TENANT_LOCK_V1:"+tenant)).getLong();
        try(var p=c.prepareStatement(shared?"select pg_advisory_xact_lock_shared(?)":"select pg_advisory_xact_lock(?)")) {p.setLong(1,key);p.execute();}
    }
    private static final class Check {
        final DSLContext db; final Request request; final UUID tenant; final Instant now; final ReadFacts facts;
        final StringBuilder evidence=new StringBuilder();
        Subject selectedFact;Instant nextBoundary;
        void boundary(Instant time){if(time!=null&&time.isAfter(now)&&(nextBoundary==null||time.isBefore(nextBoundary)))nextBoundary=time;}
        final Map<UUID,Record> organizations=new HashMap<>();
        Check(DSLContext db,Request request,Instant now,ReadFacts facts){this.db=db;this.request=request;this.tenant=request.actor().tenantId();this.now=now;this.facts=facts;}
        Record row(Table<?> table,String idName,UUID id) throws SQLException {
            var key=new FactKey(table.getName(),idName,id);
            Record row;
            if(facts!=null&&facts.rows.containsKey(key))row=facts.rows.get(key);
            else {
                row=db.selectFrom(table).where(DSL.field("tenant_id",UUID.class).eq(tenant)).and(DSL.field(idName,UUID.class).eq(id)).fetchOne();
                if(facts!=null)facts.rows.put(key,row);
            }
            if(row!=null) {
                Long revision=row.get("revision",Long.class);
                if(revision!=null && (revision<0 || revision>9007199254740991L)) throw new SQLException("Unsafe persisted revision","22003");
                String authorityTable=switch(request.requirement().path()){case DIRECT,SYSTEM->"authority_grant";case DELEGATED->"delegation_grant";case OBJECT->"object_access_grant";};
                if(table.getName().equals(authorityTable) && id.equals(request.requirement().authorityFactId())) selectedFact=new Subject("identity."+authorityTable,id,revision,null);
                evidence.append(table.getName()).append(':').append(id).append(':').append(revision).append(':').append(row.get("state")).append(';');
            } else evidence.append(table.getName()).append(':').append(id).append(":MISSING;");
            return row;
        }
        boolean active(Record r){return r!=null && "ACTIVE".equals(r.get("state"));}
        boolean valid(Record r,String from,String until){
            if(!active(r))return false;
            Instant start=r.get(from,OffsetDateTime.class).toInstant();
            OffsetDateTime end=r.get(until,OffsetDateTime.class);
            boundary(start);boundary(end==null?null:end.toInstant());
            evidence.append(start).append('/').append(end).append(';');
            return !start.isAfter(now) && (end==null || end.toInstant().isAfter(now));
        }
        boolean appointment(UUID principal,UUID appointment) throws SQLException {
            Record p=row(PRINCIPAL,"principal_id",principal), a=row(APPOINTMENT,"appointment_id",appointment);
            return active(p) && valid(a,"effective_from","effective_until") && principal.equals(a.get("principal_id",UUID.class)) && ancestry(a.get("organization_unit_id",UUID.class))!=null;
        }
        List<UUID> ancestry(UUID id) throws SQLException {
            List<UUID> path=new ArrayList<>();
            while(id!=null) {
                if(path.contains(id) || path.size()>256)return null;
                path.add(id);
                Record r=organizations.get(id);
                if(r==null){r=row(ORGANIZATION_UNIT,"organization_unit_id",id);if(r!=null)organizations.put(id,r);}
                if(!active(r))return null;
                id=r.get("parent_organization_unit_id",UUID.class);
            }
            return path;
        }
        boolean covers(UUID root,UUID node) throws SQLException { var path=ancestry(node);return path!=null && path.contains(root); }
        String evaluate() throws SQLException {
            if(!active(row(TENANT,"tenant_id",tenant)))return "NOT_AUTHORIZED";
            Actor actor=request.actor();
            if(!appointment(actor.principalId(),actor.appointmentId()))return "APPOINTMENT_INACTIVE";
            Record principal=row(PRINCIPAL,"principal_id",actor.principalId());
            boolean system=request.requirement().path()==Path.SYSTEM;
            if(!Objects.equals(principal.get("principal_kind"),actor.principalKind().name())
                    || actor.principalKind()!=(system?PrincipalKind.SERVICE:PrincipalKind.HUMAN))return "NOT_AUTHORIZED";
            if(actor.onBehalfPrincipalId()!=null && !appointment(actor.onBehalfPrincipalId(),actor.onBehalfAppointmentId()))return "APPOINTMENT_INACTIVE";
            if(ancestry(request.scopeOrganizationId())==null)return "NOT_AUTHORIZED";
            var g=OBJECT_ACCESS_GRANT;
            Condition exact=g.OBJECT_SUBJECT_TYPE.eq(request.subject().type()).and(g.OBJECT_SUBJECT_ID.eq(request.subject().id()));
            exact=exact.and(request.subject().revision()!=null?g.OBJECT_SUBJECT_REVISION.eq(request.subject().revision()):g.OBJECT_SUBJECT_HASH.eq(Base64.getUrlDecoder().decode(request.subject().hash())));
            final Condition selector=exact;
            // Cache immutable-under-lock rows, including future/expired DENY rows; valid() still uses fresh time.
            var owner=g.TENANT_ID.eq(tenant).and(g.GRANTEE_PRINCIPAL_ID.eq(actor.principalId()).or(g.GRANTEE_PRINCIPAL_ID.eq(actor.onBehalfPrincipalId()))).and(g.ACCESS_CODE.eq(request.requirement().authorityCode())).and(g.EFFECT_CODE.eq("DENY"));
            // A ledger examines many exact subjects for the same actor and authority.
            // Read a bounded catalog once under the identity lock; retain future and
            // expired rows and evaluate validity against this evaluation's fresh clock.
            // Large catalogs fall back to indexed exact-subject reads, never truncate.
            var catalog=facts==null?null:cached(facts,new DenialCatalog(actor.principalId(),actor.onBehalfPrincipalId(),request.requirement().authorityCode()),
                ()->db.selectFrom(g).where(owner).orderBy(g.OBJECT_ACCESS_GRANT_ID).limit(257).fetch());
            List<? extends Record> denies;
            if(catalog!=null&&catalog.size()<=256) {
                denies=catalog.stream().filter(r->request.subject().type().equals(r.get(g.OBJECT_SUBJECT_TYPE))&&request.subject().id().equals(r.get(g.OBJECT_SUBJECT_ID))
                    &&(request.subject().revision()!=null?request.subject().revision().equals(r.get(g.OBJECT_SUBJECT_REVISION)):Arrays.equals(Base64.getUrlDecoder().decode(request.subject().hash()),r.get(g.OBJECT_SUBJECT_HASH)))).toList();
                for(var deny:denies)facts.rows.put(new FactKey(g.getName(),"object_access_grant_id",deny.get(g.OBJECT_ACCESS_GRANT_ID)),deny);
            } else denies=cached(facts,new Denials(actor.principalId(),actor.onBehalfPrincipalId(),request.subject(),request.requirement().authorityCode()),
                ()->db.selectFrom(g).where(owner).and(selector).orderBy(g.OBJECT_ACCESS_GRANT_ID).fetch());
            for(Record deny:denies) {
                row(g,"object_access_grant_id",deny.get(g.OBJECT_ACCESS_GRANT_ID));
                if(valid(deny,"valid_from","valid_until"))return "NOT_AUTHORIZED";
            }
            if(request.requirement().path()==Path.OBJECT) {
                Record grant=row(OBJECT_ACCESS_GRANT,"object_access_grant_id",request.requirement().authorityFactId());
                Record app=row(APPOINTMENT,"appointment_id",actor.appointmentId());
                boolean matches=grant!=null && request.subject().type().equals(grant.get("object_subject_type"))
                        && request.subject().id().equals(grant.get("object_subject_id"))
                        && Objects.equals(request.subject().revision(),grant.get("object_subject_revision",Long.class))
                        && Arrays.equals(request.subject().hash()==null?null:Base64.getUrlDecoder().decode(request.subject().hash()),grant.get("object_subject_hash",byte[].class));
                return actor.onBehalfPrincipalId()==null && valid(grant,"valid_from","valid_until") && matches
                        && "ALLOW".equals(grant.get("effect_code")) && actor.principalId().equals(grant.get("grantee_principal_id"))
                        && request.requirement().authorityCode().equals(grant.get("access_code"))
                        && covers(app.get("organization_unit_id",UUID.class),request.scopeOrganizationId())?null:"NOT_AUTHORIZED";
            }
            if(request.requirement().path()==Path.DELEGATED) {
                Record delegation=row(DELEGATION_GRANT,"delegation_grant_id",request.requirement().authorityFactId());
                if(actor.onBehalfAppointmentId()==null || !valid(delegation,"valid_from","valid_until")
                        || !actor.appointmentId().equals(delegation.get("delegate_appointment_id"))
                        || !actor.onBehalfAppointmentId().equals(delegation.get("delegator_appointment_id")))return "NOT_AUTHORIZED";
                Record source=row(AUTHORITY_GRANT,"authority_grant_id",delegation.get("source_authority_grant_id",UUID.class));
                return direct(source,actor.onBehalfAppointmentId())
                        && covers(source.get("scope_organization_unit_id",UUID.class),delegation.get("scope_organization_unit_id",UUID.class))
                        && covers(delegation.get("scope_organization_unit_id",UUID.class),request.scopeOrganizationId())?null:"NOT_AUTHORIZED";
            }
            if((request.requirement().path()!=Path.DIRECT && !system) || actor.onBehalfPrincipalId()!=null)return "NOT_AUTHORIZED";
            Record grant=row(AUTHORITY_GRANT,"authority_grant_id",request.requirement().authorityFactId());
            return direct(grant,actor.appointmentId())?null:"NOT_AUTHORIZED";
        }
        boolean direct(Record grant,UUID appointment) throws SQLException {
            return valid(grant,"valid_from","valid_until")
                    && appointment.equals(grant.get("grantee_appointment_id",UUID.class))
                    && request.requirement().authorityCode().equals(grant.get("authority_code"))
                    && covers(grant.get("scope_organization_unit_id",UUID.class),request.scopeOrganizationId());
        }
    }
}
