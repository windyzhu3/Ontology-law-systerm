package io.github.windyzhu3.ontologylaw.api;
import java.util.*;
import tools.jackson.databind.JsonNode;
/** T05 original-byte shape checks preserve required explicit nulls before DTO binding. */
final class R2CustomerRequirementsJson {
 private R2CustomerRequirementsJson(){}
 static void install(tools.jackson.databind.module.SimpleModule module){
  module.setMixInAnnotation(io.github.windyzhu3.ontologylaw.api.adapter.generated.model.OpportunityCustomerSourceV1.class,AbsentSourceFields.class);
  module.setMixInAnnotation(io.github.windyzhu3.ontologylaw.api.adapter.generated.model.OpportunityCustomerContextV1.class,AbsentContextFields.class);
  module.addDeserializer(io.github.windyzhu3.ontologylaw.api.adapter.generated.model.OpportunityCustomerCommandReceiptV1ResultFact.class,new tools.jackson.databind.ValueDeserializer<io.github.windyzhu3.ontologylaw.api.adapter.generated.model.OpportunityCustomerCommandReceiptV1ResultFact>(){
   public io.github.windyzhu3.ontologylaw.api.adapter.generated.model.OpportunityCustomerCommandReceiptV1ResultFact deserialize(tools.jackson.core.JsonParser p,tools.jackson.databind.DeserializationContext c){var tree=c.readTree(p);return switch(tree.path("factType").asString()){
    case "OPPORTUNITY_CUSTOMER_DRAFT"->c.readTreeAsValue(tree,io.github.windyzhu3.ontologylaw.api.adapter.generated.model.OpportunityCustomerDraftFactRefV1.class);
    case "OPPORTUNITY_CUSTOMER_CONFIRMATION"->c.readTreeAsValue(tree,io.github.windyzhu3.ontologylaw.api.adapter.generated.model.OpportunityCustomerConfirmationFactRefV1.class);
    default->throw new IllegalArgumentException("Unexpected customer result fact");};}
  });
 }
 @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
 private abstract static class AbsentSourceFields{}
 private abstract static class AbsentContextFields{
  @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) public abstract io.github.windyzhu3.ontologylaw.api.adapter.generated.model.OpportunityCustomerVersionV1 getDraft();
  @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) public abstract io.github.windyzhu3.ontologylaw.api.adapter.generated.model.OpportunityCustomerVersionV1 getConfirmation();
  @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) public abstract String getCurrentOwnerLabel();
 }

 static void validate(JsonNode tree,boolean draft){
  var required=new HashSet<>(Set.of("expectedOpportunityRevision","responsibilityBasis","expectedDraft","expectedConfirmation"));if(draft)required.add("document");shape(tree,"",required,required);
  selector(tree.get("responsibilityBasis"),"/responsibilityBasis",false);selector(tree.get("expectedDraft"),"/expectedDraft",draft);selector(tree.get("expectedConfirmation"),"/expectedConfirmation",true);
  if(draft){var doc=tree.get("document");var fields=Set.of("participants","unknownOpponent","matterName","customerGoal","serviceScope","knownConstraints","contactName","contactPhone");var allowed=new HashSet<>(fields);allowed.add("unverifiedOpponentName");shape(doc,"/document",fields,allowed);var rows=doc.get("participants");if(!rows.isArray())fail("/document/participants","INVALID_FORMAT");for(int i=0;i<rows.size();i++){String at="/document/participants/"+i;var row=rows.get(i);var names=Set.of("role","party","newParty","profileChange");shape(row,at,names,names);selector(row.get("party"),at+"/party",true);if(!row.get("newParty").isNull()){var f=Set.of("kind","name","distinctIdentityConfirmed");shape(row.get("newParty"),at+"/newParty",f,f);}if(!row.get("profileChange").isNull()){var f=Set.of("name","sharedProfileImpactConfirmed");shape(row.get("profileChange"),at+"/profileChange",f,f);}}}
  tree(tree,"",0,draft);
 }
 private static void selector(JsonNode n,String at,boolean nullable){if(n.isNull()){if(!nullable)fail(at,"NOT_ALLOWED");return;}var f=Set.of("id","revision");shape(n,at,f,f);}
 private static void shape(JsonNode n,String at,Set<String> required,Set<String> allowed){if(n==null||!n.isObject())fail(at,"INVALID_FORMAT");for(var key:required)if(!n.has(key))fail(at+"/"+key,"CONDITION_FAILED");for(var field:n.properties())if(!allowed.contains(field.getKey()))fail(at+"/"+field.getKey(),"NOT_ALLOWED");}
 private static void tree(JsonNode n,String at,int depth,boolean draft){if(depth>64)fail(at,"OUT_OF_RANGE");if(n.isNull()){boolean allowed=at.equals("/expectedConfirmation")||draft&&at.equals("/expectedDraft")||draft&&at.equals("/document/unverifiedOpponentName")||draft&&at.matches("/document/participants/[0-9]+/(party|newParty|profileChange)");if(!allowed)fail(at,"NOT_ALLOWED");return;}if(n.isFloatingPointNumber())fail(at,"INVALID_FORMAT");if(n.isIntegralNumber()&&(!n.canConvertToLong()||n.longValue() < -9007199254740991L||n.longValue()>9007199254740991L))fail(at,"OUT_OF_RANGE");if(n.isObject())for(var f:n.properties())tree(f.getValue(),at+"/"+f.getKey().replace("~","~0").replace("/","~1"),depth+1,draft);if(n.isArray())for(int i=0;i<n.size();i++)tree(n.get(i),at+"/"+i,depth+1,draft);}
 private static void fail(String at,String code){throw R1HttpFailure.validation(at.isEmpty()?"/":at,code);}
}
