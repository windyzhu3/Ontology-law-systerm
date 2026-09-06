package io.github.windyzhu3.ontologylaw.responsibility;
import java.sql.*;import java.time.Instant;import java.util.*;

/** Responsibility Owner port. Caller supplies the fenced command transaction and canonical validated values. */
public interface ActionDraftService {
    record Confirmation(UUID draftId,long revision,String digest) {}
    record Draft(io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject selector,UUID taskId,
            String actionCode,String schemaCode,int schemaVersion,Map<String,Object> values,String digest,
            String state,Instant createdAt,Instant updatedAt) {
        public Draft { values=Collections.unmodifiableMap(new TreeMap<>(values)); }
    }
    record Saved(Draft draft,boolean changed) {}
    Draft read(Connection c,UUID tenant,UUID taskId)throws SQLException;
    Saved save(Connection c,UUID tenant,TaskFactory.Task task,Draft expected,Map<String,Object> values,UUID actor,Instant now)throws SQLException;
    boolean exists(Connection c,UUID tenant,UUID task,UUID draft) throws SQLException;
    void validate(Connection c,UUID tenant,TaskFactory.Task task,Confirmation selector,Map<String,Object> values) throws SQLException;
    void confirm(Connection c,UUID tenant,TaskFactory.Task task,Confirmation selector,Map<String,Object> values,UUID actor,Instant now) throws SQLException;
    static ActionDraftService databaseBacked(){return new io.github.windyzhu3.ontologylaw.responsibility.internal.persistence.JooqActionDraftRepository();}
}
