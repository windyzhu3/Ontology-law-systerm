package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.api.adapter.generated.model.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.*;
import tools.jackson.databind.module.SimpleModule;

/** Completes closed generated oneOf bindings without modifying the frozen generated interfaces. */
@Configuration(proxyBeanMethods=false)
public class R1JsonConfiguration {
    @Bean JsonMapperBuilderCustomizer r1OneOfBindings(){return builder->builder.addModule(oneOfModule()).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .withCoercionConfig(tools.jackson.databind.type.LogicalType.Textual,config->{for(var shape:java.util.List.of(tools.jackson.databind.cfg.CoercionInputShape.Integer,tools.jackson.databind.cfg.CoercionInputShape.Float,tools.jackson.databind.cfg.CoercionInputShape.Boolean))config.setCoercion(shape,tools.jackson.databind.cfg.CoercionAction.Fail);});}
    static SimpleModule oneOfModule(){
        var module=new SimpleModule("R1 closed ingress oneOf");
        // Only these closed values/partials have optional fields whose absence is semantic.
        // Do not apply NON_NULL globally: CurrentCard requires explicit null placeholders.
        for(var type:java.util.List.of(CompleteLeadIngressPhoneOnlyValuesV1.class,CompleteLeadIngressPhoneAndEmailValuesV1.class,CompleteLeadIngressEmailOnlyValuesV1.class,
                AssignLeadValuesV1.class,AcknowledgeSourceIntakeStopRequestValuesV1.class,ConnectedValidRecordContactResultValuesV1.class,NotConnectedRecordContactResultValuesV1.class,SuspectInvalidRecordContactResultValuesV1.class,
                ReviewLeadValidityValuesV1.class,ResolveDuplicateLeadValuesV1.class,RecordRoutingDispositionValuesV1.class,
                PartialCompleteLeadIngressValuesV1.class,PartialAssignLeadValuesV1.class,PartialAcknowledgeSourceIntakeStopRequestValuesV1.class,PartialReviewLeadValidityValuesV1.class,
                PartialResolveDuplicateLeadValuesV1.class,PartialRecordRoutingDispositionValuesV1.class,PartialRecordContactResultValuesV1.class,DueR1TaskPageV1.class))module.setMixInAnnotation(type,AbsentOptionalFields.class);
        module.addDeserializer(CompleteLeadIngressV1.class,new ValueDeserializer<CompleteLeadIngressV1>(){
            public CompleteLeadIngressV1 deserialize(JsonParser p,DeserializationContext c){var tree=c.readTree(p);return (CompleteLeadIngressV1)c.readTreeAsValue(tree,select(tree,false));}
        });
        module.addDeserializer(CompleteLeadIngressValuesV1.class,new ValueDeserializer<CompleteLeadIngressValuesV1>(){
            public CompleteLeadIngressValuesV1 deserialize(JsonParser p,DeserializationContext c){var tree=c.readTree(p);return (CompleteLeadIngressValuesV1)c.readTreeAsValue(tree,select(tree,true));}
        });
        module.addDeserializer(PartialCompleteLeadIngressValuesV1Phone.class,scalar(PartialCompleteLeadIngressValuesV1Phone.class));
        module.addDeserializer(PartialCompleteLeadIngressValuesV1Email.class,scalar(PartialCompleteLeadIngressValuesV1Email.class));
        module.addDeserializer(PartialAssignLeadValuesV1OwnerAppointmentId.class,scalar(PartialAssignLeadValuesV1OwnerAppointmentId.class));
        module.addDeserializer(PartialRecordContactResultValuesV1EvidenceSubmissionId.class,scalar(PartialRecordContactResultValuesV1EvidenceSubmissionId.class));
        return module;
    }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private abstract static class AbsentOptionalFields {}
    /** Only output form partials use these generated scalar oneOf marker interfaces. */
    private record PartialScalar(@com.fasterxml.jackson.annotation.JsonValue String value) implements PartialCompleteLeadIngressValuesV1Phone,PartialCompleteLeadIngressValuesV1Email,PartialAssignLeadValuesV1OwnerAppointmentId,PartialRecordContactResultValuesV1EvidenceSubmissionId {}
    private static <T> ValueDeserializer<T> scalar(Class<T> type){return new ValueDeserializer<>(){
        public T deserialize(JsonParser p,DeserializationContext c){var tree=c.readTree(p);if(!tree.isString())throw new IllegalArgumentException("Invalid form scalar");return type.cast(new PartialScalar(tree.asString()));}
    };}
    private static Class<?> select(JsonNode tree,boolean values){
        if(!tree.isObject()||(!tree.has("phone")&&!tree.has("email")))throw new R1HttpFailure("VALIDATION_FAILED");
        if(tree.has("phone")&&tree.has("email"))return values?CompleteLeadIngressPhoneAndEmailValuesV1.class:CompleteLeadIngressPhoneAndEmailV1.class;
        if(tree.has("phone"))return values?CompleteLeadIngressPhoneOnlyValuesV1.class:CompleteLeadIngressPhoneOnlyV1.class;
        return values?CompleteLeadIngressEmailOnlyValuesV1.class:CompleteLeadIngressEmailOnlyV1.class;
    }
}
