package io.github.windyzhu3.ontologylaw.transfer;
import java.util.*;
public record TransferIntakeInput(String decision,String requirement,String explanation,boolean acceptanceChecked){
 public TransferIntakeInput{
  if(decision==null||!Set.of("ACCEPT","RETURN").contains(decision)||explanation==null||explanation.isBlank()||explanation.codePointCount(0,explanation.length())>4000)throw new IllegalArgumentException("Exact intake decision required");
  if(decision.equals("ACCEPT")&&(!acceptanceChecked||requirement!=null)||decision.equals("RETURN")&&(acceptanceChecked||requirement==null||!Set.of("CLIENT_IDENTITY","SIGNATURE_ARCHIVE","HANDOVER_EXPLANATION","OTHER_MATERIAL").contains(requirement)))throw new IllegalArgumentException("Exact intake confirmation or return requirement required");explanation=explanation.strip();
 }
 @Override public String toString(){return "TransferIntakeInput[protected]";}
}
