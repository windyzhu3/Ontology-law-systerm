package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.execution.internal.persistence.SensitiveReadClock;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Sole read transaction boundary: no network callback, serialization, cache, or response before commit acknowledgement. */
public final class SensitiveReadRuntime {
    @FunctionalInterface public interface ReadWork { Prepared read(Connection connection,Instant trustedNow)throws SQLException; }
    public record Prepared(Map<String,Object> envelope,DisclosurePlan disclosure) {
        @SuppressWarnings("unchecked") public Prepared {envelope=(Map<String,Object>)CanonicalJson.freeze(envelope);Objects.requireNonNull(disclosure);}
    }
    public record Committed(int status,Map<String,Object> body,String etag) {}
    public static final class Failure extends RuntimeException {
        private final int status;private final String code;
        public Failure(int status,String code) {super(code,null,false,false);this.status=status;this.code=code;}
        public int status(){return status;}public String code(){return code;}
    }
    private final AuthorizationService authorization;
    private final AuditAppender audit;
    private final R1BusinessFence fence=R1BusinessFence.databaseBacked();
    public SensitiveReadRuntime(AuthorizationService authorization,AuditAppender audit) {this.authorization=Objects.requireNonNull(authorization);this.audit=Objects.requireNonNull(audit);}
    public Committed read(Connection connection,Actor actor,UUID correlation,String ifNoneMatch,ReadWork work) {
        if(actor==null)throw new Failure(401,"UNAUTHENTICATED");
        if(actor.principalKind()!=PrincipalKind.HUMAN)throw new Failure(403,"NOT_AUTHORIZED");
        Objects.requireNonNull(correlation);
        try {
            return inTransaction(connection,Capability.QUERY,c->{
                fence.shared(c,actor.tenantId());
                work.read(c,SensitiveReadClock.now(c));
                authorization.lockForEvaluation(c,actor.tenantId());
                // Discard the entire first pass. Identity changes while waiting regenerate all content and bindings.
                var prepared=work.read(c,SensitiveReadClock.now(c));
                boolean sensitive=prepared.envelope().get("currentCard")!=null;
                if(sensitive&&prepared.disclosure().entries().isEmpty())throw new IllegalArgumentException("Missing disclosure sources");
                String etag=etag(actor,prepared);
                boolean matched=etag.equals(ifNoneMatch);
                if(sensitive) {
                    try {
                        setLocalRole(c,Capability.AUDIT);
                        for(var source:prepared.disclosure().entries())audit.append(c,new AuditAppender.ReadDisclosureEntry(UUID.randomUUID(),correlation,
                            source.disclosedSource(),source.authorizationAnchor(),source.authorization(),matched?AuditAppender.ResponseMode.CACHE_REVALIDATED:AuditAppender.ResponseMode.BODY));
                    } catch(SQLException|RuntimeException failure) {throw new Failure(503,"SERVICE_UNAVAILABLE");}
                }
                return new Committed(matched?304:200,matched?null:prepared.envelope(),etag);
            });
        } catch(Failure safe) {throw safe;}
        catch(SQLException failure) {throw new Failure(503,"SERVICE_UNAVAILABLE");}
        catch(RuntimeException failure) {
            for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof SQLException sql&&(sql.getSQLState()!=null&&(sql.getSQLState().startsWith("08")||Set.of("55P03","57014","40P01","40001").contains(sql.getSQLState()))))throw new Failure(503,"SERVICE_UNAVAILABLE");
            throw new Failure(500,"INTERNAL_ERROR");
        }
    }
    private static String etag(Actor actor,Prepared prepared) {
        var scope=new TreeMap<String,Object>();scope.put("tenant",actor.tenantId().toString());scope.put("principal",actor.principalId().toString());scope.put("appointment",actor.appointmentId().toString());
        scope.put("representedPrincipal",actor.onBehalfPrincipalId()==null?null:actor.onBehalfPrincipalId().toString());scope.put("representedAppointment",actor.onBehalfAppointmentId()==null?null:actor.onBehalfAppointmentId().toString());
        var entries=new ArrayList<Object>();for(var entry:prepared.disclosure().entries())entries.add(Map.of("source",selector(entry.disclosedSource()),"anchor",selector(entry.authorizationAnchor()),"authorization",stable(entry.authorization())));
        var dependencies=prepared.disclosure().dependencies().stream().map(SensitiveReadRuntime::stable).sorted().distinct().toList();
        String canonical=CanonicalJson.encode(Map.of("profile","R1_CURRENT_WORKCARD_DISCLOSURE_V1","version",1,"actor",scope,"envelope",prepared.envelope(),"sources",entries,"authorizationDependencies",dependencies));
        return "\"wb."+Base64.getUrlEncoder().withoutPadding().encodeToString(CanonicalJson.digest(canonical))+"\"";
    }
    private static String stable(AuthorizationSnapshot snapshot) {if(!snapshot.allowed()||snapshot.stableDependencies()==null)throw new IllegalArgumentException("Stable authorization dependencies required");return snapshot.stableDependencies();}
    private static Map<String,Object> selector(Subject source) {var result=new TreeMap<String,Object>();result.put("type",source.type());result.put("id",source.id().toString());result.put("revision",source.revision());result.put("hash",source.hash());return result;}
}
