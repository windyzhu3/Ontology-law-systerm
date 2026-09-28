package io.github.windyzhu3.ontologylaw.payment;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Internal metadata candidates, not an authorized response. Callers must authorize and
 * audit the exact facts before disclosure. List rows contain no protected body;
 * review returns encrypted source bytes for the composition to project after authorization. */
public interface PaymentLedgerReader {
 record Row(Subject request,Subject workflow,UUID opportunityId,UUID contractRevisionId,
            String stage,UUID ownerAppointmentId,UUID taskId,Instant dueAt) {}
 /** Keyset is the immutable request id, never the changing workflow occurrence. */
 List<Row> scan(Connection connection,UUID tenant,UUID afterRequest,String stage,int limit)throws SQLException;
 record Receipt(Subject selector,long amountMinor,String currency,String kind,boolean thisRequest,Instant confirmedAt){}
 record Review(Subject selector,String outcome,Instant occurredAt){}
 record Detail(Row row,Subject version,boolean prepay,Long requiredMinor,List<Receipt> receipts,List<Review> history,List<Subject> facts){}
 Detail detail(Connection c,UUID tenant,UUID request)throws SQLException;
 /** Exact immutable review body, for authorized reason projection only. */
 record ProtectedReview(Subject selector,UUID opportunity,UUID actor,Instant occurredAt,String outcome,byte[] ciphertext,byte[] digest){
  public ProtectedReview{ciphertext=ciphertext.clone();digest=digest.clone();}
  @Override public byte[] ciphertext(){return ciphertext.clone();}
  @Override public byte[] digest(){return digest.clone();}
 }
 ProtectedReview review(Connection c,UUID tenant,Subject exact)throws SQLException;
 static PaymentLedgerReader databaseBacked(){return new io.github.windyzhu3.ontologylaw.payment.internal.persistence.JdbcPaymentLedgerReader();}
}
