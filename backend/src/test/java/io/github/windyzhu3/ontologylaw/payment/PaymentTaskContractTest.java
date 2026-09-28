package io.github.windyzhu3.ontologylaw.payment;
import io.github.windyzhu3.ontologylaw.responsibility.TaskFactory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PaymentTaskContractTest {
 @Test void finance_and_sales_correction_have_distinct_authorities_and_shared_completion(){
  var finance=TaskFactory.Type.valueOf("CHECK_CONTRACT_RECEIPT");var sales=TaskFactory.Type.valueOf("SUPPLEMENT_CONTRACT_RECEIPT");
  assertTrue(finance.independentDecisionOwner());assertFalse(sales.independentDecisionOwner());
  assertEquals("PAYMENT_CONFIRM",finance.authority);assertEquals("PAYMENT_SUBMIT",sales.authority);
  assertEquals("contract.payment_review",finance.completionType);assertEquals(finance.completionType,sales.completionType);
  assertEquals("opportunity.opportunity",finance.subjectType());assertEquals("R2_BUSINESS_4H_V1",finance.slaCode());
 }
}
