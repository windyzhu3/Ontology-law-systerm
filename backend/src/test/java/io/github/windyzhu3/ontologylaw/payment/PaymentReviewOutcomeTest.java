package io.github.windyzhu3.ontologylaw.payment;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PaymentReviewOutcomeTest {
 @Test void non_prepay_closes_this_review_without_using_a_conditional_fee_cap(){var r=PaymentReviewOutcome.confirmed(false,null,0,1);assertTrue(r.reviewComplete());assertFalse(r.resumeExecution());assertEquals(0,r.remainingMinor());}
 @Test void partial_prepay_keeps_original_responsibility_until_exact_threshold(){var r=PaymentReviewOutcome.confirmed(true,20000L,0,8000);assertFalse(r.reviewComplete());assertFalse(r.resumeExecution());assertEquals(12000,r.remainingMinor());}
 @Test void threshold_crossing_completes_finance_and_requests_execution_resumption(){var r=PaymentReviewOutcome.confirmed(true,20000L,8000,12000);assertTrue(r.reviewComplete());assertTrue(r.resumeExecution());assertEquals(0,r.remainingMinor());}
 @Test void later_receipts_do_not_reopen_execution(){var r=PaymentReviewOutcome.confirmed(true,20000L,20000,1000);assertTrue(r.reviewComplete());assertFalse(r.resumeExecution());}
 @Test void invalid_gate_and_unsafe_totals_are_rejected(){assertThrows(IllegalArgumentException.class,()->PaymentReviewOutcome.confirmed(false,20000L,0,1));assertThrows(IllegalArgumentException.class,()->PaymentReviewOutcome.confirmed(true,null,0,1));assertThrows(IllegalArgumentException.class,()->PaymentReviewOutcome.confirmed(true,0L,0,1));assertThrows(IllegalArgumentException.class,()->PaymentReviewOutcome.confirmed(false,null,-1,1));assertThrows(IllegalArgumentException.class,()->PaymentReviewOutcome.confirmed(false,null,0,0));assertThrows(IllegalArgumentException.class,()->PaymentReviewOutcome.confirmed(false,null,9007199254740991L,1));}
}
