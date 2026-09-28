package io.github.windyzhu3.ontologylaw.transfer;
import java.util.*;
public record TransferClassificationInput(String category,UUID recipient,String explanation){
 public TransferClassificationInput{if(category==null||!Set.of("GENERAL","ENFORCEMENT","OTHER").contains(category)||recipient==null||explanation==null||explanation.isBlank()||explanation.codePointCount(0,explanation.length())>4000)throw new IllegalArgumentException("Confirmed case category and recipient required");explanation=explanation.strip();}
 @Override public String toString(){return "TransferClassificationInput[protected]";}
}
