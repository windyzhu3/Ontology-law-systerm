package io.github.windyzhu3.ontologylaw.responsibility;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Tenant-bound source reads on the caller's transaction; no authorization or presentation aggregation. */
public interface CurrentTaskReader {
    record Task(Subject selector,UUID owner,TaskFactory.Type type,Subject subject,String state,Instant createdAt,
            String slaCode,long slaSeconds,Instant slaDueAt,Subject completion,Subject responsibilityBasis) {
        public Task(Subject selector,UUID owner,TaskFactory.Type type,Subject subject,String state,Instant createdAt,String slaCode,long slaSeconds,Instant slaDueAt,Subject completion){this(selector,owner,type,subject,state,createdAt,slaCode,slaSeconds,slaDueAt,completion,type==TaskFactory.Type.PROGRESS_OPPORTUNITY?subject:null);}
        /** Compatibility alias for the existing Lead projection. */
        public Subject lead(){return subject;}
    }
    record Decision(Subject selector,UUID taskId,Subject subject,String authoritySlot,String contract,int contractVersion,
            String code,String rationale,Instant decidedAt,UUID actorAppointmentId) {
        public Decision(Subject selector,UUID taskId,Subject subject,String authoritySlot,String contract,int contractVersion,String code,String rationale,Instant decidedAt){this(selector,taskId,subject,authoritySlot,contract,contractVersion,code,rationale,decidedAt,null);}
    }
    default String cancellationReason(Connection c,UUID tenant,UUID taskId)throws SQLException{return null;}
    default Subject cancellation(Connection c,UUID tenant,UUID taskId)throws SQLException{return null;}
    Task read(Connection c,UUID tenant,UUID taskId)throws SQLException;
    List<Task> ownedTasks(Connection c,UUID tenant,UUID owner)throws SQLException;
    Decision decision(Connection c,UUID tenant,UUID decisionId)throws SQLException;
    Decision causalSourceRequest(Connection c,UUID tenant,Task task)throws SQLException;
    Decision causalStop(Connection c,UUID tenant,Task task)throws SQLException;
    List<Task> completedContactTasks(Connection c,UUID tenant,UUID leadId)throws SQLException;
    static CurrentTaskReader databaseBacked() { return new io.github.windyzhu3.ontologylaw.responsibility.internal.persistence.JooqCurrentTaskReader(); }
}
