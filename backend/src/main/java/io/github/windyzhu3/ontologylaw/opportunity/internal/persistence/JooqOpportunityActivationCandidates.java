package io.github.windyzhu3.ontologylaw.opportunity.internal.persistence;
import io.github.windyzhu3.ontologylaw.opportunity.OpportunityActivationCandidates;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.jooq.impl.DSL;
import org.jooq.SQLDialect;
import static io.github.windyzhu3.ontologylaw.opportunity.internal.persistence.jooq.Tables.OPPORTUNITY_;
public final class JooqOpportunityActivationCandidates implements OpportunityActivationCandidates {
    public boolean salesProgressed(Connection c,UUID tenant,UUID opportunity)throws SQLException {
        try(var p=c.prepareStatement("select exists(select 1 from opportunity.quote_revision where tenant_id=? and opportunity_id=?) or exists(select 1 from opportunity.contract_preparation_source where tenant_id=? and opportunity_id=?) or exists(select 1 from opportunity.quote_workflow where tenant_id=? and opportunity_id=?)")) {
            p.setObject(1,tenant);p.setObject(2,opportunity);p.setObject(3,tenant);p.setObject(4,opportunity);p.setObject(5,tenant);p.setObject(6,opportunity);
            try(var r=p.executeQuery()){r.next();return r.getBoolean(1);}
        }
    }
    public boolean currentOpen(Connection c,UUID tenant,io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject subject){
        if(!"opportunity.opportunity".equals(subject.type())||subject.revision()==null)return false;
        var o=OPPORTUNITY_;
        return DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false)).fetchExists(DSL.selectOne().from(o)
                .where(o.TENANT_ID.eq(tenant)).and(o.OPPORTUNITY_ID.eq(subject.id())).and(o.REVISION.eq(subject.revision())).and(o.CLOSED_AT.isNull()));
    }
    public List<Position> scan(Connection c,UUID tenant,Set<UUID> owners,Instant observed,Position after,int limit){
        if(limit<1||limit>100)throw new IllegalArgumentException("Bounded scan required");if(owners.isEmpty())return List.of();
        var o=OPPORTUNITY_;var predicate=o.TENANT_ID.eq(tenant).and(o.OWNER_APPOINTMENT_ID.in(owners)).and(o.CLOSED_AT.isNull()).and(o.CREATED_AT.le(observed.atOffset(ZoneOffset.UTC)));
        if(after!=null){var at=after.at().atOffset(ZoneOffset.UTC);predicate=predicate.and(o.CREATED_AT.gt(at).or(o.CREATED_AT.eq(at).and(o.OPPORTUNITY_ID.gt(after.id()))));}
        return DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false)).select(o.CREATED_AT,o.OPPORTUNITY_ID).from(o).where(predicate)
                .orderBy(o.CREATED_AT,o.OPPORTUNITY_ID).limit(limit).fetch(r->new Position(r.value1().toInstant(),r.value2()));
    }
}
