package io.github.windyzhu3.ontologylaw.contract.internal.persistence;
import io.github.windyzhu3.ontologylaw.contract.OpportunityContractReader;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.util.*;
public final class JdbcOpportunityContractReader implements OpportunityContractReader {
    public Negotiation negotiation(Connection c,UUID tenant,UUID opportunity)throws SQLException{
        try(var p=c.prepareStatement("select d.negotiation_disposition_id,d.kind,a.termination_review_assignment_id,a.due_at from contract.negotiation_disposition d left join contract.termination_review_assignment a on a.tenant_id=d.tenant_id and a.request_id=d.negotiation_disposition_id and not exists(select 1 from contract.termination_review_assignment n where n.tenant_id=a.tenant_id and n.previous_assignment_id=a.termination_review_assignment_id) where d.tenant_id=? and d.opportunity_id=? and not exists(select 1 from contract.negotiation_disposition n where n.tenant_id=d.tenant_id and n.previous_disposition_id=d.negotiation_disposition_id)")){
            p.setObject(1,tenant);p.setObject(2,opportunity);try(var r=p.executeQuery()){if(!r.next())return null;return new Negotiation(new Subject("contract.negotiation_disposition",r.getObject(1,UUID.class),0L,null),r.getString(2),r.getObject(3)==null?null:new Subject("contract.termination_review_assignment",r.getObject(3,UUID.class),0L,null),r.getTimestamp(4)==null?null:r.getTimestamp(4).toInstant());}
        }
    }
    public List<Subject> takeoverFacts(Connection c,UUID tenant,UUID opportunity)throws SQLException {
        var facts=new ArrayList<>(forOpportunity(c,tenant,opportunity));
        try(var p=c.prepareStatement("select w.preparation_workflow_id,w.revision from contract.preparation_workflow w where w.tenant_id=? and w.opportunity_id=? and not exists(select 1 from contract.preparation_workflow n where n.tenant_id=w.tenant_id and n.previous_workflow_id=w.preparation_workflow_id)")) {
            p.setObject(1,tenant);p.setObject(2,opportunity);
            try(var r=p.executeQuery()){while(r.next())facts.add(new Subject("contract.preparation_workflow",r.getObject(1,UUID.class),r.getLong(2),null));}
        }
        try(var p=c.prepareStatement("select r.preparation_request_id,r.revision from contract.preparation_request r where r.tenant_id=? and r.opportunity_id=? and not exists(select 1 from contract.preparation_request n where n.tenant_id=r.tenant_id and n.previous_request_id=r.preparation_request_id)")) {
            p.setObject(1,tenant);p.setObject(2,opportunity);
            try(var r=p.executeQuery()){while(r.next())facts.add(new Subject("contract.preparation_request",r.getObject(1,UUID.class),r.getLong(2),null));}
        }
        return List.copyOf(facts);
    }
    public List<Subject> forOpportunity(Connection c,UUID tenant,UUID opportunity)throws SQLException{
        var result=new ArrayList<Subject>();try(var p=c.prepareStatement("select contract_id,revision from contract.contract where tenant_id=? and opportunity_id=? order by contract_id limit 1")){p.setObject(1,tenant);p.setObject(2,opportunity);try(var r=p.executeQuery()){while(r.next())result.add(new Subject("contract.contract",r.getObject(1,UUID.class),r.getLong(2),null));}}return List.copyOf(result);
    }
}
