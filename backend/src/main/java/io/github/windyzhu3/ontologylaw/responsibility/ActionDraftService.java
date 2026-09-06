package io.github.windyzhu3.ontologylaw.responsibility;
import java.sql.*;import java.time.Instant;import java.util.*;

/** Confirmation-only Owner port; save/edit will extend this service in Task4. */
public interface ActionDraftService {
    record Confirmation(UUID draftId,long revision,String digest) {}
    boolean exists(Connection c,UUID tenant,UUID task,UUID draft) throws SQLException;
    void validate(Connection c,UUID tenant,TaskFactory.Task task,Confirmation selector,Map<String,Object> values) throws SQLException;
    void confirm(Connection c,UUID tenant,TaskFactory.Task task,Confirmation selector,Map<String,Object> values,UUID actor,Instant now) throws SQLException;
    static ActionDraftService databaseBacked(){return new io.github.windyzhu3.ontologylaw.responsibility.internal.persistence.JooqActionDraftRepository();}
}
