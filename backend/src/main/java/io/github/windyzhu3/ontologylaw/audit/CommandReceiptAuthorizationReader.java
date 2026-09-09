package io.github.windyzhu3.ontologylaw.audit;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;
import java.util.UUID;

/** ADR0013 exception only: caller holds business/identity shared locks and current active identity proof. */
public interface CommandReceiptAuthorizationReader {
    record Original(UUID commandId,String commandType,String outcome,Subject subject,UUID scopeOrganization,ReceiptRecoveryMetadata recovery,Subject resultFact,String rejectionCode) {
        public Original(UUID commandId,String commandType,String outcome,Subject subject,UUID scopeOrganization,ReceiptRecoveryMetadata recovery){this(commandId,commandType,outcome,subject,scopeOrganization,recovery,null,null);}
        @Override public String toString(){return "OriginalCommandAudit[restricted]";}
    }
    final class InvalidMetadata extends RuntimeException {public InvalidMetadata(){super("Receipt recovery unavailable",null,false,false);}}
    Original read(Connection c,Actor actor,UUID commandId) throws SQLException;
    static CommandReceiptAuthorizationReader databaseBacked(){return new io.github.windyzhu3.ontologylaw.audit.internal.persistence.JooqCommandReceiptAuthorizationReader();}
}
