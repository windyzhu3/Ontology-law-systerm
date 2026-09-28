package io.github.windyzhu3.ontologylaw.responsibility;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;

/** Metadata only. The caller must authorize this exact source before reading confirmed values. */
public interface TaskConfirmationReader {
 record Confirmation(Subject selector,UUID taskId,String actionCode,String schemaCode,int schemaVersion,Instant confirmedAt,String digest) {}
 Confirmation forTask(Connection c,UUID tenant,UUID task)throws SQLException;
 static TaskConfirmationReader databaseBacked(){return new io.github.windyzhu3.ontologylaw.responsibility.internal.persistence.JooqTaskConfirmationReader();}
}
