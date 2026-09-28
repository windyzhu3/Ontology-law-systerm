package io.github.windyzhu3.ontologylaw.payment;

import java.time.Instant;
import java.util.UUID;

/** Human receipt input only. Construction neither authorizes finance nor proves money arrived.
 * The named Owner must resolve accepted material, account configuration and exact contract/version. */
public record PaymentReceiptInput(UUID contractId,UUID contractRevisionId,UUID materialVersionId,
 String materialSha256,String transactionReference,long amountMinor,String currency,
 Instant receivedAt,String explanation,boolean attributionChecked) {
 public PaymentReceiptInput {
  if(contractId==null||contractRevisionId==null||materialVersionId==null||materialSha256==null
    ||!materialSha256.matches("[a-f0-9]{64}")||amountMinor<=0||amountMinor>9007199254740991L
    ||!"CNY".equals(currency)||receivedAt==null||!attributionChecked||explanation==null
    ||explanation.isBlank()||explanation.codePointCount(0,explanation.length())>2000)throw invalid();
  transactionReference=normalizeReference(transactionReference);explanation=explanation.strip();
 }
 public void validateAt(Instant databaseNow){if(databaseNow==null||receivedAt.isAfter(databaseNow))throw invalid();}
 static String normalizeReference(String reference){
  if(reference==null)throw invalid();String value=reference.strip();
  if(value.isEmpty()||value.codePointCount(0,value.length())>200||value.codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT||Character.getType(c)==Character.SURROGATE))throw invalid();
  return value;
 }
 private static IllegalArgumentException invalid(){return new IllegalArgumentException("Exact manually verified receipt input required");}
 @Override public String toString(){return "PaymentReceiptInput[restricted]";}
}
