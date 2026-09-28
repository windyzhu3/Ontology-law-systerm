package io.github.windyzhu3.ontologylaw.opportunity.internal.persistence;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import java.sql.*;
import java.time.*;
import java.util.*;
public final class JooqOpportunityOwnerExceptionReader implements OpportunityOwnerExceptionReader {
    public List<Position> scan(Connection c,UUID tenant,Instant observed,Position after,int limit)throws SQLException {
        if(limit<1||limit>100)throw new IllegalArgumentException("Invalid page bound");
        String sql="select first_observed_at,owner_exception_id from opportunity.owner_exception where tenant_id=? and is_current and first_observed_at<=?"+(after==null?"":" and (first_observed_at,owner_exception_id)>(?,?)")+" order by first_observed_at,owner_exception_id limit ?";
        try(var p=c.prepareStatement(sql)){int n=1;p.setObject(n++,tenant);p.setObject(n++,OffsetDateTime.ofInstant(observed,ZoneOffset.UTC));if(after!=null){p.setObject(n++,OffsetDateTime.ofInstant(after.at(),ZoneOffset.UTC));p.setObject(n++,after.id());}p.setInt(n,limit);var rows=new ArrayList<Position>();try(var rs=p.executeQuery()){while(rs.next())rows.add(new Position(rs.getObject(1,OffsetDateTime.class).toInstant(),rs.getObject(2,UUID.class)));}return List.copyOf(rows);}
    }
    public OpportunityOwnerExceptionService.Snapshot current(Connection c,UUID tenant,UUID id)throws SQLException {
        try(var p=c.prepareStatement("select * from opportunity.owner_exception where tenant_id=? and owner_exception_id=? and is_current")){p.setObject(1,tenant);p.setObject(2,id);try(var rs=p.executeQuery()){return rs.next()?JooqOpportunityOwnerExceptionService.snapshot(rs):null;}}
    }
}
