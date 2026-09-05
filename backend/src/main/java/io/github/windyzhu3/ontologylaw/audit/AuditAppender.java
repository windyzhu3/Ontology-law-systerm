package io.github.windyzhu3.ontologylaw.audit;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationSnapshot;
import java.sql.*;
import java.util.*;

/** Append-only owner port. Writes on the caller's active AUDIT capability connection. */
public interface AuditAppender {
    record Entry(UUID id,UUID commandId,String commandType,UUID correlationId,String result,
            AuthorizationSnapshot authorization,String summary,byte[] summaryDigest) {
        public Entry {summaryDigest=summaryDigest.clone();}
        @Override public byte[] summaryDigest(){return summaryDigest.clone();}
    }
    void append(Connection connection,Entry entry) throws SQLException;
    static AuditAppender databaseBacked(String executionNodeCode){return new io.github.windyzhu3.ontologylaw.audit.internal.persistence.JooqAuditAppender(executionNodeCode);}
}
