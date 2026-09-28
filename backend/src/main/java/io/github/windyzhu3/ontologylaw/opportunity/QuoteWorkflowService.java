package io.github.windyzhu3.ontologylaw.opportunity;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;import java.util.*;import java.time.*;
/** Caller authorizes every protected fact before decrypting; writes require one fenced transaction. */
public interface QuoteWorkflowService {
 record Metadata(UUID opportunityId,UUID actorAppointmentId,Instant createdAt,boolean createdInCurrentTransaction){}
 record Task(Subject selector,UUID owner,String type,Subject subject,String state,Instant createdAt,Subject completion){}
 class Blocked extends RuntimeException {private final String code;public Blocked(String code){super(code);this.code=code;}public String code(){return code;}}
 interface Ports {
  boolean contractTakenOver(Connection c,UUID tenant,UUID opportunity)throws SQLException;
  Instant now(Connection c)throws SQLException;
  Task read(Connection c,UUID tenant,UUID id)throws SQLException;
  List<Task> activeForLead(Connection c,UUID tenant,Subject opportunity)throws SQLException;
  Task create(Connection c,UUID tenant,String type,UUID owner,Subject opportunity,ZoneId zone,Instant now)throws SQLException;
  void cancelForQuote(Connection c,UUID tenant,Task task,String reason,Instant now)throws SQLException;
  default Subject waitReceipt(Connection c,UUID tenant,Task task)throws SQLException{throw new UnsupportedOperationException("Termination unavailable in historical fixture");}
  default void cancelForTermination(Connection c,UUID tenant,Task task,Subject fact,Instant now)throws SQLException{throw new UnsupportedOperationException("Termination unavailable in historical fixture");}
  void complete(Connection c,UUID tenant,Task task,Subject fact,Instant now)throws SQLException;
  void waitUntil(Connection c,UUID tenant,Task task,UUID actor,Instant due,Instant now,Subject response)throws SQLException;
  UUID confirmAction(Connection c,UUID tenant,Task task,Map<String,Object> values,UUID actor,Instant now)throws SQLException;
  Map<String,Object> materialBody(Connection c,UUID tenant,UUID upload)throws SQLException;
  List<Subject> materialFacts(Connection c,UUID tenant,OpportunityMaterials.Version version)throws SQLException;
  List<Subject> sourceFacts(Connection c,UUID tenant,UUID opportunity)throws SQLException;
 }
 static io.github.windyzhu3.ontologylaw.identity.AuthorizationService.ReadScope lockedLedgerFacts(Connection c,UUID tenant)throws SQLException{return io.github.windyzhu3.ontologylaw.opportunity.internal.persistence.JdbcQuoteWorkflowService.lockedLedgerFacts(c,tenant);}
 static void prepareLedgerCandidates(Connection c,UUID tenant,List<UUID> ids,int batch)throws SQLException{io.github.windyzhu3.ontologylaw.opportunity.internal.persistence.JdbcQuoteWorkflowService.prepareLedgerCandidates(c,tenant,ids,batch);}
    /** Caller must authorize the exact selector before calling and audit before returning text. */
    static String confirmedReason(Connection c,UUID tenant,UUID opportunity,Subject exact,OpportunityProgressProtection protection,QuoteDraftService.Codec codec)throws SQLException {
        return io.github.windyzhu3.ontologylaw.opportunity.internal.persistence.JdbcQuoteWorkflowHistory.confirmedReason(c,tenant,opportunity,exact,protection,codec);
    }
 static Metadata metadata(Connection c,UUID tenant,Subject exact)throws SQLException{return io.github.windyzhu3.ontologylaw.opportunity.internal.persistence.JdbcQuoteWorkflowMetadata.read(c,tenant,exact);}
 /** Exact accepted quote commercial input; caller must authorize protectedFacts before decryption. */
 Map<String,Object> acceptedPreparation(Connection c,UUID tenant,UUID opportunity)throws SQLException;
 byte[] document(Connection c,Actor actor,UUID opportunity,UUID quoteId)throws SQLException;
 Map<String,Object> context(Connection c,Actor actor,UUID opportunity)throws SQLException;
 Subject execute(Connection c,String action,Actor actor,Map<String,Object> payload)throws SQLException;
 List<Subject> protectedFacts(Connection c,UUID tenant,UUID opportunity)throws SQLException;
 static QuoteWorkflowService databaseBacked(OpportunityProgressProtection protection,QuoteDraftService.Codec codec,Ports ports){return new io.github.windyzhu3.ontologylaw.opportunity.internal.persistence.JdbcQuoteWorkflowService(protection,codec,ports);}
}
