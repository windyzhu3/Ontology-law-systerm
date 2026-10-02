package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.api.adapter.generated.model.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Explicit closed oneOf selection; generated models are never regenerated or widened here. */
final class R1WireModels {
    private static final JsonMapper MAPPER=JsonMapper.builder().addModule(R1JsonConfiguration.oneOfModule()).build();
    private R1WireModels(){}
    static <T> T model(Object value,Class<T> type){return MAPPER.convertValue(value,type);}
    static Object payload(Object value){return omitAbsent(MAPPER.convertValue(value,Map.class));}
    private static Object omitAbsent(Object value){
        if(value instanceof Map<?,?> map){var fields=new TreeMap<String,Object>();map.forEach((k,v)->{if(v!=null)fields.put((String)k,omitAbsent(v));});return fields;}
        if(value instanceof List<?> list)return list.stream().map(R1WireModels::omitAbsent).toList();
        if(value instanceof Integer||value instanceof Short||value instanceof Byte)return ((Number)value).longValue();
        return value;
    }
    static CommandReceipt receipt(Map<String,Object> body) {
        if("REJECTED".equals(body.get("outcome")))return MAPPER.convertValue(body,RejectedCommandReceipt.class);
        var fields=new TreeMap<>(body);Object result=fields.remove("resultFact");
        if(!(result instanceof Map<?,?> fact))throw new IllegalArgumentException("Invalid receipt projection");
        return MAPPER.convertValue(fields,SuccessfulCommandReceipt.class).resultFact(fact(fact));
    }
    static PublicFactRef fact(Map<?,?> body) {
        Class<? extends PublicFactRef> type=switch((String)body.get("factType")) {
            case "TRANSFER_WORKFLOW"->TransferWorkflowFactRefV1.class;case "TRANSFER_SUBMISSION"->TransferSubmissionFactRefV1.class;case "TRANSFER_CONFLICT_REVIEW"->TransferConflictReviewFactRefV1.class;case "TRANSFER_INTAKE"->TransferIntakeFactRefV1.class;case "MATTER_CLASSIFICATION"->MatterClassificationFactRefV1.class;
            case "LEAD"->LeadFactRef.class;case "ACTION_DRAFT"->ActionDraftFactRef.class;
            case "TASK_OCCURRENCE"->TaskOccurrenceFactRef.class;case "DECISION_RECORD"->DecisionRecordFactRef.class;
            case "OPPORTUNITY_MATERIAL_UPLOAD"->OpportunityMaterialUploadFactRefV1.class;
            case "OPPORTUNITY_MATERIAL_VERSION"->OpportunityMaterialVersionFactRefV1.class;
            case "CONTRACT_SIGNATURE_DRAFT"->R2ContractSignatureDraftFactRefV1.class;
            case "CONTRACT_SIGNATURE_ARRANGEMENT"->R2ContractSignatureArrangementFactRefV1.class;
            case "CONTRACT_SIGNATURE_SUBMISSION"->R2ContractSignatureSubmissionFactRefV1.class;
            case "CONTRACT_SIGNATURE_VERIFICATION"->R2ContractSignatureVerificationFactRefV1.class;
            case "CONTRACT_EXECUTION_VERIFICATION"->ContractExecutionVerificationFactRefV1.class;
            case "CONTRACT_PAYMENT_REQUEST"->ContractPaymentRequestFactRefV1.class;case "CONTRACT_PAYMENT_REVIEW"->ContractPaymentReviewFactRefV1.class;case "CONTRACT_PAYMENT_WORKFLOW"->ContractPaymentWorkflowFactRefV1.class;
            case "CONTRACT_EXECUTION_WORKFLOW"->ContractExecutionWorkflowFactRefV1.class;
            case "CONTRACT_SIGNATURE_ARCHIVE"->R2ContractSignatureArchiveFactRefV1.class;
            case "CONTRACT_SIGNATURE_REVISION_RETURN"->R2ContractSignatureRevisionReturnFactRefV1.class;
            case "CONTRACT_TERMINATION_REVIEW_ASSIGNMENT"->ContractTerminationReviewAssignmentFactRefV1.class;
            case "CONTRACT_NEGOTIATION_DISPOSITION"->ContractNegotiationDispositionFactRefV1.class;
            case "CONTRACT_SIGNATURE_WORKFLOW"->R2ContractSignatureWorkflowFactRefV1.class;
            case "CONTRACT_SIGNATURE_HANDOFF"->R2ContractSignatureHandoffFactRefV1.class;
            case "CONTRACT_PREPARATION_WORKFLOW"->ContractPreparationWorkflowFactRefV1.class;
            case "CONTRACT_PREPARATION_REQUEST"->R2ContractPreparationRequestFactRefV1.class;
            case "CONTRACT_PREPARATION_DECISION"->R2ContractPreparationDecisionFactRefV1.class;
            case "CONTRACT"->R2ContractFactRefV1.class;
            case "CONTRACT_DRAFT"->R2ContractDraftFactRefV1.class;
            case "CONTRACT_REVISION"->R2ContractRevisionFactRefV1.class;
            case "CONTRACT_REVIEW_REQUEST"->R2ContractReviewRequestFactRefV1.class;
            case "CONTRACT_REVIEW_DECISION"->R2ContractReviewDecisionFactRefV1.class;
            case "CONTRACT_APPROVAL_REQUEST"->R2ContractApprovalRequestFactRefV1.class;
            case "CONTRACT_APPROVAL_DECISION"->R2ContractApprovalDecisionFactRefV1.class;
            case "FOLLOWUP_ATTEMPT"->FollowupAttemptFactRefV1.class;
            case "QUOTE_TERMINATION"->R2QuoteTerminationFactRefV1.class;
            case "QUOTE_PREPARATION_INTENT"->R2QuotePreparationIntentFactRefV1.class;
            case "QUOTE_DRAFT"->R2QuoteDraftFactRefV1.class;
            case "QUOTE_REVISION"->R2QuoteRevisionFactRefV1.class;
            case "QUOTE_APPROVAL_REQUEST"->R2QuoteApprovalRequestFactRefV1.class;
            case "QUOTE_APPROVAL_DECISION"->R2QuoteApprovalDecisionFactRefV1.class;
            case "QUOTE_ISSUE"->R2QuoteIssueFactRefV1.class;
            case "QUOTE_RESPONSE"->R2QuoteResponseFactRefV1.class;
            case "OPPORTUNITY_CUSTOMER_DRAFT"->OpportunityCustomerDraftFactRefV1.class;
            case "OPPORTUNITY_CUSTOMER_CONFIRMATION"->OpportunityCustomerConfirmationFactRefV1.class;
            case "OPPORTUNITY_CLOSURE"->OpportunityClosureFactRefV1.class;
            case "OPPORTUNITY_PROGRESS"->OpportunityProgressFactRefV1.class;
            case "OPPORTUNITY_OWNER_EXCEPTION"->OwnerExceptionFactRefV1.class;case "OPPORTUNITY_OWNER_VALIDATION"->OwnerExceptionValidationFactRefV1.class;
            case "LEAD_ASSIGNMENT"->LeadAssignmentFactRef.class;case "LEAD_CONTACT_RESULT"->LeadContactResultFactRef.class;
            case "APPOINTMENT_ROLE"->AppointmentRoleFactRefV1.class;case "IDENTITY_PRINCIPAL"->IdentityPrincipalFactRefV1.class;case "ORGANIZATION_UNIT"->OrganizationUnitFactRefV1.class;case "APPOINTMENT"->AppointmentFactRefV1.class;case "AUTHORITY_GRANT"->AuthorityGrantFactRefV1.class;
            default->throw new IllegalArgumentException("Invalid receipt projection");
        };
        return MAPPER.convertValue(body,type);
    }
}
