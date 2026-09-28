package io.github.windyzhu3.ontologylaw.responsibility;
import java.sql.*;
import java.time.Instant;
import java.util.*;
/** Read-only maintenance queries confined to Responsibility-owned rows. */
public interface OpportunityMaintenanceTasks {
    record Position(Instant at,UUID id) {}
    TaskFactory.Task initial(Connection c,UUID tenant,UUID opportunity)throws SQLException;
    boolean initialExists(Connection c,UUID tenant,UUID opportunity)throws SQLException;
    io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject waitProgress(Connection c,UUID tenant,UUID task)throws SQLException;
    TaskFactory.Task predecessor(Connection c,UUID tenant,UUID task)throws SQLException;
    TaskFactory.Task handoffPredecessor(Connection c,UUID tenant,UUID task)throws SQLException;
    List<Position> due(Connection c,UUID tenant,Set<UUID> owners,Instant observed,Position after,int limit)throws SQLException;
    static OpportunityMaintenanceTasks databaseBacked(){return new io.github.windyzhu3.ontologylaw.responsibility.internal.persistence.JooqOpportunityMaintenanceTasks();}
}

