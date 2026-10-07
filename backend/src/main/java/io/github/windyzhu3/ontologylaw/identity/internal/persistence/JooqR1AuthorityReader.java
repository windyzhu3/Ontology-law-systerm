package io.github.windyzhu3.ontologylaw.identity.internal.persistence;

import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import static io.github.windyzhu3.ontologylaw.identity.internal.persistence.jooq.Tables.*;

public final class JooqR1AuthorityReader implements R1AuthorityReader {
    private record GrantCandidates(Actor actor,String code,Path path) {}
    private final AuthorizationService authorization=AuthorizationService.databaseBacked();
    private static DSLContext db(Connection c) {return DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false));}
    public Request select(Connection c,Actor actor,Subject subject,UUID organization,String slot,String code) throws SQLException {
        var result=choose(c,actor,subject,organization,slot,code,false);
        return result==null?null:result.request();
    }
    public List<AuthorizationSnapshot> entryAuthorizations(Connection c,Actor actor,String slot,String code)throws SQLException {
        if(actor.principalKind()!=PrincipalKind.HUMAN||actor.onBehalfAppointmentId()!=null)return List.of();
        var g=AUTHORITY_GRANT;var o=ORGANIZATION_UNIT;
        var scopes=db(c).selectDistinct(o.ORGANIZATION_UNIT_ID,o.REVISION).from(g).join(o)
            .on(o.TENANT_ID.eq(g.TENANT_ID)).and(o.ORGANIZATION_UNIT_ID.eq(g.SCOPE_ORGANIZATION_UNIT_ID))
            .where(g.TENANT_ID.eq(actor.tenantId())).and(g.GRANTEE_APPOINTMENT_ID.eq(actor.appointmentId()))
            .and(g.AUTHORITY_CODE.eq(code)).and(g.STATE.eq("ACTIVE")).and(o.STATE.eq("ACTIVE"))
            .orderBy(o.ORGANIZATION_UNIT_ID).fetch();
        var result=new ArrayList<AuthorizationSnapshot>();
        for(var row:scopes){var id=row.get(o.ORGANIZATION_UNIT_ID);var subject=new Subject("identity.organization_unit",id,row.get(o.REVISION),null);var snapshot=authorize(c,actor,subject,id,slot,code);if(snapshot!=null&&snapshot.allowed())result.add(snapshot);}
        return List.copyOf(result);
    }
    public AuthorizationSnapshot authorize(Connection c,Actor actor,Subject subject,UUID organization,String slot,String code)throws SQLException {
        return choose(c,actor,subject,organization,slot,code,true);
    }
    private AuthorizationSnapshot choose(Connection c,Actor actor,Subject subject,UUID organization,String slot,String code,boolean finalCheck)throws SQLException {
        Path path=actor.onBehalfAppointmentId()!=null?Path.DELEGATED:actor.principalKind()==PrincipalKind.SERVICE?Path.SYSTEM:Path.DIRECT;
        var ids=grantIds(c,actor,code,path);
        for(UUID id:ids) {
            var request=new Request(actor,subject,organization,new Requirement(code,slot,path,id));
            var snapshot=authorization.evaluate(c,request,finalCheck);
            if(snapshot.allowed())return snapshot;
        }
        return null;
    }
    private List<UUID> grantIds(Connection c,Actor actor,String code,Path path) {
        var d=db(c);
        if(path==Path.DELEGATED){var g=DELEGATION_GRANT;return JooqAuthorizationService.lockedFacts(c,actor.tenantId(),new GrantCandidates(actor,code,path),()->d.select(g.DELEGATION_GRANT_ID).from(g).where(g.TENANT_ID.eq(actor.tenantId())).and(g.DELEGATE_APPOINTMENT_ID.eq(actor.appointmentId())).and(g.DELEGATOR_APPOINTMENT_ID.eq(actor.onBehalfAppointmentId())).orderBy(g.DELEGATION_GRANT_ID).fetch(g.DELEGATION_GRANT_ID));}
        var g=AUTHORITY_GRANT;return JooqAuthorizationService.lockedFacts(c,actor.tenantId(),new GrantCandidates(actor,code,path),()->d.select(g.AUTHORITY_GRANT_ID).from(g).where(g.TENANT_ID.eq(actor.tenantId())).and(g.GRANTEE_APPOINTMENT_ID.eq(actor.appointmentId())).and(g.AUTHORITY_CODE.eq(code)).orderBy(g.AUTHORITY_GRANT_ID).fetch(g.AUTHORITY_GRANT_ID));
    }
    public List<AuthorizationSnapshot> authorizeAll(Connection c,Actor actor,List<Subject> subjects,UUID organization,String slot,String code)throws SQLException {
        if(!JooqAuthorizationService.lockedReadActive(c,actor.tenantId()))return R1AuthorityReader.super.authorizeAll(c,actor,subjects,organization,slot,code);
        Path path=actor.onBehalfAppointmentId()!=null?Path.DELEGATED:actor.principalKind()==PrincipalKind.SERVICE?Path.SYSTEM:Path.DIRECT;
        var found=new HashMap<Subject,AuthorizationSnapshot>();
        for(UUID id:grantIds(c,actor,code,path)){
            var requests=subjects.stream().distinct().filter(subject->!found.containsKey(subject)).map(subject->new Request(actor,subject,organization,new Requirement(code,slot,path,id))).toList();
            var evaluated=authorization.evaluateAll(c,requests,true);
            boolean oneCompletePath=found.isEmpty()&&evaluated.stream().allMatch(AuthorizationSnapshot::allowed);
            for(var snapshot:evaluated)if(snapshot.allowed())found.put(snapshot.request().subject(),snapshot);
            // This whole selection was evaluated at one database time, with the
            // batch's end-time boundary check already complete. A second identical
            // evaluation adds no distinct winning path to verify. A new call still
            // obtains fresh time; selections assembled across paths use the check below.
            if(oneCompletePath)return subjects.stream().map(found::get).toList();
            if(found.size()==new HashSet<>(subjects).size())break;
        }
        if(subjects.stream().anyMatch(subject->!found.containsKey(subject)))return List.of();
        // Earlier winning paths can expire while other paths are being selected.
        var result=authorization.evaluateAll(c,subjects.stream().map(subject->found.get(subject).request()).toList(),true);
        return result.stream().allMatch(AuthorizationSnapshot::allowed)?result:List.of();
    }
    public List<Candidate> candidates(Connection c,UUID tenant,Subject subject,UUID organization,String slot,String code) throws SQLException {
        var a=APPOINTMENT;var p=PRINCIPAL;var result=new ArrayList<Candidate>();
        var rows=db(c).select(a.APPOINTMENT_ID,a.PRINCIPAL_ID,a.ORGANIZATION_UNIT_ID,a.EFFECTIVE_FROM).from(a)
                .join(p).on(p.TENANT_ID.eq(a.TENANT_ID)).and(p.PRINCIPAL_ID.eq(a.PRINCIPAL_ID))
                .where(a.TENANT_ID.eq(tenant)).and(p.PRINCIPAL_KIND.eq("HUMAN"))
                .orderBy(a.EFFECTIVE_FROM,a.APPOINTMENT_ID).fetch();
        for(var row:rows) {
            if(!underRoot(c,tenant,row.get(a.ORGANIZATION_UNIT_ID),organization))continue;
            var actor=new Actor(tenant,row.get(a.PRINCIPAL_ID),row.get(a.APPOINTMENT_ID),null,null);
            var request=select(c,actor,subject,organization,slot,code);
            if(request!=null)result.add(new Candidate(actor.appointmentId(),actor.principalId(),row.get(a.ORGANIZATION_UNIT_ID),row.get(a.EFFECTIVE_FROM).toInstant(),request));
        }
        return List.copyOf(result);
    }
    private boolean underRoot(Connection c,UUID tenant,UUID node,UUID root) {
        var visited=new HashSet<UUID>();var o=ORGANIZATION_UNIT;
        while(node!=null&&visited.add(node)&&visited.size()<=256){var r=db(c).select(o.PARENT_ORGANIZATION_UNIT_ID,o.STATE).from(o).where(o.TENANT_ID.eq(tenant)).and(o.ORGANIZATION_UNIT_ID.eq(node)).fetchOne();
            if(r==null||!"ACTIVE".equals(r.value2()))return false;if(node.equals(root))return true;node=r.value1();}
        return false;
    }
}
