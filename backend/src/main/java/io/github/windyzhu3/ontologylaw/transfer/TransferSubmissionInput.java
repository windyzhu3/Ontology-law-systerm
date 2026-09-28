package io.github.windyzhu3.ontologylaw.transfer;
import java.util.*;
/** Human-confirmed submission input, never an authorization or transfer-readiness proof. */
public record TransferSubmissionInput(Material clientIdentity,Material signatureArchive,String explanation,boolean consistencyChecked,List<Correction> corrections) {
 public record Material(UUID versionId,String sha256){public Material{if(versionId==null||sha256==null||!sha256.matches("[a-f0-9]{64}"))throw invalid();}}
 public record Correction(UUID returnItemId,String response,Material material){public Correction(UUID returnItemId,String response){this(returnItemId,response,null);}public Correction{if(returnItemId==null)throw invalid();response=text(response);} @Override public String toString(){return "TransferCorrection[protected]";}}
 public TransferSubmissionInput{
  if(clientIdentity==null||signatureArchive==null||!consistencyChecked||corrections==null||corrections.size()>100)throw invalid();explanation=text(explanation);corrections=List.copyOf(corrections);
  if(corrections.stream().map(Correction::returnItemId).distinct().count()!=corrections.size())throw invalid();
 }
 /** The Owner supplies the exact persisted return-item set; the client cannot declare completeness. */
 public void requireReturnItems(Set<UUID> expected){if(expected==null||!new HashSet<>(corrections.stream().map(Correction::returnItemId).toList()).equals(expected))throw invalid();}
 private static String text(String value){if(value==null||value.isBlank()||value.codePointCount(0,value.length())>4000)throw invalid();return value.strip();}
 private static IllegalArgumentException invalid(){return new IllegalArgumentException("Exact confirmed transfer input required");}
 @Override public String toString(){return "TransferSubmissionInput[protected]";}
}
