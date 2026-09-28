package io.github.windyzhu3.ontologylaw.opportunity;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Opportunity-owned explicit terminal fact. Inspection never decrypts the protected explanation. */
public interface OpportunityClosureService {
    record ActiveTask(Subject selector,Subject basis,UUID owner,Subject waitReceipt,String state) {}
    interface Tasks {
        ActiveTask active(Connection c,UUID tenant,Subject opportunity)throws SQLException;
        void cancel(Connection c,UUID tenant,Subject opportunity,Subject basis,UUID owner,Subject task,Subject wait,Subject closure,Instant at)throws SQLException;
    }
    @FunctionalInterface interface Downstream {List<Subject> read(Connection c,UUID tenant,UUID opportunity)throws SQLException;}
    final class Blocked extends RuntimeException {
        private final String code;
        public Blocked(String code){super("Opportunity closure blocked");this.code=code;}
        public String code(){return code;}
    }
    record Closure(Subject selector,Subject opportunity,Subject responsibility,Subject task,Subject waitReceipt,UUID actor,String reasonCode,Instant closedAt) {}
    record Snapshot(Subject opportunity,OpportunityResponsibilityReader.Responsibility responsibility,Subject task,Subject waitReceipt,String taskState,boolean closed,List<Subject> downstreamFacts,Closure closure) {
        public Snapshot { downstreamFacts=List.copyOf(downstreamFacts); }
        public boolean downstream(){return !downstreamFacts.isEmpty();}
    }
    record Input(Subject opportunity,Subject responsibility,Subject task,Subject waitReceipt,UUID actor,String reasonCode,String summary) {
        @Override public String toString(){return "OpportunityClosureInput[protected]";}
    }
    Snapshot inspect(Connection c,UUID tenant,UUID opportunity)throws SQLException;
    Closure read(Connection c,UUID tenant,Subject closure)throws SQLException;
    String summary(Connection c,UUID tenant,Subject closure)throws SQLException;
    Closure close(Connection c,UUID tenant,Input input)throws SQLException;
    /** Exact immutable metadata for receipt authorization; no key or protected-body capability required. */
    static Closure metadata(Connection c,UUID tenant,Subject closure)throws SQLException{return io.github.windyzhu3.ontologylaw.opportunity.internal.persistence.JooqOpportunityClosureService.readMetadata(c,tenant,closure);}
    static Closure currentMetadata(Connection c,UUID tenant,UUID opportunity)throws SQLException{return io.github.windyzhu3.ontologylaw.opportunity.internal.persistence.JooqOpportunityClosureService.currentMetadata(c,tenant,opportunity);}
    static OpportunityClosureService databaseBacked(OpportunityProgressProtection protection,Tasks tasks,Downstream downstream){return new io.github.windyzhu3.ontologylaw.opportunity.internal.persistence.JooqOpportunityClosureService(protection,tasks,downstream);}
}
