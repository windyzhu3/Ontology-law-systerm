package io.github.windyzhu3.ontologylaw.opportunity;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;import java.time.Instant;import java.util.*;
/** Unauthorised metadata returned by each business Owner, never a client projection. */
public interface OpportunityTeamExceptionReader {
 static OpportunityTeamExceptionReader databaseBacked(){return new io.github.windyzhu3.ontologylaw.opportunity.internal.persistence.JdbcOpportunityTeamExceptionReader();}
 record Row(Subject selector,UUID opportunity,UUID owner,UUID originalTask,Instant observedAt,
            Instant dueAt,String kind,String state,String resumeStage,UUID organization,List<Subject> sources){
  public Row{sources=List.copyOf(sources);}
 }
 List<Row> scan(Connection c,UUID tenant,UUID after,int limit)throws SQLException;
 Row current(Connection c,UUID tenant,UUID id)throws SQLException;
}
