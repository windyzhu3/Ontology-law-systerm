package io.github.windyzhu3.ontologylaw.transfer;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;import java.util.*;
/** Exact PRE_TRANSFER inspection. Caller owns authorization and the tenant business fence. */
public interface TransferConflictReviewRepository {
 @FunctionalInterface interface Codec{String encode(Object value);}
 @FunctionalInterface interface Protection{byte[] seal(UUID tenant,UUID opportunity,UUID fact,String clear);}
 @FunctionalInterface interface BlockingDecision{Subject block(Connection c,UUID tenant,UUID opportunity,UUID actor,Subject finding,String reason)throws SQLException;}
 record Candidate(ScopeParty scope,ScopeParty matched){}
 @FunctionalInterface interface Completeness {boolean complete(Connection c,UUID tenant,UUID opportunity)throws SQLException;}
 record ScopeParty(UUID sourceId,String sourceType,UUID partyId,long revision,String role,String digest){

 }
 record Scan(UUID submissionId,UUID opportunityId,String submissionDigest,String legalNeedDigest,List<ScopeParty> scope,List<ScopeParty> corpus,List<Candidate> candidates,boolean scopeComplete,String scopeDigest,String corpusDigest){
  public Scan{scope=List.copyOf(scope);corpus=List.copyOf(corpus);candidates=List.copyOf(candidates);}
  public List<String> permittedOutcomes(){var result=new ArrayList<String>();result.add("NEED_INFO");if(scopeComplete&&candidates.isEmpty())result.add("CLEAR");if(!candidates.isEmpty())result.add("BLOCKED");return List.copyOf(result);}
  @Override public String toString(){return "TransferReviewScan[protected]";}
 }
 default io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject record(Connection c,UUID tenant,UUID workflow,UUID actor,TransferConflictReviewInput input,TransferWorkflowService.Draft draft)throws SQLException{throw new UnsupportedOperationException("Review write composition required");}
 static TransferConflictReviewRepository databaseBacked(Completeness completeness,Codec codec,Protection protection,BlockingDecision blocking){return new io.github.windyzhu3.ontologylaw.transfer.internal.persistence.JdbcTransferConflictReviewRepository(completeness,codec,protection,blocking);}
 Scan inspect(Connection c,UUID tenant,UUID submission)throws SQLException;
 static TransferConflictReviewRepository databaseBacked(Completeness completeness,Codec codec){return new io.github.windyzhu3.ontologylaw.transfer.internal.persistence.JdbcTransferConflictReviewRepository(completeness,codec);}
}
