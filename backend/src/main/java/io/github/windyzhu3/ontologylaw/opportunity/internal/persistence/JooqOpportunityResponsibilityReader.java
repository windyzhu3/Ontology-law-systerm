package io.github.windyzhu3.ontologylaw.opportunity.internal.persistence;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationIdentityReader;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import io.github.windyzhu3.ontologylaw.opportunity.OpportunityResponsibilityReader;
import java.sql.*;
import java.util.*;

/** Never chooses an arbitrary latest handoff: the complete bounded chain must resolve exactly. */
public final class JooqOpportunityResponsibilityReader implements OpportunityResponsibilityReader {
    private record Link(Subject selector, Subject prior, UUID from, UUID to) {}
    private record Source(Subject opportunity){}
    public Responsibility current(Connection c,UUID tenant,Subject opportunity)throws SQLException {
        return OpportunityReadRows.fact(c,tenant,new Source(opportunity),()->currentUncached(c,tenant,opportunity));
    }
    private Responsibility currentUncached(Connection c, UUID tenant, Subject opportunity) throws SQLException {
        if (opportunity == null || !"opportunity.opportunity".equals(opportunity.type()) || opportunity.revision() == null)
            throw new IllegalArgumentException("Exact opportunity required");
        UUID frozen; boolean closed;
        try (var p = c.prepareStatement("select revision,owner_appointment_id,closed_at from opportunity.opportunity where tenant_id=? and opportunity_id=?")) {
            p.setObject(1,tenant); p.setObject(2,opportunity.id());
            try (var r=p.executeQuery()) {
                if (!r.next() || r.getLong(1)!=opportunity.revision()) throw invalid();
                frozen=r.getObject(2,UUID.class);closed=r.getObject(3)!=null;
            }
        }
        var identities=AuthorizationIdentityReader.databaseBacked();
        if (identities.registration(c,tenant,frozen)==null) throw invalid();
        Map<Subject,Link> links=new HashMap<>();Long chainRevision=null;
        try (var p=c.prepareStatement("select responsibility_handoff_id,revision,opportunity_revision,prior_basis_type,prior_basis_id,prior_basis_revision,from_appointment_id,to_appointment_id from opportunity.responsibility_handoff where tenant_id=? and opportunity_id=? limit 65")) {
            p.setObject(1,tenant); p.setObject(2,opportunity.id());
            try(var r=p.executeQuery()) { while(r.next()) {
                long revision=r.getLong(3);
                // Closing seals the root at the next revision; it does not rewrite the handoff chain.
                if (revision>opportunity.revision() || revision<0 || links.size()==64) throw invalid();
                chainRevision=chainRevision==null?revision:Math.min(chainRevision,revision);
                var prior=new Subject(r.getString(4),r.getObject(5,UUID.class),r.getLong(6),null);
                var link=new Link(new Subject("opportunity.responsibility_handoff",r.getObject(1,UUID.class),r.getLong(2),null),prior,r.getObject(7,UUID.class),r.getObject(8,UUID.class));
                if (links.put(prior,link)!=null) throw invalid();
            }}
        }
        Subject basis=chainRevision==null?opportunity:new Subject(opportunity.type(),opportunity.id(),chainRevision,null); UUID owner=frozen; int consumed=0;
        Set<Subject> seen=new HashSet<>();
        while(links.containsKey(basis)) {
            if(!seen.add(basis)) throw invalid();
            var link=links.get(basis);
            if(!owner.equals(link.from()) || identities.registration(c,tenant,link.to())==null) throw invalid();
            basis=link.selector(); owner=link.to(); consumed++;
        }
        if(consumed!=links.size()) throw invalid();
        return new Responsibility(basis,owner);
    }
    private static SQLException invalid(){return new SQLException("Opportunity responsibility basis is stale or inconsistent","22000");}
}
