package io.github.windyzhu3.ontologylaw.transfer;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;import java.time.Instant;import java.util.UUID;
/** Metadata only. The caller must authorize and audit every disclosed selector before returning a response. */
public interface TransferWorkflowReader {
 record State(Subject workflow,Subject request,Subject opportunity,UUID contractId,UUID fromOrganization,UUID toOrganization,String stage,String target,UUID owner,UUID taskId,Subject submission,Subject review,Subject intake,Subject classification,UUID matterId,String matterNumber,String category,UUID recipient,Instant dueAt){}
 record Result(Subject selector,UUID opportunity,UUID request,UUID workflow,UUID previousWorkflow,UUID actor,boolean createdInCurrentTransaction){}
 record ProtectedFact(Subject selector,UUID opportunity,byte[] ciphertext,byte[] digest){public ProtectedFact{ciphertext=ciphertext.clone();digest=digest.clone();}public byte[] ciphertext(){return ciphertext.clone();}public byte[] digest(){return digest.clone();}@Override public String toString(){return "TransferProtectedFact[protected]";}}
 record History(Subject selector,String kind,String outcome,Instant occurredAt){}
 java.util.List<History> history(Connection c,UUID tenant,UUID request)throws SQLException;
 record ReturnItem(Subject selector,String requirement){}
 ProtectedFact body(Connection c,UUID tenant,Subject fact)throws SQLException;
 java.util.List<ReturnItem> returnItems(Connection c,UUID tenant,State state)throws SQLException;
 java.util.List<Subject> facts(Connection c,UUID tenant,UUID request)throws SQLException;
 Result result(Connection c,UUID tenant,Subject fact)throws SQLException;
 State workflow(Connection c,UUID tenant,UUID workflow)throws SQLException;
 State forContract(Connection c,UUID tenant,UUID contract)throws SQLException;
 java.util.List<State> forOpportunity(Connection c,UUID tenant,UUID opportunity)throws SQLException;
 State current(Connection c,UUID tenant,UUID request)throws SQLException;
 /** Exact current occurrence only; a completed predecessor never resolves to a new person's task. */
 State forTask(Connection c,UUID tenant,UUID task)throws SQLException;
 /** Last occurrence belonging to this task, including completed or cancelled history. */
 State lastForTask(Connection c,UUID tenant,UUID task)throws SQLException;
 static TransferWorkflowReader databaseBacked(){return new io.github.windyzhu3.ontologylaw.transfer.internal.persistence.JdbcTransferWorkflowReader();}
}
