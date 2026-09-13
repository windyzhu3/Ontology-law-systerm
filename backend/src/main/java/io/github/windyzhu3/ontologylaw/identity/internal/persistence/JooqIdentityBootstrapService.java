package io.github.windyzhu3.ontologylaw.identity.internal.persistence;

import io.github.windyzhu3.ontologylaw.identity.IdentityBootstrapService;
import java.sql.*;
import java.util.*;
import java.time.*;
import org.jooq.*;
import org.jooq.impl.DSL;
import static io.github.windyzhu3.ontologylaw.identity.internal.persistence.jooq.Tables.*;

public final class JooqIdentityBootstrapService implements IdentityBootstrapService {
    private static DSLContext db(Connection c)throws SQLException {
        if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw new SQLException("Bootstrap requires transaction","25001");
        return DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false));
    }
    public boolean initialized(Connection c,UUID tenant,String code)throws SQLException {
        var t=TENANT;var rows=db(c).select(t.TENANT_ID,t.TENANT_CODE).from(t).where(t.TENANT_ID.eq(tenant).or(t.TENANT_CODE.eq(code))).limit(2).fetch();
        if(rows.isEmpty())return false;
        if(rows.size()!=1||!rows.getFirst().value1().equals(tenant)||!rows.getFirst().value2().equals(code))throw invalid();return true;
    }
    public Facts create(Connection c,UUID tenant,Manifest m,byte[] hmac)throws SQLException {
        var d=db(c);var now=d.select(DSL.field("clock_timestamp()",OffsetDateTime.class)).fetchOne(0,OffsetDateTime.class);
        if(m.effectiveFrom().isAfter(now.toInstant())||hmac.length!=32||initialized(c,tenant,m.tenantCode()))throw invalid();
        UUID root=UUID.randomUUID(),principal=UUID.randomUUID(),appointment=UUID.randomUUID();var grants=new ArrayList<UUID>();
        var t=TENANT;d.insertInto(t).set(t.TENANT_ID,tenant).set(t.TENANT_CODE,m.tenantCode()).set(t.DISPLAY_NAME,m.tenantDisplayName()).set(t.STATE,"ACTIVE").set(t.REVISION,0L).set(t.CREATED_AT,now).execute();
        var p=PRINCIPAL;d.insertInto(p).set(p.TENANT_ID,tenant).set(p.PRINCIPAL_ID,principal).set(p.PRINCIPAL_KIND,"HUMAN").set(p.IDENTITY_PROVIDER_CODE,m.identityProviderCode()).set(p.EXTERNAL_SUBJECT_HMAC,hmac).set(p.DISPLAY_NAME,m.principalDisplayName()).set(p.STATE,"ACTIVE").set(p.REVISION,0L).set(p.CREATED_AT,now).execute();
        var o=ORGANIZATION_UNIT;d.insertInto(o).set(o.TENANT_ID,tenant).set(o.ORGANIZATION_UNIT_ID,root).set(o.UNIT_CODE,m.rootCode()).set(o.DISPLAY_NAME,m.rootDisplayName()).set(o.STATE,"ACTIVE").set(o.REVISION,0L).set(o.CREATED_AT,now).execute();
        var a=APPOINTMENT;var effective=OffsetDateTime.ofInstant(m.effectiveFrom(),ZoneOffset.UTC);
        d.insertInto(a).set(a.TENANT_ID,tenant).set(a.APPOINTMENT_ID,appointment).set(a.PRINCIPAL_ID,principal).set(a.ORGANIZATION_UNIT_ID,root).set(a.ROLE_CODE,"IDENTITY_ADMIN").set(a.EFFECTIVE_FROM,effective).set(a.STATE,"ACTIVE").set(a.REVISION,0L).set(a.CREATED_AT,now).execute();
        var g=AUTHORITY_GRANT;
        for(String code:MANAGEMENT_CODES){UUID id=UUID.randomUUID();grants.add(id);d.insertInto(g).set(g.TENANT_ID,tenant).set(g.AUTHORITY_GRANT_ID,id).set(g.GRANTEE_APPOINTMENT_ID,appointment).set(g.GRANTED_BY_APPOINTMENT_ID,appointment).set(g.SCOPE_ORGANIZATION_UNIT_ID,root).set(g.AUTHORITY_CODE,code).set(g.VALID_FROM,effective).set(g.STATE,"ACTIVE").set(g.REVISION,0L).set(g.CREATED_AT,now).execute();}
        return new Facts(tenant,root,principal,appointment,grants,now.toInstant());
    }
    public void verify(Connection c,Manifest m,byte[] hmac,Facts f)throws SQLException {
        var d=db(c);var tenant=f.tenant();var now=OffsetDateTime.ofInstant(f.createdAt(),ZoneOffset.UTC);var effective=OffsetDateTime.ofInstant(m.effectiveFrom(),ZoneOffset.UTC);
        var t=TENANT;exact(d,t,t.TENANT_ID.eq(tenant),t.TENANT_CODE.eq(m.tenantCode()).and(t.DISPLAY_NAME.eq(m.tenantDisplayName())).and(t.STATE.eq("ACTIVE")).and(t.REVISION.eq(0L)).and(t.CREATED_AT.eq(now)),1);
        var p=PRINCIPAL;exact(d,p,p.TENANT_ID.eq(tenant).and(p.PRINCIPAL_ID.eq(f.principal())),p.PRINCIPAL_KIND.eq("HUMAN").and(p.IDENTITY_PROVIDER_CODE.eq(m.identityProviderCode())).and(p.EXTERNAL_SUBJECT_HMAC.eq(hmac)).and(p.DISPLAY_NAME.eq(m.principalDisplayName())).and(p.STATE.eq("ACTIVE")).and(p.REVISION.eq(0L)).and(p.CREATED_AT.eq(now)),1);
        var o=ORGANIZATION_UNIT;exact(d,o,o.TENANT_ID.eq(tenant).and(o.ORGANIZATION_UNIT_ID.eq(f.root())),o.PARENT_ORGANIZATION_UNIT_ID.isNull().and(o.UNIT_CODE.eq(m.rootCode())).and(o.DISPLAY_NAME.eq(m.rootDisplayName())).and(o.STATE.eq("ACTIVE")).and(o.CLOSED_AT.isNull()).and(o.REVISION.eq(0L)).and(o.CREATED_AT.eq(now)),1);
        var a=APPOINTMENT;exact(d,a,a.TENANT_ID.eq(tenant).and(a.APPOINTMENT_ID.eq(f.appointment())),a.PRINCIPAL_ID.eq(f.principal()).and(a.ORGANIZATION_UNIT_ID.eq(f.root())).and(a.ROLE_CODE.eq("IDENTITY_ADMIN")).and(a.EFFECTIVE_FROM.eq(effective)).and(a.EFFECTIVE_UNTIL.isNull()).and(a.STATE.eq("ACTIVE")).and(a.REVISION.eq(0L)).and(a.CREATED_AT.eq(now)),1);
        var g=AUTHORITY_GRANT;var originalGrants=g.TENANT_ID.eq(tenant).and(g.AUTHORITY_GRANT_ID.in(f.grants()));exact(d,g,originalGrants,g.GRANTEE_APPOINTMENT_ID.eq(f.appointment()).and(g.GRANTED_BY_APPOINTMENT_ID.eq(f.appointment())).and(g.SCOPE_ORGANIZATION_UNIT_ID.eq(f.root())).and(g.AUTHORITY_CODE.in(MANAGEMENT_CODES)).and(g.VALID_FROM.eq(effective)).and(g.VALID_UNTIL.isNull()).and(g.STATE.eq("ACTIVE")).and(g.REVOKED_AT.isNull()).and(g.REVOCATION_REASON_CODE.isNull()).and(g.REVISION.eq(0L)).and(g.CREATED_AT.eq(now)),4);
        if(d.selectCount().from(g).where(originalGrants).groupBy(g.AUTHORITY_CODE).fetch().size()!=4)throw invalid();
        for(int i=0;i<4;i++)if(d.fetchCount(g,g.TENANT_ID.eq(tenant).and(g.AUTHORITY_GRANT_ID.eq(f.grants().get(i))).and(g.AUTHORITY_CODE.eq(MANAGEMENT_CODES.get(i))))!=1)throw invalid();
    }
    private static void exact(DSLContext d,Table<?> table,Condition tenant,Condition shape,int count)throws SQLException {
        if(d.fetchCount(table,tenant)!=count||d.fetchCount(table,tenant.and(shape))!=count)throw invalid();
    }
    private static SQLException invalid(){return new SQLException("BOOTSTRAP_ORIGINAL_STATE_CONFLICT","23000");}
}
