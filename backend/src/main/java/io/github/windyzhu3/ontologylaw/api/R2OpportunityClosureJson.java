package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.api.adapter.generated.model.*;
import java.util.*;
import tools.jackson.core.*;
import tools.jackson.databind.*;
import tools.jackson.databind.module.SimpleModule;

/** Completes only T04 unions; preserves required explicit nulls without disclosing unavailable selectors. */
final class R2OpportunityClosureJson {
    private R2OpportunityClosureJson(){}
    static void install(SimpleModule module){
        module.setMixInAnnotation(OpportunityClosureDetailV1.class,AbsentSummary.class);
        module.addDeserializer(OpportunityCloseContextV1ExpectedTask.class,new ValueDeserializer<OpportunityCloseContextV1ExpectedTask>(){
            public OpportunityCloseContextV1ExpectedTask deserialize(JsonParser p,DeserializationContext c){return c.readTreeAsValue(c.readTree(p),OwnerExceptionRevisionSelectorV1.class);}
        });
        module.addDeserializer(OpportunityCloseContextV1ExpectedWait.class,new ValueDeserializer<OpportunityCloseContextV1ExpectedWait>(){
            public OpportunityCloseContextV1ExpectedWait deserialize(JsonParser p,DeserializationContext c){return c.readTreeAsValue(c.readTree(p),OwnerExceptionDigestSelectorV1.class);}
        });
        module.addSerializer(OpportunityCloseContextV1.class,new ValueSerializer<OpportunityCloseContextV1>(){
            public void serialize(OpportunityCloseContextV1 value,JsonGenerator out,SerializationContext c){
                var fields=new LinkedHashMap<String,Object>();fields.put("opportunity",value.getOpportunity());fields.put("status",value.getStatus());
                if(value.getStatus()==OpportunityCloseContextV1.StatusEnum.READY){fields.put("expectedResponsibility",value.getExpectedResponsibility());fields.put("expectedTask",value.getExpectedTask());fields.put("expectedWait",value.getExpectedWait());}
                if(value.getStatus()==OpportunityCloseContextV1.StatusEnum.CLOSED&&value.getClosure()!=null)fields.put("closure",value.getClosure());
                c.writeValue(out,fields);
            }
        });
        module.addDeserializer(CloseOpportunityV1.class,new ValueDeserializer<CloseOpportunityV1>(){
            public CloseOpportunityV1 deserialize(JsonParser p,DeserializationContext c){
                var tree=c.readTree(p);var required=List.of("expectedOpportunityRevision","expectedResponsibility","expectedTask","expectedWait","reasonCode","summary");
                if(!tree.isObject()||tree.size()!=required.size()||required.stream().anyMatch(k->!tree.has(k)))throw R1HttpFailure.validation("/","CONDITION_FAILED");
                return new CloseOpportunityV1(c.readTreeAsValue(tree.get("expectedOpportunityRevision"),Long.class),c.readTreeAsValue(tree.get("expectedResponsibility"),OwnerExceptionRevisionSelectorV1.class),tree.get("expectedTask").isNull()?null:c.readTreeAsValue(tree.get("expectedTask"),OwnerExceptionRevisionSelectorV1.class),tree.get("expectedWait").isNull()?null:c.readTreeAsValue(tree.get("expectedWait"),OwnerExceptionDigestSelectorV1.class),c.readTreeAsValue(tree.get("reasonCode"),OpportunityCloseReasonV1.class),c.readTreeAsValue(tree.get("summary"),String.class));
            }
        });
    }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private abstract static class AbsentSummary {}
}
