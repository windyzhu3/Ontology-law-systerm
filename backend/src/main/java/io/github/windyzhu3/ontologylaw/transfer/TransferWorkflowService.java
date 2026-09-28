package io.github.windyzhu3.ontologylaw.transfer;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;import java.time.*;import java.util.*;

/** Transfer responsibility Owner. Runtime supplies the fenced, audited transaction. */
public interface TransferWorkflowService {
 record Task(Subject selector,UUID owner,String state){}
 record Basis(UUID revisionId,UUID confirmationId,String contractDigest,String legalNeedDigest){
  public Basis{Objects.requireNonNull(revisionId);Objects.requireNonNull(confirmationId);digest(contractDigest);digest(legalNeedDigest);}
 }
 record Draft(UUID id,String digest){public Draft{Objects.requireNonNull(id);TransferWorkflowService.digest(digest);}}
 static void digest(String value){if(value==null||!value.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Exact digest required");}
 class Blocked extends RuntimeException{private final String code;public Blocked(String code){super(code);this.code=code;}public String code(){return code;}}
 interface Ports {
  void lockAuthority(Connection c,Actor actor)throws SQLException;
  void authorize(Connection c,Actor actor,Subject opportunity,String authority,List<Subject> facts)throws SQLException;
  Instant now(Connection c)throws SQLException;
  Instant due(Instant trigger);
  UUID owner(Connection c,Actor actor,Subject opportunity,UUID request,String stage,UUID submitter,UUID incumbent)throws SQLException;
  Task task(Connection c,UUID tenant,UUID id)throws SQLException;
  Task create(Connection c,UUID tenant,Subject opportunity,UUID owner,String stage,Instant now,Instant due)throws SQLException;
  void complete(Connection c,UUID tenant,Task task,Subject result,Instant now)throws SQLException;
  void cancel(Connection c,UUID tenant,Task task,Instant now)throws SQLException;
  /** Revalidates accepted material hashes, current customer/parties and exact executed contract. */
  Basis validate(Connection c,Actor actor,Subject opportunity,UUID contract,TransferSubmissionInput input)throws SQLException;
  default void validateCorrectionMaterial(Connection c,Actor actor,Subject opportunity,TransferSubmissionInput.Material material)throws SQLException{throw new UnsupportedOperationException("Exact correction material validation required");}
  Draft confirmDraft(Connection c,Actor actor,Task task,String submissionDigest,Instant now)throws SQLException;
  default Subject review(Connection c,Actor actor,Subject workflow,TransferConflictReviewInput input,Draft draft)throws SQLException{throw new UnsupportedOperationException("Independent review composition required");}
  default void validateAcceptance(Connection c,Actor actor,Subject workflow)throws SQLException{throw new UnsupportedOperationException("Current exact intake basis required");}
  default Subject intakeDecision(Connection c,Actor actor,Task task,Subject snapshot,TransferIntakeInput input,Instant now)throws SQLException{throw new UnsupportedOperationException("Responsibility intake decision required");}
  default void validateRecipient(Connection c,Actor actor,Subject opportunity,UUID request,TransferClassificationInput input)throws SQLException{throw new UnsupportedOperationException("Qualified receiving appointment required");}
  String encode(Map<String,Object> value);
  byte[] seal(UUID tenant,UUID opportunity,UUID fact,String clear);
 }
 Subject start(Connection c,Actor actor,Subject request)throws SQLException;
 Subject submit(Connection c,Actor actor,Subject workflow,TransferSubmissionInput input)throws SQLException;
 Subject review(Connection c,Actor actor,Subject workflow,TransferConflictReviewInput input)throws SQLException;
 Subject intake(Connection c,Actor actor,Subject workflow,TransferIntakeInput input)throws SQLException;
 Subject classify(Connection c,Actor actor,Subject workflow,TransferClassificationInput input)throws SQLException;
 /** Metadata-only discovery; the recovery command revalidates under locks before writing. */
 boolean recoveryNeeded(Connection c,Actor actor,Subject workflow)throws SQLException;
 Subject recover(Connection c,Actor actor,Subject workflow)throws SQLException;
 static TransferWorkflowService databaseBacked(Ports ports){return new io.github.windyzhu3.ontologylaw.transfer.internal.persistence.JdbcTransferWorkflowService(ports);}
}
