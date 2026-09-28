package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.execution.*;import io.github.windyzhu3.ontologylaw.transfer.*;import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;import java.util.*;
public record R2TransferInput(CommandAuthorizationBinding.Transfers binding,Map<String,Object> values,Object input){
 @SuppressWarnings("unchecked") public static R2TransferInput parse(CommandEnvelope e){try{
  require(e.type().transfers()&&e.actor().onBehalfAppointmentId()==null&&e.taskPrecondition()==null&&e.draftPrecondition()==null&&e.identityPrecondition()==null);var p=object(e.payload(),"opportunityId","expectedOpportunityRevision","expectedWorkflow","values");var w=object(p.get("expectedWorkflow"),"id","revision");require(revision(w.get("revision"))==0);
  var binding=new CommandAuthorizationBinding.Transfers(new Subject("opportunity.opportunity",id(p.get("opportunityId")),revision(p.get("expectedOpportunityRevision")),null),new Subject("transfer.workflow",id(w.get("id")),0L,null));CommandScope.transfers(e,binding);var values=(Map<String,Object>)p.get("values");require(values!=null&&CanonicalJson.encode(values).length()<=500000);
  Object input=switch(e.type()){
   case SUBMIT_TRANSFER,RESUBMIT_TRANSFER->{var v=object(values,"clientIdentity","signatureArchive","explanation","consistencyChecked","corrections");var corrections=new ArrayList<TransferSubmissionInput.Correction>();for(var raw:(List<?>)v.get("corrections")){require(raw instanceof Map<?,?>);var item=(Map<?,?>)raw;require(item.keySet().equals(Set.of("returnItemId","response"))||item.keySet().equals(Set.of("returnItemId","response","material")));corrections.add(new TransferSubmissionInput.Correction(id(item.get("returnItemId")),(String)item.get("response"),item.get("material")==null?null:material(item.get("material"))));}yield new TransferSubmissionInput(material(v.get("clientIdentity")),material(v.get("signatureArchive")),(String)v.get("explanation"),bool(v.get("consistencyChecked")),corrections);}
   case RECORD_TRANSFER_CONFLICT_REVIEW->{var v=object(values,"outcome","explanation","scopeChecked");yield new TransferConflictReviewInput((String)v.get("outcome"),(String)v.get("explanation"),bool(v.get("scopeChecked")));}
   case RECORD_TRANSFER_INTAKE->{var v=object(values,"decision","requirement","explanation","acceptanceChecked");yield new TransferIntakeInput((String)v.get("decision"),(String)v.get("requirement"),(String)v.get("explanation"),bool(v.get("acceptanceChecked")));}
   case CLASSIFY_MATTER->{var v=object(values,"matterId","category","recipient","explanation");id(v.get("matterId"));yield new TransferClassificationInput((String)v.get("category"),id(v.get("recipient")),(String)v.get("explanation"));}
   default->throw new IllegalArgumentException();};return new R2TransferInput(binding,values,input);
 }catch(IllegalArgumentException|ClassCastException|NullPointerException invalid){throw new CommandHandler.Rejected("VALIDATION_FAILED");}}
 private static Map<?,?> object(Object value,String...keys){require(value instanceof Map<?,?>);var m=(Map<?,?>)value;require(m.keySet().equals(Set.of(keys)));return m;}
 private static TransferSubmissionInput.Material material(Object value){var m=object(value,"versionId","sha256");return new TransferSubmissionInput.Material(id(m.get("versionId")),(String)m.get("sha256"));}
 private static UUID id(Object value){String text=(String)value;UUID id=UUID.fromString(text);require(id.toString().equals(text));return id;}
 private static long revision(Object value){require(value instanceof Long||value instanceof Integer);long n=((Number)value).longValue();require(n>=0&&n<=9007199254740991L);return n;}
 private static boolean bool(Object value){require(value instanceof Boolean);return (Boolean)value;}
 private static void require(boolean condition){if(!condition)throw new IllegalArgumentException();}
 @Override public String toString(){return "R2TransferInput[protected]";}
}
