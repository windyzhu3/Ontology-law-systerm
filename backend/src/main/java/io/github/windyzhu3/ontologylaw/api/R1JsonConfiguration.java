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
        module.setMixInAnnotation(CurrentWorkCardEnvelope.class,OptionalSelectionNotice.class);
        module.setMixInAnnotation(NextSummary.class,OptionalSummaryMetadata.class);
        // Only these closed values/partials have optional fields whose absence is semantic.
        // Do not apply NON_NULL globally: CurrentCard requires explicit null placeholders.
        for(var type:java.util.List.of(CompleteLeadIngressPhoneOnlyValuesV1.class,CompleteLeadIngressPhoneAndEmailValuesV1.class,CompleteLeadIngressEmailOnlyValuesV1.class,
                AssignLeadValuesV1.class,AcknowledgeSourceIntakeStopRequestValuesV1.class,ConnectedValidRecordContactResultValuesV1.class,NotConnectedRecordContactResultValuesV1.class,SuspectInvalidRecordContactResultValuesV1.class,
                ReviewLeadValidityValuesV1.class,ResolveDuplicateLeadValuesV1.class,RecordRoutingDispositionValuesV1.class,RecordSourceRequestContinuationValuesV1.class,PartialRecordSourceRequestContinuationValuesV1.class,
                PartialCompleteLeadIngressValuesV1.class,PartialAssignLeadValuesV1.class,PartialAcknowledgeSourceIntakeStopRequestValuesV1.class,PartialReviewLeadValidityValuesV1.class,
                PartialResolveDuplicateLeadValuesV1.class,PartialRecordRoutingDispositionValuesV1.class,PartialRecordContactResultValuesV1.class,DueR1TaskPageV1.class,R2OpportunityTaskPageV1.class))module.setMixInAnnotation(type,AbsentOptionalFields.class);
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
        // R2's closed union reuses the seven R1 leaf models without widening their TaskType enum.
        // The envelope now uses the additive union rather than the R1 discriminator interface.
        // Serialize the real leaf property: the old interface otherwise suppresses taskType.
        for(var type:java.util.List.of(ResolveLeadDuplicateCurrentCard.class,CompleteLeadIngressCurrentCard.class,
                AssignLeadCurrentCard.class,ResolveLeadRoutingGapCurrentCard.class,AcknowledgeSourceIntakeStopRequestCurrentCard.class,
                ContactLeadCurrentCard.class,ReviewLeadValidityCurrentCard.class))module.setMixInAnnotation(type,ExplicitCardType.class);
        module.addDeserializer(CurrentWorkCardEnvelopeCurrentCard.class,new ValueDeserializer<CurrentWorkCardEnvelopeCurrentCard>(){
            public CurrentWorkCardEnvelopeCurrentCard deserialize(JsonParser p,DeserializationContext c){return (CurrentWorkCardEnvelopeCurrentCard)readCard(c.readTree(p),c);}
        });
        module.addDeserializer(R2CurrentCardV1.class,new ValueDeserializer<R2CurrentCardV1>(){
            public R2CurrentCardV1 deserialize(JsonParser p,DeserializationContext c){return (R2CurrentCardV1)readCard(c.readTree(p),c);}
        });
        module.addDeserializer(R2OpportunityFormV1Values.class,new ValueDeserializer<R2OpportunityFormV1Values>(){
            public R2OpportunityFormV1Values deserialize(JsonParser p,DeserializationContext c){var tree=c.readTree(p);if(!tree.isObject())throw new IllegalArgumentException("Invalid Opportunity form");return tree.size()==0?new EmptyOpportunityValues():c.readTreeAsValue(tree,OpportunityProgressValuesV1.class);}
        });
        for(var type:java.util.List.of(OpportunityLedgerItemV1.class,OpportunityLedgerDetailV1.class,OpportunityLedgerPageV1.class,OwnerExceptionDetailV1.class,OwnerExceptionPageV1.class,OwnerExceptionReceiverPageV1.class,OwnerExceptionOperationsPageV1.class,OwnerExceptionObservationPageV1.class))module.setMixInAnnotation(type,AbsentOptionalFields.class);
        module.addDeserializer(OwnerExceptionSelectorV1.class,new ValueDeserializer<OwnerExceptionSelectorV1>(){
            public OwnerExceptionSelectorV1 deserialize(JsonParser p,DeserializationContext c){var tree=c.readTree(p);return tree.has("hash")?c.readTreeAsValue(tree,OwnerExceptionDigestSelectorV1.class):c.readTreeAsValue(tree,OwnerExceptionRevisionSelectorV1.class);}
        });
        module.addDeserializer(OwnerExceptionCommandReceiptV1ResultFact.class,new ValueDeserializer<OwnerExceptionCommandReceiptV1ResultFact>(){
            public OwnerExceptionCommandReceiptV1ResultFact deserialize(JsonParser p,DeserializationContext c){var tree=c.readTree(p);String type=tree.path("factType").asString();if(!java.util.Set.of("OPPORTUNITY_OWNER_EXCEPTION","OPPORTUNITY_OWNER_VALIDATION").contains(type))throw new IllegalArgumentException("Unexpected exception result");return type.equals("OPPORTUNITY_OWNER_EXCEPTION")?c.readTreeAsValue(tree,OwnerExceptionFactRefV1.class):c.readTreeAsValue(tree,OwnerExceptionValidationFactRefV1.class);}
        });
        R2OpportunityClosureJson.install(module);
        R2CustomerRequirementsJson.install(module);
        R2MaterialsJson.install(module);
        module.addDeserializer(ContractPreparationReconcileReceiptV1ResultFact.class,new ValueDeserializer<ContractPreparationReconcileReceiptV1ResultFact>(){
            public ContractPreparationReconcileReceiptV1ResultFact deserialize(JsonParser p,DeserializationContext c){
                var tree=c.readTree(p);
                return switch(tree.path("factType").asString()){
                    case "CONTRACT_PREPARATION_WORKFLOW"->c.readTreeAsValue(tree,ContractPreparationWorkflowFactRefV1.class);
                    case "CONTRACT_TERMINATION_REVIEW_ASSIGNMENT"->c.readTreeAsValue(tree,ContractTerminationReviewAssignmentFactRefV1.class);
            case "TRANSFER_WORKFLOW"->c.readTreeAsValue(tree,TransferWorkflowFactRefV1.class);
            case "CONTRACT_PAYMENT_WORKFLOW"->c.readTreeAsValue(tree,ContractPaymentWorkflowFactRefV1.class);
            case "CONTRACT_EXECUTION_WORKFLOW"->c.readTreeAsValue(tree,ContractExecutionWorkflowFactRefV1.class);
            case "CONTRACT_SIGNATURE_WORKFLOW"->c.readTreeAsValue(tree,R2ContractSignatureWorkflowFactRefV1.class);
                    default->throw new IllegalArgumentException("Unexpected contract recovery result");
                };
            }
        });
        return module;
    }
    private static Object readCard(JsonNode tree,DeserializationContext context) {
        if(!tree.isObject()||!tree.has("taskType"))throw new IllegalArgumentException("Invalid workcard");
        Class<?> type=switch(tree.get("taskType").asString()) {
            case "RESOLVE_LEAD_DUPLICATE"->ResolveLeadDuplicateCurrentCard.class;
            case "COMPLETE_LEAD_INGRESS"->CompleteLeadIngressCurrentCard.class;
            case "ASSIGN_LEAD"->AssignLeadCurrentCard.class;
            case "RESOLVE_SOURCE_REQUEST"->R2SourceRequestCurrentCardV1.class;
            case "RESOLVE_LEAD_ROUTING_GAP"->ResolveLeadRoutingGapCurrentCard.class;
            case "ACK_SOURCE_INTAKE_STOP_REQUEST"->AcknowledgeSourceIntakeStopRequestCurrentCard.class;
            case "CONTACT_LEAD"->ContactLeadCurrentCard.class;
            case "REVIEW_LEAD_VALIDITY"->ReviewLeadValidityCurrentCard.class;
            case "PROGRESS_OPPORTUNITY"->R2OpportunityCurrentCardV1.class;
            case "CHECK_CONTRACT_RECEIPT","SUPPLEMENT_CONTRACT_RECEIPT","CHECK_CONTRACT_EXECUTION","REVIEW_CONTRACT_TERMINATION","ARRANGE_CONTRACT_SIGNATURE","COLLECT_CONTRACT_SIGNATURE","VERIFY_CONTRACT_SIGNATURE","ARCHIVE_CONTRACT_SIGNATURE","REQUEST_CONTRACT_PREPARATION","DECIDE_CONTRACT_PREPARATION","PREPARE_CONTRACT","SUBMIT_CONTRACT_REVIEW","REVIEW_CONTRACT","SUBMIT_CONTRACT_APPROVAL","APPROVE_CONTRACT","SUPPLEMENT_CONTRACT_REVIEW"->R2ContractCurrentCardV1.class;
            case "PREPARE_TRANSFER","REVIEW_TRANSFER","ACCEPT_TRANSFER","SUPPLEMENT_TRANSFER","CLASSIFY_MATTER"->R2TransferCurrentCardV1.class;
            case "PREPARE_QUOTE","SUBMIT_QUOTE_APPROVAL","APPROVE_QUOTE","DELIVER_QUOTE","RECORD_QUOTE_REPLY","RESOLVE_QUOTE_AUTHORITY"->R2QuoteCurrentCardV1.class;
            default->throw new IllegalArgumentException("Unregistered workcard");
        };
        return context.readTreeAsValue(tree,type);
    }
    private record EmptyOpportunityValues() implements R2OpportunityFormV1Values {
        @com.fasterxml.jackson.annotation.JsonValue public java.util.Map<String,Object> values(){return java.util.Map.of();}
    }
    private abstract static class OptionalSummaryMetadata {
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public abstract String getSubjectTitle();
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public abstract String getSubjectFactRef();
    }
    /** Omit only optional non-nullable selection text; required nullable card fields stay explicit. */
    private abstract static class OptionalSelectionNotice {
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public abstract String getSelectionNotice();
    }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(value={"taskType"},allowGetters=true,allowSetters=true)
    @com.fasterxml.jackson.annotation.JsonTypeInfo(use=com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NONE)
    private abstract static class ExplicitCardType {}
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
