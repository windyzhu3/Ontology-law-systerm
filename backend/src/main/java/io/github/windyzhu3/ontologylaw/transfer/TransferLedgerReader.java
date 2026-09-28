package io.github.windyzhu3.ontologylaw.transfer;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.time.Instant;
import java.util.*;
/** Internal metadata candidates only. Disclosure requires the caller's current authorization
 * and audit. Detailed case identity and materials are read separately after authorization. */
public interface TransferLedgerReader {
 record Row(Subject request,Subject workflow,UUID opportunityId,UUID contractId,
            String stage,UUID ownerAppointmentId,UUID taskId,Instant dueAt) {}
 List<Row> scan(Connection c,UUID tenant,UUID afterRequest,String stage,int limit)throws SQLException;
 static TransferLedgerReader databaseBacked(){return new io.github.windyzhu3.ontologylaw.transfer.internal.persistence.JdbcTransferLedgerReader();}
}
