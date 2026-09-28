package io.github.windyzhu3.ontologylaw.identity.internal.persistence;

import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;
import java.time.*;
import java.util.*;

public final class JooqOpportunityOwnerExceptionAuthorityReader implements OpportunityOwnerExceptionAuthorityReader {
    private final AuthorizationIdentityReader identities=AuthorizationIdentityReader.databaseBacked();
    private final AuthorizationService authorization=AuthorizationService.databaseBacked();
    private final R1AuthorityReader authorities=R1AuthorityReader.databaseBacked();
    public String restrictedOrganizationLabel(Connection c,UUID tenant,UUID organization)throws SQLException {
        try(var p=c.prepareStatement("select unit_code from identity.organization_unit where tenant_id=? and organization_unit_id=?")){p.setObject(1,tenant);p.setObject(2,organization);try(var rs=p.executeQuery()){return rs.next()?rs.getString(1):null;}}
    }
    public boolean hasAuthority(Connection c,Actor actor,String code,Instant now)throws SQLException {
        var current=identities.owner(c,actor.tenantId(),actor.appointmentId(),now);
        if(actor.principalKind()!=PrincipalKind.HUMAN||actor.onBehalfAppointmentId()!=null||current==null||!current.active()||!current.principalId().equals(actor.principalId())||!activeOrganization(c,actor.tenantId(),current.organizationId()))return false;
        try(var p=c.prepareStatement("select 1 from identity.authority_grant where tenant_id=? and grantee_appointment_id=? and authority_code=? and state='ACTIVE' and valid_from<=? and (valid_until is null or valid_until>?) limit 1")){
            p.setObject(1,actor.tenantId());p.setObject(2,actor.appointmentId());p.setString(3,code);p.setObject(4,OffsetDateTime.ofInstant(now,ZoneOffset.UTC));p.setObject(5,OffsetDateTime.ofInstant(now,ZoneOffset.UTC));try(var rs=p.executeQuery()){return rs.next();}
        }
    }
    public List<UUID> receiverAppointments(Connection c,UUID tenant,UUID after,int limit)throws SQLException {
        if(limit<1||limit>100)throw new IllegalArgumentException("Invalid page bound");
        try(var p=c.prepareStatement("select distinct a.appointment_id from identity.appointment a join identity.principal p on p.tenant_id=a.tenant_id and p.principal_id=a.principal_id join identity.authority_grant g on g.tenant_id=a.tenant_id and g.grantee_appointment_id=a.appointment_id where a.tenant_id=? and a.state='ACTIVE' and p.state='ACTIVE' and p.principal_kind='HUMAN' and g.state='ACTIVE' and g.authority_code='SALES_OPPORTUNITY_OWNER'"+(after==null?"":" and a.appointment_id>?")+" order by a.appointment_id limit ?")){
            p.setObject(1,tenant);int n=2;if(after!=null)p.setObject(n++,after);p.setInt(n,limit);var values=new ArrayList<UUID>();try(var rs=p.executeQuery()){while(rs.next())values.add(rs.getObject(1,UUID.class));}return List.copyOf(values);
        }
    }
    private record HistoricalOrganization(UUID appointment){}
    public UUID historicalOrganization(Connection c,UUID tenant,UUID appointment)throws SQLException {
        return JooqAuthorizationService.lockedFacts(c,tenant,new HistoricalOrganization(appointment),()->{
            var a=io.github.windyzhu3.ontologylaw.identity.internal.persistence.jooq.Tables.APPOINTMENT;
            return org.jooq.impl.DSL.using(c,org.jooq.SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false))
                .select(a.ORGANIZATION_UNIT_ID).from(a).where(a.TENANT_ID.eq(tenant)).and(a.APPOINTMENT_ID.eq(appointment)).fetchOne(a.ORGANIZATION_UNIT_ID);
        });
    }
    public Assessment receiver(Connection c,UUID tenant,UUID appointment,UUID organization,List<Subject> facts,Instant now)throws SQLException {
        return receiver(c,tenant,appointment,organization,facts,now,"SALES_OPPORTUNITY_OWNER");
    }
    public Assessment receiver(Connection c,UUID tenant,UUID appointment,UUID organization,List<Subject> facts,Instant now,String taskAuthority)throws SQLException {
        if(!Set.of("SALES_OPPORTUNITY_OWNER","QUOTE_PREPARE","QUOTE_DELIVER","QUOTE_RESPONSE").contains(taskAuthority))throw new IllegalArgumentException("Unsupported owner authority");
        var codes=new LinkedHashSet<>(List.of("SALES_OPPORTUNITY_OWNER",taskAuthority));
        var registration=identities.registration(c,tenant,appointment);var owner=identities.owner(c,tenant,appointment,now);
        boolean active=registration!=null && registration.principalKind()==PrincipalKind.HUMAN && owner!=null && owner.active() && activeOrganization(c,tenant,owner.organizationId());
        if(registration==null)return new Assessment(false,false,false,List.of());
        var actor=new Actor(tenant,registration.principalId(),appointment,null,null,PrincipalKind.HUMAN);
        if(!active) {
            boolean denied=false;for(var code:codes)for(var fact:facts)denied|=denied(c,actor,fact,code,now);
            return new Assessment(false,false,denied,List.of());
        }
        var evidence=new ArrayList<AuthorizationSnapshot>(); boolean allowed=!facts.isEmpty(),denied=false;
        for(var code:codes)for(var fact:facts) {
            denied|=denied(c,actor,fact,code,now);
            var request=authorities.select(c,actor,fact,organization,"OPPORTUNITY_OWNER",code);
            if(request==null){allowed=false;continue;}
            var result=authorization.evaluate(c,request,true);evidence.add(result);allowed&=result.allowed();
        }
        return new Assessment(true,allowed&&!denied,denied,evidence);
    }
    public boolean permitted(Connection c,Actor actor,UUID organization,List<Subject> facts,String code)throws SQLException {
        if(facts.isEmpty())return false;
        return authorities.authorizeAll(c,actor,facts,organization,"OPPORTUNITY_OWNER",code).size()==facts.size();
    }
    public List<UUID> supervisors(Connection c,UUID tenant,UUID organization,List<Subject> facts)throws SQLException {
        var candidates=new ArrayList<Actor>();
        try(var p=c.prepareStatement("with recursive ancestors(id,parent_id,depth,visited) as (select organization_unit_id,parent_organization_unit_id,1,array[organization_unit_id] from identity.organization_unit where tenant_id=? and organization_unit_id=? union all select o.organization_unit_id,o.parent_organization_unit_id,x.depth+1,x.visited||o.organization_unit_id from identity.organization_unit o join ancestors x on o.organization_unit_id=x.parent_id where o.tenant_id=? and x.depth<256 and not o.organization_unit_id=any(x.visited)) select distinct a.appointment_id,a.principal_id from identity.appointment a join identity.principal p on p.tenant_id=a.tenant_id and p.principal_id=a.principal_id join identity.authority_grant g on g.tenant_id=a.tenant_id and g.grantee_appointment_id=a.appointment_id where a.tenant_id=? and p.principal_kind='HUMAN' and a.state='ACTIVE' and p.state='ACTIVE' and g.state='ACTIVE' and g.scope_organization_unit_id in (select id from ancestors) and g.authority_code='OPPORTUNITY_OWNER_EXCEPTION_RESOLVE' order by a.appointment_id limit 257")) {
            p.setObject(1,tenant);p.setObject(2,organization);p.setObject(3,tenant);p.setObject(4,tenant);try(var r=p.executeQuery()){while(r.next())candidates.add(new Actor(tenant,r.getObject(2,UUID.class),r.getObject(1,UUID.class),null,null));}
        }
        // An unresolved routing bound must not discard the underlying Owner exception.
        if(candidates.size()>256)return List.of();
        var result=new ArrayList<UUID>();for(var actor:candidates)
            if(permitted(c,actor,organization,facts,"OPPORTUNITY_OWNER_EXCEPTION_READ") && permitted(c,actor,organization,facts,"OPPORTUNITY_OWNER_EXCEPTION_RESOLVE"))result.add(actor.appointmentId());
        return List.copyOf(result);
    }
    private boolean activeOrganization(Connection c,UUID tenant,UUID organization)throws SQLException {
        try(var p=c.prepareStatement("select state from identity.organization_unit where tenant_id=? and organization_unit_id=?")) {
            p.setObject(1,tenant);p.setObject(2,organization);try(var r=p.executeQuery()){return r.next()&&"ACTIVE".equals(r.getString(1));}
        }
    }
    private boolean denied(Connection c,Actor actor,Subject fact,String code,Instant now)throws SQLException {
        String selector=fact.revision()!=null?"object_subject_revision=?":"object_subject_hash=?";
        try(var p=c.prepareStatement("select 1 from identity.object_access_grant where tenant_id=? and grantee_principal_id=? and access_code=? and effect_code='DENY' and state='ACTIVE' and object_subject_type=? and object_subject_id=? and "+selector+" and valid_from<=? and (valid_until is null or valid_until>?) limit 1")) {
            p.setObject(1,actor.tenantId());p.setObject(2,actor.principalId());p.setString(3,code);p.setString(4,fact.type());p.setObject(5,fact.id());p.setObject(6,fact.revision()!=null?fact.revision():Base64.getUrlDecoder().decode(fact.hash()));
            var at=OffsetDateTime.ofInstant(now,ZoneOffset.UTC);p.setObject(7,at);p.setObject(8,at);try(var r=p.executeQuery()){return r.next();}
        }
    }
}
