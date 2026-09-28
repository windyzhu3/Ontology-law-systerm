package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService;
import java.sql.*;
import java.util.*;

/** Trusted static owner port. Implementations own SQL for their facts; all phases share one connection.
 * No phase may commit, switch capability, close the connection, call remote services, or mutate identity.
 */
public interface CommandHandler {
    /** Owner handlers use this for every proposed CAS, before mutating any fact. */
    static long nextRevision(long current)throws SQLException{
        if(current<0 || current>=9007199254740991L)throw new SQLException("Revision cannot be safely incremented","22003");
        return current+1;
    }
    record Context(CommandScope scope, AuthorizationService.Request authorization, CommandAuthorizationBinding binding) {
        public Context(CommandScope scope, AuthorizationService.Request authorization) { this(scope,authorization,null); }
        public Context {Objects.requireNonNull(scope);Objects.requireNonNull(authorization);}
    }
    enum QueueOwner { R1_PROJECTION, R2_PROJECTION }
    enum Event {
        OpportunitySupersededTaskRepairedV1("responsibility.task_occurrence","revision:post-CAS"),
        SourceRequestTaskRestoredV1("responsibility.task_occurrence","revision:0"),
        SourceRequestContinuationRecordedV1("responsibility.decision_record","hash:content"),
        ContractNegotiationEndedV1("contract.negotiation_disposition","revision:0"),
        ContractTerminationReviewRequestedV1("contract.negotiation_disposition","revision:0"),
        ContractTerminationReviewRecordedV1("contract.negotiation_disposition","revision:0"),
        ContractTerminationReviewReconciledV1("contract.termination_review_assignment","revision:0"),
        ContractSignatureVerificationReturnedV1("contract.signature_revision_return","revision:0"),
        ContractSignatureDraftSavedV1("contract.signature_draft","revision:0"),
        ContractSignatureArrangementConfirmedV1("contract.signature_arrangement","revision:0"),
        ContractSignatureSubmittedV1("contract.signature_submission","revision:0"),
        ContractSignatureVerificationRecordedV1("contract.signature_verification","revision:0"),
        ContractSignatureArchivedV1("contract.signature_archive","revision:0"),
        ContractReturnedForRevisionV1("contract.signature_revision_return","revision:0"),
        ContractExecutionConditionsVerifiedV1("contract.execution_verification","revision:0"),
        ContractExecutionReconciledV1("contract.execution_workflow","revision:0"),
        TransferWorkflowReconciledV1("transfer.workflow","revision:0"),
        TransferSubmittedV1("transfer.submission","revision:0"),
        TransferResubmittedV1("transfer.submission","revision:0"),
        TransferConflictReviewRecordedV1("transfer.review","revision:0"),
        TransferIntakeRecordedV1("transfer.intake","revision:0"),
        MatterClassifiedV1("transfer.classification","revision:0"),
        ContractReceiptReviewRequestedV1("contract.payment_request","revision:0"),
        ContractReceiptReviewRecordedV1("contract.payment_review","revision:0"),
        ContractReceiptSupplementedV1("contract.payment_review","revision:0"),
        ContractPaymentReconciledV1("contract.payment_workflow","revision:0"),
        ContractSignatureReconciledV1("contract.signature_workflow","revision:0"),
        LeadCapturedV1("lead.lead","revision:transaction-final"), ActionDraftSavedV1("responsibility.action_draft","revision:post-write"),
        ContactTaskReopenedV1("responsibility.task_occurrence","revision:post-CAS"), RoutingReviewTaskReopenedV1("responsibility.task_occurrence","revision:post-CAS"), SourceRequestReviewReopenedV1("responsibility.task_occurrence","revision:post-CAS"),
        LeadDuplicateResolutionRecordedV1("responsibility.decision_record","hash:content"), LeadIngressCompletedV1("lead.lead","revision:post-CAS"), LeadAssignedV1("lead.lead_assignment","revision:0"),
        LeadRoutingDispositionRecordedV1("responsibility.decision_record","hash:content"), SourceIntakeStopRequestedV1("responsibility.decision_record","hash:content"), SourceIntakeStopRequestAcknowledgedV1("responsibility.decision_record","hash:content"),
        LeadContactResultRecordedV1("lead.lead_contact_result","hash:immutable-row"), LeadContactRetryExhaustedV1("lead.lead_contact_result","hash:immutable-row"), LeadValidityReviewedV1("responsibility.decision_record","hash:content"),
        OpportunityOpened("opportunity.opportunity","revision:0"),
        OpportunityActionDraftSavedV1("responsibility.action_draft","revision:post-write"),
        OpportunityProgressRecordedV1("opportunity.opportunity_progress","hash:protected-body"),
        OpportunityInitialTaskActivatedV1("responsibility.task_occurrence","revision:0"),
        OpportunityTaskReopenedV1("responsibility.task_occurrence","revision:post-CAS"),
        OpportunityOwnerExceptionObservedV1("opportunity.owner_exception","revision:post-write"),
        OpportunityOwnerCoordinationRecordedV1("opportunity.owner_exception","revision:post-write"),
        OpportunityResponsibilityTransferredV1("opportunity.owner_exception","revision:post-write"),
        OpportunityCustomerDraftSavedV1("opportunity.customer_requirement_draft","revision:0"),
        OpportunityCustomerRequirementsConfirmedV1("opportunity.customer_requirement_confirmation","revision:0"),
        OpportunityMaterialUploadOpenedV1("evidence.material_upload_basis","revision:0"),
        OpportunityMaterialAcceptedV1("opportunity.material_version","revision:0"),
        OpportunityClosedV1("opportunity.closure","revision:0"),
        ContractPreparationReconciledV1("contract.preparation_workflow","revision:0"),
        ContractPreparationRequestedV1("contract.preparation_request","revision:0"),
        ContractPreparationDecisionRecordedV1("contract.preparation_decision","revision:0"),
        ContractPreparationStartedV1("contract.contract","revision:transaction-final"),
        ContractDraftSavedV1("contract.preparation_draft","revision:0"),
        ContractFormedV1("contract.contract_revision","hash:protected-body"),
        ContractReviewRequestedV1("contract.revision_review_request","revision:0"),
        ContractReviewRecordedV1("contract.revision_review_decision","revision:0"),
        ContractApprovalRequestedV1("contract.revision_approval_request","revision:0"),
        ContractDecisionRecordedV1("contract.revision_approval_decision","revision:0"),
        SalesFollowupAttemptRecordedV1("opportunity.followup_attempt","hash:body_digest"),
        QuoteNegotiationEndedV1("opportunity.quote_termination","revision:0"),
        QuotePreparationStartedV1("opportunity.quote_preparation_intent","revision:0"),
        QuoteDraftSavedV1("opportunity.quote_draft","revision:0"),
        QuoteFormedV1("opportunity.quote_revision","hash:protected-body"),
        QuoteApprovalRequestedV1("opportunity.quote_approval_request","revision:0"),
        QuoteDecisionRecordedV1("opportunity.quote_approval_decision","revision:0"),
        QuoteDeliveredV1("opportunity.quote_issue","revision:0"),
        QuoteResponseRecordedV1("opportunity.quote_response","hash:protected-body");
        private final String sourceFactType,sourceSelector;
        Event(String sourceFactType,String sourceSelector){this.sourceFactType=sourceFactType;this.sourceSelector=sourceSelector;}
        public String sourceFactType(){return sourceFactType;}
        public String sourceSelector(){return sourceSelector;}
        public int schemaVersion(){return 1;}
        public Set<QueueOwner> queueOwners(){return Set.of(name().startsWith("Transfer")||this==MatterClassifiedV1||name().startsWith("Contract")||name().startsWith("Quote")||this==OpportunityMaterialUploadOpenedV1||this==OpportunityMaterialAcceptedV1||this==OpportunityCustomerDraftSavedV1||this==OpportunityCustomerRequirementsConfirmedV1||this==OpportunityClosedV1||this==OpportunityOwnerExceptionObservedV1||this==OpportunityOwnerCoordinationRecordedV1||this==OpportunityResponsibilityTransferredV1||this==OpportunityInitialTaskActivatedV1||this==OpportunityProgressRecordedV1||this==OpportunityActionDraftSavedV1||this==OpportunitySupersededTaskRepairedV1||this==OpportunityTaskReopenedV1?QueueOwner.R2_PROJECTION:QueueOwner.R1_PROJECTION);}
    }
    record Notification(Event event,AuthorizationService.Subject sourceFact) {
        public Notification {Objects.requireNonNull(event);Objects.requireNonNull(sourceFact);}
    }
    record Result(CommandOutcome.Status status,AuthorizationService.Subject fact,List<Notification> notifications) {
        public Result {
            Objects.requireNonNull(status);
            notifications=List.copyOf(notifications);
            if(status==CommandOutcome.Status.REJECTED || fact==null || (status==CommandOutcome.Status.SUCCEEDED)!=(!notifications.isEmpty()))throw new IllegalArgumentException("Invalid handler result");
        }
        public static Result succeeded(AuthorizationService.Subject fact,Event event){return new Result(CommandOutcome.Status.SUCCEEDED,fact,List.of(new Notification(event,fact)));}
        public static Result noChange(AuthorizationService.Subject fact){return new Result(CommandOutcome.Status.NO_CHANGE,fact,List.of());}
    }
    /** Only safe code, never a payload or exception message. */
    final class Rejected extends RuntimeException {
        private final String code;
        public Rejected(String code){super("Command rejected");if(!code.matches("[A-Z][A-Z0-9_]{0,63}"))throw new IllegalArgumentException("Invalid rejection code");this.code=code;}
        public String code(){return code;}
    }
    CommandEnvelope.Type type();
    /** QUERY: input/static schema, visibility and Draft existence; no locks/writes or mutable-state CAS rejection. */
    Context resolve(Connection connection,CommandEnvelope envelope) throws SQLException;
    /** COMMAND: lock Lead (or capture source key) then Task; do not reject advanced state before replay lookup. */
    void lockRoots(Connection connection,CommandEnvelope envelope,Context context) throws SQLException;
    /** Only recovery commands: NEW keys pass eligibility under Lead/Task/Command locks before slot insertion. */
    void recoveryEligibility(Connection connection,CommandEnvelope envelope,Context context) throws SQLException;
    /** COMMAND: current subject/Task/Draft/owner and safe revision increment, before any fact mutation. */
    void validateBeforeWork(Connection connection,CommandEnvelope envelope,Context context) throws SQLException;
    Result execute(Connection connection,CommandEnvelope envelope,Context context) throws SQLException;
    /** QUERY under held locks: exact result and every notification source fact, subject CAS and responsibility ownership after own writes. */
    void validateBeforeCommit(Connection connection,CommandEnvelope envelope,Context context,Result result) throws SQLException;
}
