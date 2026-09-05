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
    public AuthorizationSnapshot evaluate(Connection connection, Request request, boolean finalCheck) throws SQLException {
        requireTransaction(connection);
        if(finalCheck) lock(connection,request.actor().tenantId(),true);
        Instant now;
        try(var p=connection.prepareStatement("select clock_timestamp()");var rs=p.executeQuery()) { rs.next(); now=rs.getObject(1,OffsetDateTime.class).toInstant(); }
        var check=new Check(DSL.using(connection,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false)),request,now);
        String rejection=check.evaluate();
        String evidence="R1_AUTHORIZATION_SNAPSHOT_V1\n"+request+"\n"+now+"\n"+check.evidence+"\n"+(rejection==null?"ALLOW":rejection);
        return new AuthorizationSnapshot(request,now,rejection==null,rejection,check.selectedFact,evidence,hash(evidence));
    }
    public void lockForMutation(Connection connection, UUID tenantId) throws SQLException { requireTransaction(connection); lock(connection,tenantId,false); }
    public void lockForEvaluation(Connection connection, UUID tenantId) throws SQLException { requireTransaction(connection); lock(connection,tenantId,true); }
    private static void requireTransaction(Connection c) throws SQLException {
        if(c.getAutoCommit() || c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED) throw new SQLException("Authorization requires READ COMMITTED transaction","25001");
    }
    private static byte[] hash(String value) {
        try{return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static void lock(Connection c,UUID tenant,boolean shared) throws SQLException {
        // Separate namespace from command locks. Hash collisions only serialize extra tenants.
        long key=ByteBuffer.wrap(hash("R1_IDENTITY_TENANT_LOCK_V1:"+tenant)).getLong();
        try(var p=c.prepareStatement(shared?"select pg_advisory_xact_lock_shared(?)":"select pg_advisory_xact_lock(?)")) {p.setLong(1,key);p.execute();}
    }
    private static final class Check {
        final DSLContext db; final Request request; final UUID tenant; final Instant now;
        final StringBuilder evidence=new StringBuilder();
        Subject selectedFact;
        final Map<UUID,Record> organizations=new HashMap<>();
        Check(DSLContext db,Request request,Instant now){this.db=db;this.request=request;this.tenant=request.actor().tenantId();this.now=now;}
        Record row(Table<?> table,String idName,UUID id) throws SQLException {
            Record row=db.selectFrom(table).where(DSL.field("tenant_id",UUID.class).eq(tenant)).and(DSL.field(idName,UUID.class).eq(id)).fetchOne();
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
            if(!Objects.equals(principal.get("principal_kind"),system?"SERVICE":"HUMAN"))return "NOT_AUTHORIZED";
            if(actor.onBehalfPrincipalId()!=null && !appointment(actor.onBehalfPrincipalId(),actor.onBehalfAppointmentId()))return "APPOINTMENT_INACTIVE";
            if(ancestry(request.scopeOrganizationId())==null)return "NOT_AUTHORIZED";
            var g=OBJECT_ACCESS_GRANT;
            Condition exact=g.OBJECT_SUBJECT_TYPE.eq(request.subject().type()).and(g.OBJECT_SUBJECT_ID.eq(request.subject().id()));
            exact=exact.and(request.subject().revision()!=null?g.OBJECT_SUBJECT_REVISION.eq(request.subject().revision()):g.OBJECT_SUBJECT_HASH.eq(Base64.getUrlDecoder().decode(request.subject().hash())));
            for(Record deny:db.selectFrom(g).where(g.TENANT_ID.eq(tenant)).and(g.GRANTEE_PRINCIPAL_ID.eq(actor.principalId()).or(g.GRANTEE_PRINCIPAL_ID.eq(actor.onBehalfPrincipalId()))).and(g.ACCESS_CODE.eq(request.requirement().authorityCode())).and(g.EFFECT_CODE.eq("DENY")).and(exact).orderBy(g.OBJECT_ACCESS_GRANT_ID).fetch()) {
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
