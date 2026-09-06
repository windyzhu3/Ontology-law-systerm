package io.github.windyzhu3.ontologylaw.responsibility;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Tenant-bound source reads on the caller's transaction; no authorization or presentation aggregation. */
public interface CurrentTaskReader {
    record Task(Subject selector,UUID owner,TaskFactory.Type type,Subject lead,String state,Instant createdAt,
            String slaCode,long slaSeconds,Instant slaDueAt,Subject completion) {}
    record Decision(Subject selector,UUID taskId,Subject subject,String authoritySlot,String contract,int contractVersion,
            String code,String rationale,Instant decidedAt) {}
    Task read(Connection c,UUID tenant,UUID taskId)throws SQLException;
    List<Task> ownedTasks(Connection c,UUID tenant,UUID owner)throws SQLException;
    Decision decision(Connection c,UUID tenant,UUID decisionId)throws SQLException;
    static CurrentTaskReader databaseBacked() { return new io.github.windyzhu3.ontologylaw.responsibility.internal.persistence.JooqCurrentTaskReader(); }
}
