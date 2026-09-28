package io.github.windyzhu3.ontologylaw.identity.internal.persistence;

import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.jooq.*;
import org.jooq.impl.DSL;
import static io.github.windyzhu3.ontologylaw.identity.internal.persistence.jooq.Tables.*;

public final class JooqR1ServiceAuthorityReader implements R1ServiceAuthorityReader,R2OpportunityServiceScopeReader {
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
        return scope(c,actor,code,checkedAt);
    }
    public DueScope opportunityScope(Connection c,Actor actor,String code,Instant checkedAt) throws SQLException {
        if(!Set.of("OPPORTUNITY_TASK_ACTIVATE","OPPORTUNITY_TASK_RECOVER","CONTRACT_TASK_RECOVER").contains(code))throw new IllegalArgumentException("Unknown Opportunity service authority");
        var original=scope(c,actor,code,checkedAt);if(!original.authorized())return original;
        var rootIds=roots(c,actor,code,checkedAt);var owners=new HashSet<>(original.ownerAppointments());
        // A receiver may be appointed in another organization but already hold a complete grant over
        // the frozen business scope. Intersect grant coverage with SERVICE coverage before scanning;
        // every returned task is still independently authorized against its own frozen source scope.
        var rootArray=c.createArrayOf("uuid",rootIds.toArray(UUID[]::new));
        String sql="with recursive covered(id) as (select organization_unit_id from identity.organization_unit where tenant_id=? and organization_unit_id=any(?) and state='ACTIVE' union select o.organization_unit_id from identity.organization_unit o join covered x on o.parent_organization_unit_id=x.id where o.tenant_id=? and o.state='ACTIVE'), ancestors(id,parent_id) as (select organization_unit_id,parent_organization_unit_id from identity.organization_unit where tenant_id=? and organization_unit_id=any(?) and state='ACTIVE' union select o.organization_unit_id,o.parent_organization_unit_id from identity.organization_unit o join ancestors x on o.organization_unit_id=x.parent_id where o.tenant_id=? and o.state='ACTIVE') select distinct a.appointment_id from identity.appointment a join identity.principal p on p.tenant_id=a.tenant_id and p.principal_id=a.principal_id join identity.organization_unit own_org on own_org.tenant_id=a.tenant_id and own_org.organization_unit_id=a.organization_unit_id join identity.authority_grant g on g.tenant_id=a.tenant_id and g.grantee_appointment_id=a.appointment_id where a.tenant_id=? and a.state='ACTIVE' and p.state='ACTIVE' and p.principal_kind='HUMAN' and own_org.state='ACTIVE' and a.effective_from<=? and (a.effective_until is null or a.effective_until>?) and g.state='ACTIVE' and g.authority_code='SALES_OPPORTUNITY_OWNER' and g.valid_from<=? and (g.valid_until is null or g.valid_until>?) and (g.scope_organization_unit_id in (select id from covered) or g.scope_organization_unit_id in (select id from ancestors))";
        try(var p=c.prepareStatement(sql)) {
            p.setObject(1,actor.tenantId());p.setArray(2,rootArray);p.setObject(3,actor.tenantId());p.setObject(4,actor.tenantId());p.setArray(5,rootArray);p.setObject(6,actor.tenantId());p.setObject(7,actor.tenantId());
            var now=checkedAt.atOffset(ZoneOffset.UTC);for(int i=8;i<=11;i++)p.setObject(i,now);
            try(var r=p.executeQuery()){while(r.next())owners.add(r.getObject(1,UUID.class));}
        }finally{rootArray.free();}
        return new DueScope(true,owners);
    }
    public OwnerExceptionScope ownerExceptionScope(Connection c,Actor actor,Instant checkedAt) {
        Objects.requireNonNull(checkedAt);
        if(actor==null)return new OwnerExceptionScope(false,Set.of());
        var roots=roots(c,actor,"OPPORTUNITY_OWNER_EXCEPTION_DISCOVER",checkedAt);
        if(roots.isEmpty())return new OwnerExceptionScope(false,Set.of());
        var d=db(c);var o=ORGANIZATION_UNIT;
        var covered=DSL.name("owner_exception_organizations");
        var id=DSL.field(DSL.name("owner_exception_organizations","id"),UUID.class);
        // Do not join to active owners: their invalidity is precisely what must remain discoverable.
        var organizations=d.withRecursive(covered,DSL.name("id")).as(
            DSL.select(o.ORGANIZATION_UNIT_ID).from(o).where(o.TENANT_ID.eq(actor.tenantId()))
                .and(o.ORGANIZATION_UNIT_ID.in(roots)).and(o.STATE.eq("ACTIVE"))
            .union(DSL.select(o.ORGANIZATION_UNIT_ID).from(o).join(DSL.table(covered)).on(o.PARENT_ORGANIZATION_UNIT_ID.eq(id))
                .where(o.TENANT_ID.eq(actor.tenantId())).and(o.STATE.eq("ACTIVE"))))
            .select(id).from(DSL.table(covered)).fetch(id);
        return new OwnerExceptionScope(true,new HashSet<>(organizations));
    }
    private DueScope scope(Connection c,Actor actor,String code,Instant checkedAt) {
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
