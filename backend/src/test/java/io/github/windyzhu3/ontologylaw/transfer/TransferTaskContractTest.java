package io.github.windyzhu3.ontologylaw.transfer;
import io.github.windyzhu3.ontologylaw.responsibility.TaskFactory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TransferTaskContractTest {
 @Test void independent_review_follows_actual_sales_submission(){
  var sales=TaskFactory.Type.valueOf("PREPARE_TRANSFER");var review=TaskFactory.Type.valueOf("REVIEW_TRANSFER");
  assertEquals("SUBMIT_TRANSFER",sales.command);assertEquals("transfer.submission",sales.completionType);
  assertEquals("TRANSFER_SUBMIT",sales.authority);assertFalse(sales.independentDecisionOwner());
  assertTrue(review.independentDecisionOwner());assertEquals("TRANSFER_REVIEW",review.authority);
  assertEquals("opportunity.opportunity",sales.subjectType());assertEquals("R2_BUSINESS_4H_V1",sales.slaCode());
  assertFalse(sales.isContract());assertTrue(sales.isTransfer());
 }

 @Test void downstream_transfer_tasks_keep_explicit_public_fact_types(){
  assertEquals("TRANSFER_SUBMISSION",TaskFactory.Type.SUPPLEMENT_TRANSFER.publicCompletionType());
  assertEquals("TRANSFER_INTAKE",TaskFactory.Type.ACCEPT_TRANSFER.publicCompletionType());
  assertEquals("MATTER_CLASSIFICATION",TaskFactory.Type.CLASSIFY_MATTER.publicCompletionType());
 }
}
