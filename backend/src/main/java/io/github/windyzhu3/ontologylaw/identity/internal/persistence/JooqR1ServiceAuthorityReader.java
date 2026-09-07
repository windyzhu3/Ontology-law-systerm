package io.github.windyzhu3.ontologylaw.identity.internal.persistence;

import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import org.jooq.*;
import org.jooq.impl.DSL;
import static io.github.windyzhu3.ontologylaw.identity.internal.persistence.jooq.Tables.*;

public final class JooqR1ServiceAuthorityReader implements R1ServiceAuthorityReader {
    private static DSLContext db(Connection c){return DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false));}
    public boolean projectionCoverage(Connection c,Actor actor,Set<UUID> organizations,Instant checkedAt) {
        var roots=roots(c,actor,"R1_PROJECTION_CONSUME",checkedAt);var d=db(c);
        if(roots.isEmpty())return false;
        for(var organization:organizations){var path=ancestry(d,actor.tenantId(),organization);if(path==null||Collections.disjoint(path,roots))return false;}
        return true;
    }
    private Set<UUID> roots(Connection c,Actor actor,String code,Instant checkedAt) {
        if(actor.principalKind()!=PrincipalKind.SERVICE||actor.onBehalfAppointmentId()!=null)return Set.of();
        var d=db(c);var a=APPOINTMENT;var p=PRINCIPAL;var t=TENANT;var now=checkedAt.atOffset(ZoneOffset.UTC);
        var app=d.select(a.ORGANIZATION_UNIT_ID).from(a).join(p).on(p.TENANT_ID.eq(a.TENANT_ID).and(p.PRINCIPAL_ID.eq(a.PRINCIPAL_ID)))
            .join(t).on(t.TENANT_ID.eq(a.TENANT_ID)).where(a.TENANT_ID.eq(actor.tenantId())).and(a.APPOINTMENT_ID.eq(actor.appointmentId()))
            .and(a.PRINCIPAL_ID.eq(actor.principalId())).and(a.STATE.eq("ACTIVE")).and(p.STATE.eq("ACTIVE")).and(t.STATE.eq("ACTIVE"))
            .and(p.PRINCIPAL_KIND.eq("SERVICE")).and(a.EFFECTIVE_FROM.le(now)).and(a.EFFECTIVE_UNTIL.isNull().or(a.EFFECTIVE_UNTIL.gt(now))).fetchOne();
        if(app==null||ancestry(d,actor.tenantId(),app.value1())==null)return Set.of();
        var g=AUTHORITY_GRANT;
        return d.select(g.SCOPE_ORGANIZATION_UNIT_ID).from(g).where(g.TENANT_ID.eq(actor.tenantId()))
            .and(g.GRANTEE_APPOINTMENT_ID.eq(actor.appointmentId())).and(g.AUTHORITY_CODE.eq(code))
            .and(g.STATE.eq("ACTIVE")).and(g.VALID_FROM.le(now)).and(g.VALID_UNTIL.isNull().or(g.VALID_UNTIL.gt(now)))
            .fetch(g.SCOPE_ORGANIZATION_UNIT_ID).stream().filter(root->ancestry(d,actor.tenantId(),root)!=null).collect(java.util.stream.Collectors.toSet());
    }
    public DueScope dueScope(Connection c,Actor actor,String code,Instant checkedAt) {
        if(!Set.of("CONTACT_TASK_RECOVER","ROUTING_REVIEW_TASK_RECOVER").contains(code))throw new IllegalArgumentException("Unknown recovery authority");
        var roots=roots(c,actor,code,checkedAt);if(roots.isEmpty())return new DueScope(false,Set.of());
        var d=db(c);var o=ORGANIZATION_UNIT;var a=APPOINTMENT;var p=PRINCIPAL;var now=checkedAt.atOffset(ZoneOffset.UTC);
        var covered=DSL.name("covered_organizations");var id=DSL.field(DSL.name("covered_organizations","id"),UUID.class);
        var ids=d.withRecursive(covered,DSL.name("id")).as(
            DSL.select(o.ORGANIZATION_UNIT_ID).from(o).where(o.TENANT_ID.eq(actor.tenantId())).and(o.ORGANIZATION_UNIT_ID.in(roots)).and(o.STATE.eq("ACTIVE"))
            .union(DSL.select(o.ORGANIZATION_UNIT_ID).from(o).join(DSL.table(covered)).on(o.PARENT_ORGANIZATION_UNIT_ID.eq(id))
                .where(o.TENANT_ID.eq(actor.tenantId())).and(o.STATE.eq("ACTIVE"))))
            .selectDistinct(a.APPOINTMENT_ID).from(a).join(DSL.table(covered)).on(a.ORGANIZATION_UNIT_ID.eq(id))
            .join(p).on(p.TENANT_ID.eq(a.TENANT_ID).and(p.PRINCIPAL_ID.eq(a.PRINCIPAL_ID)))
            .where(a.TENANT_ID.eq(actor.tenantId())).and(a.STATE.eq("ACTIVE")).and(p.STATE.eq("ACTIVE"))
            .and(a.EFFECTIVE_FROM.le(now)).and(a.EFFECTIVE_UNTIL.isNull().or(a.EFFECTIVE_UNTIL.gt(now))).fetch(a.APPOINTMENT_ID);
        return new DueScope(true,new HashSet<>(ids));
    }
    private static Set<UUID> ancestry(DSLContext db,UUID tenant,UUID node) {
        var result=new HashSet<UUID>();var o=ORGANIZATION_UNIT;
        while(node!=null){if(result.size()>=256||!result.add(node))return null;
            var row=db.select(o.PARENT_ORGANIZATION_UNIT_ID,o.STATE).from(o).where(o.TENANT_ID.eq(tenant)).and(o.ORGANIZATION_UNIT_ID.eq(node)).fetchOne();
            if(row==null||!"ACTIVE".equals(row.value2()))return null;node=row.value1();}
        return result;
    }
}
