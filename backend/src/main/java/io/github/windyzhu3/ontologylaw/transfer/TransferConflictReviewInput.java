package io.github.windyzhu3.ontologylaw.transfer;
import java.util.*;
public record TransferConflictReviewInput(String outcome,String explanation,boolean scopeChecked){
 public TransferConflictReviewInput{if(outcome==null||!Set.of("CLEAR","NEED_INFO","BLOCKED").contains(outcome)||explanation==null||explanation.isBlank()||explanation.codePointCount(0,explanation.length())>4000||!scopeChecked)throw new IllegalArgumentException("Confirmed independent review required");explanation=explanation.strip();}
 @Override public String toString(){return "TransferConflictReviewInput[protected]";}
}
