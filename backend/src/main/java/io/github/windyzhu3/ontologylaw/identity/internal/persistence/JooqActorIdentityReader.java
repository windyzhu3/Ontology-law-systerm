package io.github.windyzhu3.ontologylaw.identity.internal.persistence;

import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.jooq.*;
import org.jooq.impl.DSL;
import static io.github.windyzhu3.ontologylaw.identity.internal.persistence.jooq.Tables.*;

public final class JooqActorIdentityReader implements ActorIdentityReader {
    public boolean active(Connection c,Actor actor) throws SQLException {
        if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw new SQLException("Identity requires transaction","25001");
        if(actor==null)return false;
        var db=DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false));
        var now=db.select(DSL.field("clock_timestamp()",OffsetDateTime.class)).fetchOne(0,OffsetDateTime.class);
        return appointment(db,actor.tenantId(),actor.principalId(),actor.appointmentId(),actor.principalKind(),now)
                && (actor.onBehalfPrincipalId()==null || appointment(db,actor.tenantId(),actor.onBehalfPrincipalId(),actor.onBehalfAppointmentId(),PrincipalKind.HUMAN,now));
    }
    private static boolean appointment(DSLContext db,UUID tenant,UUID principal,UUID appointment,PrincipalKind kind,OffsetDateTime now) {
        var a=APPOINTMENT;var p=PRINCIPAL;var t=TENANT;
        var rows=db.select(a.ORGANIZATION_UNIT_ID).from(a).join(p).on(p.TENANT_ID.eq(a.TENANT_ID).and(p.PRINCIPAL_ID.eq(a.PRINCIPAL_ID)))
                .join(t).on(t.TENANT_ID.eq(a.TENANT_ID)).where(a.TENANT_ID.eq(tenant)).and(a.APPOINTMENT_ID.eq(appointment)).and(a.PRINCIPAL_ID.eq(principal))
                .and(a.STATE.eq("ACTIVE")).and(p.STATE.eq("ACTIVE")).and(t.STATE.eq("ACTIVE")).and(p.PRINCIPAL_KIND.eq(kind.name()))
                .and(a.EFFECTIVE_FROM.le(now)).and(a.EFFECTIVE_UNTIL.isNull().or(a.EFFECTIVE_UNTIL.gt(now))).fetch();
        if(rows.size()!=1)return false;UUID node=rows.getFirst().value1();var seen=new HashSet<UUID>();var o=ORGANIZATION_UNIT;
        while(node!=null) {
            if(seen.size()>=256||!seen.add(node))return false;
            var row=db.select(o.PARENT_ORGANIZATION_UNIT_ID,o.STATE).from(o).where(o.TENANT_ID.eq(tenant)).and(o.ORGANIZATION_UNIT_ID.eq(node)).fetchOne();
            if(row==null||!"ACTIVE".equals(row.value2()))return false;node=row.value1();
        }
        return true;
    }
}
