package io.github.windyzhu3.ontologylaw.responsibility;

import io.github.windyzhu3.ontologylaw.execution.R1EventFacts;
import java.sql.*;
import java.util.UUID;

public interface EventResponsibilityReader {
    R1EventFacts.Task task(Connection c,UUID tenant,UUID id) throws SQLException;
    R1EventFacts.Decision decision(Connection c,UUID tenant,UUID id) throws SQLException;
    R1EventFacts.Wait latestWait(Connection c,UUID tenant,UUID taskId) throws SQLException;
    static EventResponsibilityReader databaseBacked(){return new io.github.windyzhu3.ontologylaw.responsibility.internal.persistence.JooqEventResponsibilityReader();}
}
