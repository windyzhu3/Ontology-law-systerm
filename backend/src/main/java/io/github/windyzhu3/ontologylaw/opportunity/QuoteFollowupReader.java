package io.github.windyzhu3.ontologylaw.opportunity;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;

/** Exact immutable quote reply lineage used by the existing due-task recovery lane. */
public interface QuoteFollowupReader {
    record Source(Subject response,UUID opportunityId,UUID owner,UUID priorTaskId,Subject basis,Instant due) {}
    Source read(Connection c,UUID tenant,UUID task) throws SQLException;
    static QuoteFollowupReader databaseBacked(){return new io.github.windyzhu3.ontologylaw.opportunity.internal.persistence.JdbcQuoteFollowupReader();}
}
