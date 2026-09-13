package io.github.windyzhu3.ontologylaw.identity.internal.persistence;

import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;
import java.util.*;
import org.jooq.*;
import org.jooq.impl.DSL;
import static io.github.windyzhu3.ontologylaw.identity.internal.persistence.jooq.Tables.*;

public final class JooqHumanIdentityReader implements HumanIdentityReader {
    private static DSLContext db(Connection c)throws SQLException {
        if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw new SQLException("Identity requires transaction","25001");
        return DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false));
    }
    // Walk only from the selected row. Missing/closed ancestors, cycles and over-deep chains fail closed.
    private static String chain(String node,String coveredAncestor) {
        return """
            exists (with recursive chain as (
              select x.organization_unit_id,x.parent_organization_unit_id,array[x.organization_unit_id] path
              from identity.organization_unit x where x.tenant_id=a.tenant_id and x.organization_unit_id=%s and x.state='ACTIVE'
              union all select x.organization_unit_id,x.parent_organization_unit_id,c.path||x.organization_unit_id
              from chain c join identity.organization_unit x on x.tenant_id=a.tenant_id and x.organization_unit_id=c.parent_organization_unit_id
              where x.state='ACTIVE' and not x.organization_unit_id=any(c.path) and cardinality(c.path)<256
            ) select 1 from chain where parent_organization_unit_id is null %s)
            """.formatted(node,coveredAncestor==null?"":"and "+coveredAncestor+"=any(path)");
    }
    public VerifiedHumanIdentity unique(Connection c,UUID tenant,String provider,byte[] hmac)throws SQLException {
        if(tenant==null||provider==null||hmac==null||hmac.length!=32)throw new Failure("UNAUTHENTICATED");
        var p=PRINCIPAL;
        var rows=db(c).select(p.PRINCIPAL_ID).from(p).where(p.TENANT_ID.eq(tenant)).and(p.IDENTITY_PROVIDER_CODE.eq(provider))
                .and(p.EXTERNAL_SUBJECT_HMAC.eq(hmac)).and(p.PRINCIPAL_KIND.eq("HUMAN")).limit(2).fetch();
        if(rows.size()!=1)throw new Failure("UNAUTHENTICATED");
        return new VerifiedHumanIdentity(tenant,rows.getFirst().value1(),provider);
    }
    public boolean tenantActive(Connection c,UUID tenant)throws SQLException{return db(c).fetchCount(TENANT,TENANT.TENANT_ID.eq(tenant).and(TENANT.STATE.eq("ACTIVE")))==1;}
    public Subject rootOrganization(Connection c,UUID tenant)throws SQLException {
        var o=ORGANIZATION_UNIT;
        var rows=db(c).select(o.ORGANIZATION_UNIT_ID,o.REVISION).from(o).where(o.TENANT_ID.eq(tenant))
                .and(o.PARENT_ORGANIZATION_UNIT_ID.isNull()).and(o.STATE.eq("ACTIVE")).limit(2).fetch();
        if(rows.size()!=1)throw new Failure("SERVICE_UNAVAILABLE");
        return new Subject("identity.organization_unit",rows.getFirst().value1(),rows.getFirst().value2(),null);
    }
    public Self self(Connection c,VerifiedHumanIdentity identity)throws SQLException {
        var d=db(c);var p=PRINCIPAL;var t=TENANT;
        var principal=d.select(p.DISPLAY_NAME,p.REVISION).from(p).join(t).on(t.TENANT_ID.eq(p.TENANT_ID))
                .where(p.TENANT_ID.eq(identity.tenantId())).and(p.PRINCIPAL_ID.eq(identity.principalId())).and(p.IDENTITY_PROVIDER_CODE.eq(identity.provider()))
                .and(p.PRINCIPAL_KIND.eq("HUMAN")).and(p.STATE.eq("ACTIVE")).and(t.STATE.eq("ACTIVE")).fetchOne();
        if(principal==null)throw new Failure("NOT_AUTHORIZED");
        var rows=d.fetch("""
            select a.appointment_id,a.revision appointment_revision,a.role_code,o.organization_unit_id,o.revision organization_revision,o.display_name
            from identity.appointment a join identity.organization_unit o on o.tenant_id=a.tenant_id and o.organization_unit_id=a.organization_unit_id
            where a.tenant_id=? and a.principal_id=? and a.state='ACTIVE'
              and a.effective_from<=clock_timestamp() and (a.effective_until is null or a.effective_until>clock_timestamp())
              and %s order by a.appointment_id limit 51
            """.formatted(chain("a.organization_unit_id",null)),identity.tenantId(),identity.principalId());
        if(rows.size()>50)throw new Failure("SERVICE_UNAVAILABLE");
        return new Self(identity,new Subject("identity.principal",identity.principalId(),principal.value2(),null),principal.value1(),rows.stream().map(JooqHumanIdentityReader::choice).toList());
    }
    public List<DelegatedChoice> delegated(Connection c,VerifiedHumanIdentity identity,UUID own)throws SQLException {
        var rows=db(c).fetch("""
            select distinct a.appointment_id,a.revision appointment_revision,a.role_code,a.principal_id,
              o.organization_unit_id,o.revision organization_revision,o.display_name
            from identity.delegation_grant dg
            join identity.authority_grant g on g.tenant_id=dg.tenant_id and g.authority_grant_id=dg.source_authority_grant_id and g.grantee_appointment_id=dg.delegator_appointment_id
            join identity.appointment a on a.tenant_id=dg.tenant_id and a.appointment_id=dg.delegator_appointment_id
            join identity.principal p on p.tenant_id=a.tenant_id and p.principal_id=a.principal_id
            join identity.organization_unit o on o.tenant_id=a.tenant_id and o.organization_unit_id=a.organization_unit_id
            where dg.tenant_id=? and dg.delegate_appointment_id=? and dg.state='ACTIVE' and g.state='ACTIVE'
              and p.state='ACTIVE' and p.principal_kind='HUMAN' and a.state='ACTIVE'
              and dg.valid_from<=clock_timestamp() and (dg.valid_until is null or dg.valid_until>clock_timestamp())
              and g.valid_from<=clock_timestamp() and (g.valid_until is null or g.valid_until>clock_timestamp())
              and a.effective_from<=clock_timestamp() and (a.effective_until is null or a.effective_until>clock_timestamp())
              and g.authority_code in ('LEAD_CAPTURE','LEAD_INGRESS_RESOLVE','LEAD_INGRESS_COMPLETE','LEAD_ASSIGN','LEAD_ROUTING_DECIDE','SOURCE_INTAKE_REQUEST_ACK','SALES_CONTACT_OWNER','LEAD_VALIDITY_REVIEW')
              and %s and %s order by a.appointment_id limit 51
            """.formatted(chain("a.organization_unit_id",null),chain("dg.scope_organization_unit_id","g.scope_organization_unit_id")),identity.tenantId(),own);
        if(rows.size()>50)throw new Failure("SERVICE_UNAVAILABLE");
        return rows.stream().map(row->new DelegatedChoice(choice(row),row.get("principal_id",UUID.class))).toList();
    }
    private static Choice choice(org.jooq.Record row) {
        String role=switch(row.get("role_code",String.class)){case "IDENTITY_ADMIN"->"身份管理员";case "INTAKE_OPERATOR"->"接入人员";case "ROUTING_SUPERVISOR"->"分配主管";case "CONTACT_OPERATOR"->"联系人员";default->"业务任职";};
        String label=row.get("display_name",String.class)+" · "+role;
        if(label.codePointCount(0,label.length())>200)label=label.substring(0,label.offsetByCodePoints(0,200));
        return new Choice(new Subject("identity.appointment",row.get("appointment_id",UUID.class),row.get("appointment_revision",Long.class),null),new Subject("identity.organization_unit",row.get("organization_unit_id",UUID.class),row.get("organization_revision",Long.class),null),label);
    }
}
