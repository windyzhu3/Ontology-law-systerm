package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.api.adapter.generated.model.*;
import com.fasterxml.jackson.annotation.JsonInclude;
final class R2MaterialsJson {
 static void install(tools.jackson.databind.module.SimpleModule module){
  module.setMixInAnnotation(OpportunityMaterialsContextV1.class,OptionalContext.class);
  module.setMixInAnnotation(OpportunityMaterialUploadV1.class,OptionalUpload.class);
  module.setMixInAnnotation(OpportunityMaterialVersionV1.class,OptionalVersion.class);
  module.addDeserializer(OpportunityMaterialCommandReceiptV1ResultFact.class,new tools.jackson.databind.ValueDeserializer<OpportunityMaterialCommandReceiptV1ResultFact>(){
   public OpportunityMaterialCommandReceiptV1ResultFact deserialize(tools.jackson.core.JsonParser p,tools.jackson.databind.DeserializationContext c){var tree=c.readTree(p);return switch(tree.path("factType").asString()){
    case "OPPORTUNITY_MATERIAL_UPLOAD"->c.readTreeAsValue(tree,OpportunityMaterialUploadFactRefV1.class);
    case "OPPORTUNITY_MATERIAL_VERSION"->c.readTreeAsValue(tree,OpportunityMaterialVersionFactRefV1.class);
    default->throw new IllegalArgumentException("Unexpected material fact");};}
  });
 }
 private abstract static class OptionalContext {
  @JsonInclude(JsonInclude.Include.NON_NULL) public abstract OpportunityCustomerSelectorV1 getConfirmation();
  @JsonInclude(JsonInclude.Include.NON_NULL) public abstract String getCurrentOwnerLabel();
 }
 private abstract static class OptionalVersion {
  @JsonInclude(JsonInclude.Include.NON_NULL) public abstract String getReceivedByLabel();
 }
 private abstract static class OptionalUpload {
  @JsonInclude(JsonInclude.Include.NON_NULL) public abstract Integer getSizeBytes();
  @JsonInclude(JsonInclude.Include.NON_NULL) public abstract String getMediaType();
  @JsonInclude(JsonInclude.Include.NON_NULL) public abstract String getResultCode();
 }
}
