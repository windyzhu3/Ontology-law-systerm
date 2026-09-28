package io.github.windyzhu3.ontologylaw.responsibility;
import java.sql.*;
import java.time.Instant;
import java.util.*;
/** Original SLA is retained during waiting. Due state alone is not a continuation exception. */
public interface ResponsibilityOverviewReader {
 List<CurrentTaskReader.Task> overdue(Connection c,UUID tenant,Instant observed,UUID after,int limit)throws SQLException;
 static ResponsibilityOverviewReader databaseBacked(){return new io.github.windyzhu3.ontologylaw.responsibility.internal.persistence.JooqResponsibilityOverviewReader();}
}
