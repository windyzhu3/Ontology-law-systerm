package io.github.windyzhu3.ontologylaw.transfer;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;import java.util.UUID;
/** Public Owner entry point; persistence remains behind the transfer boundary. */
public final class TransferConflictReviewService {
 private final TransferConflictReviewRepository repository;
 private TransferConflictReviewService(TransferConflictReviewRepository repository){this.repository=repository;}
 public static TransferConflictReviewService databaseBacked(TransferConflictReviewRepository.Completeness completeness,TransferConflictReviewRepository.Codec codec,TransferConflictReviewRepository.Protection protection,TransferConflictReviewRepository.BlockingDecision decision){return new TransferConflictReviewService(TransferConflictReviewRepository.databaseBacked(completeness,codec,protection,decision));}
 public TransferConflictReviewRepository.Scan inspect(Connection c,UUID tenant,UUID submission)throws SQLException{return repository.inspect(c,tenant,submission);}
 public Subject record(Connection c,UUID tenant,UUID workflow,UUID actor,TransferConflictReviewInput input,TransferWorkflowService.Draft draft)throws SQLException{return repository.record(c,tenant,workflow,actor,input,draft);}
}
