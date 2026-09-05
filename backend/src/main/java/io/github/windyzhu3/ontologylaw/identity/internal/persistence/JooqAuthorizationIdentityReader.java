package io.github.windyzhu3.ontologylaw.identity.internal.persistence;

import io.github.windyzhu3.ontologylaw.identity.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.jooq.*;
import org.jooq.impl.DSL;
import static io.github.windyzhu3.ontologylaw.identity.internal.persistence.jooq.Tables.*;

public final class JooqAuthorizationIdentityReader implements AuthorizationIdentityReader {
    private DSLContext db(Connection c) { return DSL.using(c,SQLDialect.POSTGRES); }
    public AuthorizationService.Subject organization(Connection c, UUID tenant, String code) {
        var t=ORGANIZATION_UNIT;
        var rows=db(c).select(t.ORGANIZATION_UNIT_ID,t.REVISION).from(t)
                .where(t.TENANT_ID.eq(tenant)).and(t.UNIT_CODE.eq(code)).and(t.STATE.eq("ACTIVE")).fetch();
        return rows.size()!=1?null:new AuthorizationService.Subject("identity.organization_unit",rows.getFirst().value1(),rows.getFirst().value2(),null);
    }
    public Owner owner(Connection c,UUID tenant,UUID appointment,Instant now) {
        var a=APPOINTMENT;var p=PRINCIPAL;
        var row=db(c).select(a.PRINCIPAL_ID,a.ORGANIZATION_UNIT_ID,a.STATE,a.EFFECTIVE_FROM,a.EFFECTIVE_UNTIL,a.REVISION,p.STATE,p.REVISION)
                .from(a).join(p).on(p.TENANT_ID.eq(a.TENANT_ID).and(p.PRINCIPAL_ID.eq(a.PRINCIPAL_ID)))
                .where(a.TENANT_ID.eq(tenant)).and(a.APPOINTMENT_ID.eq(appointment)).fetchOne();
        if(row==null)return null;
        boolean active="ACTIVE".equals(row.get(a.STATE)) && "ACTIVE".equals(row.get(p.STATE))
                && !row.get(a.EFFECTIVE_FROM).toInstant().isAfter(now)
                && (row.get(a.EFFECTIVE_UNTIL)==null || row.get(a.EFFECTIVE_UNTIL).toInstant().isAfter(now));
        var appSelector=new AuthorizationService.Subject("identity.appointment",appointment,row.get(a.REVISION),null);
        var principalSelector=new AuthorizationService.Subject("identity.principal",row.get(a.PRINCIPAL_ID),row.get(p.REVISION),null);
        return new Owner(appointment,row.get(a.PRINCIPAL_ID),row.get(a.ORGANIZATION_UNIT_ID),active,
                appSelector+":"+row.get(a.STATE)+":"+row.get(a.EFFECTIVE_FROM)+":"+row.get(a.EFFECTIVE_UNTIL)+";"+principalSelector+":"+row.get(p.STATE));
    }
}
