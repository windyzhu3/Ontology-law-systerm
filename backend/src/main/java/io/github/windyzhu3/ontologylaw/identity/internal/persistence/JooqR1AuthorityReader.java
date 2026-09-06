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
    private final AuthorizationService authorization=AuthorizationService.databaseBacked();
    private static DSLContext db(Connection c) {return DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false));}
    public Request select(Connection c,Actor actor,Subject subject,UUID organization,String slot,String code) throws SQLException {
        var d=db(c);List<UUID> ids;Path path;
        if(actor.onBehalfAppointmentId()!=null) {
            path=Path.DELEGATED;var g=DELEGATION_GRANT;
            ids=d.select(g.DELEGATION_GRANT_ID).from(g).where(g.TENANT_ID.eq(actor.tenantId()))
                    .and(g.DELEGATE_APPOINTMENT_ID.eq(actor.appointmentId())).and(g.DELEGATOR_APPOINTMENT_ID.eq(actor.onBehalfAppointmentId()))
                    .orderBy(g.DELEGATION_GRANT_ID).fetch(g.DELEGATION_GRANT_ID);
        } else {
            path=actor.principalKind()==PrincipalKind.SERVICE?Path.SYSTEM:Path.DIRECT;var g=AUTHORITY_GRANT;
            ids=d.select(g.AUTHORITY_GRANT_ID).from(g).where(g.TENANT_ID.eq(actor.tenantId()))
                    .and(g.GRANTEE_APPOINTMENT_ID.eq(actor.appointmentId())).and(g.AUTHORITY_CODE.eq(code))
                    .orderBy(g.AUTHORITY_GRANT_ID).fetch(g.AUTHORITY_GRANT_ID);
        }
        for(UUID id:ids) {
            var request=new Request(actor,subject,organization,new Requirement(code,slot,path,id));
            if(authorization.evaluate(c,request,false).allowed())return request;
        }
        return null;
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
