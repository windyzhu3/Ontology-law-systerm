package io.github.windyzhu3.ontologylaw.identity.internal.persistence;

import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.util.UUID;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import static io.github.windyzhu3.ontologylaw.identity.internal.persistence.jooq.Tables.*;

public final class JooqWorkcardOwnerReader implements WorkcardOwnerReader {
    public Owner read(Connection c,UUID tenant,UUID appointment) {
        var a=APPOINTMENT;var p=PRINCIPAL;var o=ORGANIZATION_UNIT;
        var r=DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false))
                .select(a.APPOINTMENT_ID,a.REVISION,a.PRINCIPAL_ID,a.ORGANIZATION_UNIT_ID,a.ROLE_CODE,a.STATE,a.EFFECTIVE_FROM,a.EFFECTIVE_UNTIL,
                        p.REVISION,p.DISPLAY_NAME,p.PRINCIPAL_KIND,p.STATE,o.REVISION,o.UNIT_CODE,o.DISPLAY_NAME,o.STATE)
                .from(a).join(p).on(p.TENANT_ID.eq(a.TENANT_ID).and(p.PRINCIPAL_ID.eq(a.PRINCIPAL_ID)))
                .join(o).on(o.TENANT_ID.eq(a.TENANT_ID).and(o.ORGANIZATION_UNIT_ID.eq(a.ORGANIZATION_UNIT_ID)))
                .where(a.TENANT_ID.eq(tenant)).and(a.APPOINTMENT_ID.eq(appointment)).fetchOne();
        if(r==null)return null;
        return new Owner(new Appointment(new Subject("identity.appointment",appointment,r.get(a.REVISION),null),r.get(a.PRINCIPAL_ID),r.get(a.ORGANIZATION_UNIT_ID),
                r.get(a.ROLE_CODE),r.get(a.STATE),r.get(a.EFFECTIVE_FROM).toInstant(),r.get(a.EFFECTIVE_UNTIL)==null?null:r.get(a.EFFECTIVE_UNTIL).toInstant()),
                new Principal(new Subject("identity.principal",r.get(a.PRINCIPAL_ID),r.get(p.REVISION),null),r.get(p.DISPLAY_NAME),r.get(p.PRINCIPAL_KIND),r.get(p.STATE)),
                new Organization(new Subject("identity.organization_unit",r.get(a.ORGANIZATION_UNIT_ID),r.get(o.REVISION),null),r.get(o.UNIT_CODE),r.get(o.DISPLAY_NAME),r.get(o.STATE)));
    }
}
