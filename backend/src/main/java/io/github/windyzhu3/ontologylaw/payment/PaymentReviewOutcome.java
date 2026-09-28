package io.github.windyzhu3.ontologylaw.payment;

/** Confirmed R2 closure rule. Inputs must be exact, locked, verified receipt totals supplied by the Owner.
 * This calculation does not authorize a payment, finish a task, or prove execution conditions. */
public record PaymentReviewOutcome(boolean reviewComplete,boolean resumeExecution,long remainingMinor) {
 private static final long MAX=9007199254740991L;
 public static PaymentReviewOutcome confirmed(boolean prepay,Long requiredMinor,long previousMinor,long receivedMinor){
  if(prepay!=(requiredMinor!=null)||requiredMinor!=null&&(requiredMinor<=0||requiredMinor>MAX)
    ||previousMinor<0||previousMinor>MAX||receivedMinor<=0||receivedMinor>MAX||previousMinor>MAX-receivedMinor)
   throw new IllegalArgumentException("Exact approved gate and verified receipt totals required");
  if(!prepay)return new PaymentReviewOutcome(true,false,0);
  long remaining=Math.max(0,requiredMinor-previousMinor-receivedMinor);
  return new PaymentReviewOutcome(remaining==0,previousMinor<requiredMinor&&remaining==0,remaining);
 }
}
