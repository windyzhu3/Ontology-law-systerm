package io.github.windyzhu3.ontologylaw.opportunity.internal.persistence;

import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.opportunity.OpportunityOwnerExceptionService.Reason;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;
import java.time.*;
import java.util.*;

public final class JooqOpportunityOwnerExceptionCandidates implements OpportunityOwnerExceptionCandidates {
    private final OpportunityOwnerExceptionChecks checks;
    private final OpportunityResponsibilityReader responsibilities=OpportunityResponsibilityReader.databaseBacked();
    private final OpportunityOwnerExceptionAuthorityReader identities=OpportunityOwnerExceptionAuthorityReader.databaseBacked();
    private final R2OpportunityServiceScopeReader scopes=R2OpportunityServiceScopeReader.databaseBacked();
    private record Row(Position position,Subject opportunity,UUID frozenOwner,boolean activeCycle,boolean closed) {}
    public JooqOpportunityOwnerExceptionCandidates(OpportunityOwnerExceptionChecks checks){this.checks=Objects.requireNonNull(checks);}
    public Page scan(Connection c,Actor service,Instant observed,Position after,int limit)throws SQLException {
        Objects.requireNonNull(observed);if(limit<1 || limit>100)throw new IllegalArgumentException("Scan limit must be 1 to 100");
        Instant now;try(var p=c.prepareStatement("select clock_timestamp()");var r=p.executeQuery()){r.next();now=r.getObject(1,OffsetDateTime.class).toInstant();}
        if(observed.isAfter(now))throw new IllegalArgumentException("Future observation forbidden");
        var scope=scopes.ownerExceptionScope(c,service,now);if(!scope.authorized())throw new SQLException("Discovery scope not authorized","42501");
        if(scope.organizations().isEmpty())return new Page(List.of(),after,0,0,true);
        var rows=new ArrayList<Row>();
        String cursor=after==null?"":" and (created_at>? or (created_at=? and opportunity_id>?))";
        String active="exists(select 1 from opportunity.owner_exception e where e.tenant_id=o.tenant_id and e.opportunity_id=o.opportunity_id and e.is_current and e.state in ('ACTIVE','COORDINATING'))";
        try(var p=c.prepareStatement("select opportunity_id,revision,owner_appointment_id,created_at,"+active+",closed_at is not null from opportunity.opportunity o where tenant_id=? and (closed_at is null or "+active+") and created_at<=?"+cursor+" order by created_at,opportunity_id limit ?")) {
            int i=1;p.setObject(i++,service.tenantId());p.setObject(i++,observed.atOffset(ZoneOffset.UTC));
            if(after!=null){p.setObject(i++,after.at().atOffset(ZoneOffset.UTC));p.setObject(i++,after.at().atOffset(ZoneOffset.UTC));p.setObject(i++,after.id());}p.setInt(i,limit);
            try(var r=p.executeQuery()){while(r.next()){UUID id=r.getObject(1,UUID.class);rows.add(new Row(new Position(r.getObject(4,OffsetDateTime.class).toInstant(),id),new Subject("opportunity.opportunity",id,r.getLong(2),null),r.getObject(3,UUID.class),r.getBoolean(5),r.getBoolean(6)));}}
        }
        int diagnostics=0;var candidates=new ArrayList<Candidate>();Position position=after;
        for(var row:rows) {
            position=row.position();
            UUID organization=identities.historicalOrganization(c,service.tenantId(),row.frozenOwner());
            if(organization==null){diagnostics++;continue;}if(!scope.organizations().contains(organization))continue;
            try {
                if(!checks.canDiscover(c,service,row.opportunity(),scope.organizations()))continue;
                if(row.closed()) {candidates.add(new Candidate(row.opportunity(),activeBasis(c,service.tenantId(),row.opportunity()),Set.of()));continue;}
                var current=responsibilities.current(c,service.tenantId(),row.opportunity());
                var observation=checks.inspect(c,service.tenantId(),row.opportunity(),current,observed);
                if(row.activeCycle() && unchanged(c,service.tenantId(),row.opportunity(),current,observation))continue;
                if(row.activeCycle() || !observation.reasons().isEmpty() && !observation.reasons().equals(Set.of(Reason.SUPERVISOR_UNRESOLVED)))
                    candidates.add(new Candidate(row.opportunity(),current.basis(),observation.reasons()));
            }catch(SQLException failure) {
                if(!Set.of("40001","42501","22000").contains(failure.getSQLState()))throw failure;
                diagnostics++;
            }
        }
        return new Page(candidates,position,rows.size(),diagnostics,rows.size()<limit);
    }
    private Subject activeBasis(Connection c,UUID tenant,Subject opportunity)throws SQLException {
        try(var p=c.prepareStatement("select basis_type,basis_id,basis_revision from opportunity.owner_exception where tenant_id=? and opportunity_id=? and is_current and state in ('ACTIVE','COORDINATING')")) {
            p.setObject(1,tenant);p.setObject(2,opportunity.id());try(var r=p.executeQuery()){if(!r.next())throw new SQLException("Exception cycle changed","40001");return new Subject(r.getString(1),r.getObject(2,UUID.class),r.getLong(3),null);}
        }
    }
    private boolean unchanged(Connection c,UUID tenant,Subject opportunity,
            OpportunityResponsibilityReader.Responsibility responsibility,
            OpportunityOwnerExceptionService.Observation observation)throws SQLException {
        // Healthy/closed cycles still need their durable resolution command. Only suppress an
        // identical outstanding observation; every scan has already rechecked current authority.
        if(observation.reasons().isEmpty())return false;
        Instant currentTime;
        try(var p=c.prepareStatement("select clock_timestamp()");var r=p.executeQuery()) {
            r.next();currentTime=r.getObject(1,OffsetDateTime.class).toInstant();
        }
        try(var p=c.prepareStatement("select * from opportunity.owner_exception where tenant_id=? and opportunity_id=? and is_current and state in ('ACTIVE','COORDINATING')")) {
            p.setObject(1,tenant);p.setObject(2,opportunity.id());
            try(var r=p.executeQuery()) {
                if(!r.next())return false;
                var old=JooqOpportunityOwnerExceptionService.snapshot(r);
                if(r.next())throw new SQLException("Multiple active cycles","40001");
                var desired=old.state()==OpportunityOwnerExceptionService.State.COORDINATING
                        && old.reviewDueAt()!=null && old.reviewDueAt().isAfter(currentTime)
                        ? OpportunityOwnerExceptionService.State.COORDINATING : OpportunityOwnerExceptionService.State.ACTIVE;
                return old.state()==desired && old.opportunity().equals(opportunity)
                        && old.responsibility().equals(responsibility)
                        && Objects.equals(old.task(),observation.task())
                        && Objects.equals(old.waitReceipt(),observation.waitReceipt())
                        && old.reasons().equals(observation.reasons());
            }
        }
    }
}
