package io.github.windyzhu3.ontologylaw.transfer.internal.persistence;
import io.github.windyzhu3.ontologylaw.transfer.OpportunityTransferReader;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.util.*;
public final class JdbcOpportunityTransferReader implements OpportunityTransferReader {
    public List<Subject> forOpportunity(Connection c,UUID tenant,UUID opportunity)throws SQLException{
        var result=new ArrayList<Subject>();try(var p=c.prepareStatement("select transfer_request_id,revision from transfer.transfer_request where tenant_id=? and opportunity_id=? order by transfer_request_id limit 1")){p.setObject(1,tenant);p.setObject(2,opportunity);try(var r=p.executeQuery()){while(r.next())result.add(new Subject("transfer.transfer_request",r.getObject(1,UUID.class),r.getLong(2),null));}}return List.copyOf(result);
    }
    public List<Subject> reviewFactsForOpportunity(Connection c,UUID tenant,UUID opportunity)throws SQLException{
        var result=new ArrayList<Subject>();
        try(var p=c.prepareStatement("select transfer_request_id,revision from transfer.transfer_request where tenant_id=? and opportunity_id=? order by transfer_request_id limit 1001")){
            p.setObject(1,tenant);p.setObject(2,opportunity);
            try(var r=p.executeQuery()){
                while(r.next()){
                    if(result.size()==1000)throw new SQLException("Transfer review basis exceeds safe read bound", "54000");
                    result.add(new Subject("transfer.transfer_request",r.getObject(1,UUID.class),r.getLong(2),null));
                }
            }
        }
        return List.copyOf(result);
    }
}
