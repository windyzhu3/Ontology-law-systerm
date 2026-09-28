package io.github.windyzhu3.ontologylaw.responsibility;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;

/** Narrow Responsibility-owned terminal capability; caller holds the Opportunity root lock. */
public interface OpportunityTaskClosure {
    record Active(CurrentTaskReader.Task task,Subject waitReceipt) {}
    Active active(Connection c,UUID tenant,Subject opportunity)throws SQLException;
    void cancel(Connection c,UUID tenant,Subject opportunity,Subject basis,UUID owner,Subject task,Subject waitReceipt,Subject closure,Instant at)throws SQLException;
    static OpportunityTaskClosure databaseBacked(){return new io.github.windyzhu3.ontologylaw.responsibility.internal.persistence.JooqOpportunityTaskClosure();}
}
