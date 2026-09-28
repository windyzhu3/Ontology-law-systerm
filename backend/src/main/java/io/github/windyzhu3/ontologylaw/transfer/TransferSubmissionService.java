package io.github.windyzhu3.ontologylaw.transfer;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;import java.time.Instant;import java.util.*;

/** Transfer Owner preparation boundary. A request is neither a submitted snapshot nor a case.
 * Invoked only within the audited command transaction; receipt/event composition remains outside. */
public interface TransferSubmissionService {
 record Source(UUID opportunityId,UUID contractId,UUID executionId,Instant activatedAt,String activationDigest){
  public Source{Objects.requireNonNull(opportunityId);Objects.requireNonNull(contractId);Objects.requireNonNull(executionId);Objects.requireNonNull(activatedAt);if(activationDigest==null||!activationDigest.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Exact activation required");}
 }
 record Route(UUID fromOrganizationId,UUID toOrganizationId){
  public Route{Objects.requireNonNull(fromOrganizationId);Objects.requireNonNull(toOrganizationId);if(fromOrganizationId.equals(toOrganizationId))throw new IllegalArgumentException("Distinct transfer organizations required");}
 }
 class Blocked extends RuntimeException{
  private final String code;public Blocked(String code){super(code);this.code=code;}public String code(){return code;}
 }
 interface Ports {
  void lockAuthority(Connection c,Actor actor)throws SQLException;
  void authorizePreparation(Connection c,Actor actor,Subject opportunity)throws SQLException;
  /** Contract Owner re-reads exact current activation while holding its root lock. */
  Source source(Connection c,UUID tenant,UUID contract)throws SQLException;
  /** Trusted, authorized organization routing. Never a caller-provided destination. */
  Route route(Connection c,Actor actor,Subject opportunity)throws SQLException;
 }
 Subject prepare(Connection c,Actor actor,Subject opportunity,UUID contractId)throws SQLException;
 static TransferSubmissionService databaseBacked(Ports ports){return new io.github.windyzhu3.ontologylaw.transfer.internal.persistence.JdbcTransferSubmissionService(ports);}
}
