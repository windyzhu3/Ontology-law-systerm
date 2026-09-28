package io.github.windyzhu3.ontologylaw.opportunity;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Opportunity owns attempt facts. Responsibility owns task cancellation and future waiting. */
public interface FollowupAttemptService {
    String FACT="opportunity.followup_attempt";
    record Basis(Subject opportunity,Subject responsibility,Subject task,Subject waitReceipt,Subject quoteWorkflow){}
    record Metadata(Subject selector,Basis basis,UUID actor,UUID nextTask,UUID nextWorkflow,String context,String type,Instant occurredAt,Instant nextCheckAt,Instant createdAt,boolean createdInTransaction){}
    record Task(Subject selector,Subject opportunity,Subject responsibility,UUID owner,String purpose,String state,Subject waitReceipt){}
    record QuoteBasis(Subject selector,String stage,Subject task){}
    interface Ports {
        Task read(Connection c,UUID tenant,UUID task)throws SQLException;
        Task current(Connection c,UUID tenant,UUID task)throws SQLException;
        Subject arrange(Connection c,UUID tenant,Task task,Subject fact,ZoneId zone,Instant due,Instant now)throws SQLException;
        boolean contractTakenOver(Connection c,UUID tenant,UUID opportunity)throws SQLException;
    }
    class Blocked extends RuntimeException {public Blocked(String code){super(code);}public String code(){return getMessage();}}
    Subject record(Connection c,Actor actor,Basis basis,OpportunityFollowupAttempt input,boolean quote,ZoneId zone)throws SQLException;
    Metadata metadata(Connection c,UUID tenant,Subject exact)throws SQLException;
    QuoteBasis quoteBasis(Connection c,UUID tenant,UUID opportunity)throws SQLException;
    List<Metadata> history(Connection c,UUID tenant,UUID opportunity)throws SQLException;
    String summary(Connection c,UUID tenant,Metadata fact)throws SQLException;
    static FollowupAttemptService databaseBacked(OpportunityProgressProtection protection,QuoteDraftService.Codec codec,Ports ports){return new io.github.windyzhu3.ontologylaw.opportunity.internal.persistence.JdbcFollowupAttemptService(protection,codec,ports);}
    static Metadata readMetadata(Connection c,UUID tenant,Subject exact)throws SQLException{return io.github.windyzhu3.ontologylaw.opportunity.internal.persistence.JdbcFollowupAttemptService.readMetadata(c,tenant,exact);}
    static Metadata readRecovery(Connection c,UUID tenant,Subject exact)throws SQLException{return io.github.windyzhu3.ontologylaw.opportunity.internal.persistence.JdbcFollowupAttemptService.readRecovery(c,tenant,exact);}
}
