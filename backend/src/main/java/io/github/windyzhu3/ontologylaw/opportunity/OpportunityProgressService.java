package io.github.windyzhu3.ontologylaw.opportunity;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/** Trusted command composition only; caller owns transaction, business fence, receipt and audit. */
public interface OpportunityProgressService {
    record Result(Subject progress,Subject nextTask) {}
    record PendingTask(Subject selector,UUID owner,Subject subject,String purpose,String command,String state) {}
    interface Responsibility {
        PendingTask lockAndRead(Connection c,UUID tenant,UUID task)throws SQLException;
        Subject completeAndSchedule(Connection c,UUID tenant,PendingTask task,Subject progress,ZoneId zone,Instant now,Instant due)throws SQLException;
    }
    final class Blocked extends RuntimeException {
        private final String code;
        public Blocked(String code){super(code);this.code=code;}
        public String code(){return code;}
    }
    Result record(Connection c,Actor actor,Subject opportunity,Subject task,OpportunityProgressInput input,ZoneId zone,Instant recordedAt)throws SQLException;
    static OpportunityProgressService databaseBacked(OpportunityProgressProtection protection,Function<Map<String,Object>,String> canonical,Responsibility responsibility){
        return new io.github.windyzhu3.ontologylaw.opportunity.internal.persistence.JooqOpportunityProgressService(protection,canonical,responsibility);
    }
}
