package io.github.windyzhu3.ontologylaw.responsibility;
import java.sql.*;
import java.util.*;
/** Tenant-bound, bounded responsibility metadata. Authorization belongs to the caller. */
public interface TeamResponsibilityReader {
 enum View {TASKS,WAITING,HISTORY}
 List<CurrentTaskReader.Task> scan(Connection c,UUID tenant,View view,UUID after,int limit)throws SQLException;
 List<CurrentTaskReader.Task> currentForLead(Connection c,UUID tenant,UUID lead)throws SQLException;
 CurrentTaskReader.Task lastDecisionForLead(Connection c,UUID tenant,UUID lead)throws SQLException;
 static TeamResponsibilityReader databaseBacked(){return new io.github.windyzhu3.ontologylaw.responsibility.internal.persistence.JooqTeamResponsibilityReader();}
}
