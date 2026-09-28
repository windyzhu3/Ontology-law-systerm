package io.github.windyzhu3.ontologylaw.contract;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;import java.sql.*;import java.util.*;
/** Exact PRE_CONTRACT request, actual complete identity scan and explicit human disposition. */
public interface ContractReviewRepository {
 @FunctionalInterface interface BlockingDecision{Subject block(Connection c,UUID tenant,UUID opportunity,UUID actor,Subject finding,String reason)throws SQLException;}
 @FunctionalInterface interface Completeness{boolean complete(Connection c,UUID tenant,UUID opportunity)throws SQLException;}
 record Preview(boolean scopeComplete,int candidateCount,List<String> permittedOutcomes){public Preview{permittedOutcomes=List.copyOf(permittedOutcomes);}}
 Preview preview(Connection c,UUID tenant,UUID version)throws SQLException;
 Subject request(Connection c,UUID tenant,UUID version,UUID actor,UUID previous,String reason)throws SQLException;
 Subject decide(Connection c,UUID tenant,UUID request,UUID actor,String outcome,String reason)throws SQLException;
 static ContractReviewRepository databaseBacked(ContractProtection protection,ContractPreparationRepository.Codec codec){return databaseBacked(protection,codec,(c,t,o,a,f,r)->{throw new ContractWorkflowService.Blocked("CONTRACT_CONFLICT_DECISION_REQUIRED");});}
 static ContractReviewRepository databaseBacked(ContractProtection protection,ContractPreparationRepository.Codec codec,BlockingDecision decisions){return databaseBacked(protection,codec,decisions,(c,t,o)->false);}
 static ContractReviewRepository databaseBacked(ContractProtection protection,ContractPreparationRepository.Codec codec,BlockingDecision decisions,Completeness complete){return new io.github.windyzhu3.ontologylaw.contract.internal.persistence.JdbcContractReviewRepository(protection,codec,decisions,complete);}
}
