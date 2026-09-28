package io.github.windyzhu3.ontologylaw.contract;

import java.util.Set;

/**
 * Static command vocabulary and legal stage transitions only.
 * Callers derive stage/outcomes from exact persisted facts and must independently validate
 * sources, actor authority, review scope, every required approval, and transaction provenance.
 * Nothing in this class is an authorization or approval proof.
 */
public final class ContractWorkflowProtocol {
    private ContractWorkflowProtocol() {}

    public enum Stage {
        INITIAL, PREPARATION_REQUESTED, PREPARATION_RETURNED, SOURCE_APPROVED,
        PREPARE, FORMED, AWAIT_REVIEW, NEED_INFO, BLOCKED, REVIEW_PASSED,
        AWAIT_APPROVAL, RETURNED, READY_FOR_SIGNATURE, CHECK_CONDITIONS, READY_TRANSFER
    }
    public enum ReviewOutcome { CLEAR, WAIVED, NEED_INFO, BLOCKED }
    public enum Decision { APPROVED, RETURNED }
    public enum Action {
        VERIFY_CONTRACT_EXECUTION_CONDITIONS("CONTRACT_EXECUTION_VERIFY","contract.execution_verification","ContractExecutionConditionsVerifiedV1"),
        END_CONTRACT_NEGOTIATION("OPPORTUNITY_CLOSE","contract.negotiation_disposition","ContractNegotiationEndedV1"),
        REQUEST_CONTRACT_TERMINATION_REVIEW("OPPORTUNITY_CLOSE","contract.negotiation_disposition","ContractTerminationReviewRequestedV1"),
        RECORD_CONTRACT_TERMINATION_REVIEW("CONTRACT_TERMINATION_REVIEW","contract.negotiation_disposition","ContractTerminationReviewRecordedV1"),

        REQUEST_CONTRACT_PREPARATION("CONTRACT_PREPARE","contract.preparation_request","ContractPreparationRequestedV1"),
        RECORD_CONTRACT_PREPARATION_DECISION("CONTRACT_PREPARATION_DECIDE","contract.preparation_decision","ContractPreparationDecisionRecordedV1"),
        START_CONTRACT_PREPARATION("CONTRACT_PREPARE","contract.contract","ContractPreparationStartedV1"),
        SAVE_CONTRACT_DRAFT("CONTRACT_PREPARE","contract.preparation_draft","ContractDraftSavedV1"),
        FORM_CONTRACT("CONTRACT_PREPARE","contract.contract_revision","ContractFormedV1"),
        REQUEST_CONTRACT_REVIEW("CONTRACT_PREPARE","contract.revision_review_request","ContractReviewRequestedV1"),
        RECORD_CONTRACT_REVIEW("CONTRACT_REVIEW","contract.revision_review_decision","ContractReviewRecordedV1"),
        REQUEST_CONTRACT_APPROVAL("CONTRACT_PREPARE","contract.revision_approval_request","ContractApprovalRequestedV1"),
        RECORD_CONTRACT_DECISION("CONTRACT_APPROVE","contract.revision_approval_decision","ContractDecisionRecordedV1"),
        SAVE_CONTRACT_SIGNATURE_DRAFT("CONTRACT_PREPARE","contract.signature_draft","ContractSignatureDraftSavedV1"),
        CONFIRM_CONTRACT_SIGNATURE_ARRANGEMENT("CONTRACT_PREPARE","contract.signature_arrangement","ContractSignatureArrangementConfirmedV1"),
        SUBMIT_CONTRACT_SIGNATURE("CONTRACT_PREPARE","contract.signature_submission","ContractSignatureSubmittedV1"),
        RECORD_CONTRACT_SIGNATURE_VERIFICATION("CONTRACT_SIGNATURE_VERIFY","contract.signature_verification","ContractSignatureVerificationRecordedV1"),
        ARCHIVE_CONTRACT_SIGNATURE("CONTRACT_SIGNATURE_VERIFY","contract.signature_archive","ContractSignatureArchivedV1"),
        RETURN_CONTRACT_SIGNATURE_FOR_REVISION("CONTRACT_SIGNATURE_VERIFY","contract.signature_revision_return","ContractSignatureVerificationReturnedV1"),
        RETURN_CONTRACT_FOR_REVISION("CONTRACT_PREPARE","contract.signature_revision_return","ContractReturnedForRevisionV1");

        private final String authority,resultType,event;
        Action(String authority,String resultType,String event) {
            this.authority=authority;this.resultType=resultType;this.event=event;
        }
    }
    public static String authority(Action action) { return required(action).authority; }
    public static String resultType(Action action) { return required(action).resultType; }
    public static String event(Action action) { return required(action).event; }

    /** Rejects stage-inconsistent commands; says nothing about the caller's permission. */
    public static void requireAllowed(Stage stage,Action action) {
        if(stage==null)throw invalid();
        boolean allowed=switch(required(action)) {
            case VERIFY_CONTRACT_EXECUTION_CONDITIONS -> stage==Stage.CHECK_CONDITIONS;
            case END_CONTRACT_NEGOTIATION,REQUEST_CONTRACT_TERMINATION_REVIEW,RECORD_CONTRACT_TERMINATION_REVIEW -> true;
            case REQUEST_CONTRACT_PREPARATION -> Set.of(Stage.INITIAL,Stage.PREPARATION_RETURNED,
                Stage.SOURCE_APPROVED,Stage.PREPARE,Stage.NEED_INFO,Stage.BLOCKED,Stage.RETURNED).contains(stage);
            case RECORD_CONTRACT_PREPARATION_DECISION -> stage==Stage.PREPARATION_REQUESTED;
            case START_CONTRACT_PREPARATION -> stage==Stage.INITIAL||stage==Stage.SOURCE_APPROVED;
            case SAVE_CONTRACT_DRAFT -> true;
            case SAVE_CONTRACT_SIGNATURE_DRAFT,CONFIRM_CONTRACT_SIGNATURE_ARRANGEMENT,SUBMIT_CONTRACT_SIGNATURE,RECORD_CONTRACT_SIGNATURE_VERIFICATION,ARCHIVE_CONTRACT_SIGNATURE,RETURN_CONTRACT_FOR_REVISION,RETURN_CONTRACT_SIGNATURE_FOR_REVISION -> stage==Stage.READY_FOR_SIGNATURE;
            case FORM_CONTRACT -> Set.of(Stage.PREPARE,Stage.FORMED,Stage.NEED_INFO,Stage.BLOCKED,
                Stage.REVIEW_PASSED,Stage.RETURNED,Stage.READY_FOR_SIGNATURE).contains(stage);
            case REQUEST_CONTRACT_REVIEW -> stage==Stage.FORMED||stage==Stage.NEED_INFO;
            case RECORD_CONTRACT_REVIEW -> stage==Stage.AWAIT_REVIEW;
            case REQUEST_CONTRACT_APPROVAL -> stage==Stage.REVIEW_PASSED;
            case RECORD_CONTRACT_DECISION -> stage==Stage.AWAIT_APPROVAL;
        };
        if(!allowed)throw invalid();
    }

    /**
     * allRequiredApproved is a caller-computed result of final persisted-set revalidation,
     * never a client field. It must be false except for an approved contract decision.
     * READY_FOR_SIGNATURE is an exact handoff boundary, not a signature or execution fact.
     */
    public static Stage after(Stage current,Action action,ReviewOutcome review,Decision decision,
                              boolean allRequiredApproved) {
        requireAllowed(current,action);
        boolean reviewAction=action==Action.RECORD_CONTRACT_REVIEW;
        boolean decisionAction=action==Action.RECORD_CONTRACT_PREPARATION_DECISION||action==Action.RECORD_CONTRACT_DECISION;
        if(reviewAction!=(review!=null)||decisionAction!=(decision!=null)
            ||allRequiredApproved&&(action!=Action.RECORD_CONTRACT_DECISION||decision!=Decision.APPROVED))throw invalid();
        return switch(action) {
            case VERIFY_CONTRACT_EXECUTION_CONDITIONS -> Stage.READY_TRANSFER;
            case END_CONTRACT_NEGOTIATION,REQUEST_CONTRACT_TERMINATION_REVIEW,RECORD_CONTRACT_TERMINATION_REVIEW -> current;
            case REQUEST_CONTRACT_PREPARATION -> Stage.PREPARATION_REQUESTED;
            case RECORD_CONTRACT_PREPARATION_DECISION -> decision==Decision.APPROVED?Stage.SOURCE_APPROVED:Stage.PREPARATION_RETURNED;
            case START_CONTRACT_PREPARATION -> Stage.PREPARE;
            case SAVE_CONTRACT_DRAFT -> current;
            case SAVE_CONTRACT_SIGNATURE_DRAFT,CONFIRM_CONTRACT_SIGNATURE_ARRANGEMENT,SUBMIT_CONTRACT_SIGNATURE,RECORD_CONTRACT_SIGNATURE_VERIFICATION,ARCHIVE_CONTRACT_SIGNATURE,RETURN_CONTRACT_FOR_REVISION,RETURN_CONTRACT_SIGNATURE_FOR_REVISION -> current;
            case FORM_CONTRACT -> Stage.FORMED;
            case REQUEST_CONTRACT_REVIEW -> Stage.AWAIT_REVIEW;
            case RECORD_CONTRACT_REVIEW -> switch(review) {
                case CLEAR,WAIVED -> Stage.REVIEW_PASSED;
                case NEED_INFO -> Stage.NEED_INFO;
                case BLOCKED -> Stage.BLOCKED;
            };
            case REQUEST_CONTRACT_APPROVAL -> Stage.AWAIT_APPROVAL;
            case RECORD_CONTRACT_DECISION -> decision==Decision.RETURNED?Stage.RETURNED:
                allRequiredApproved?Stage.READY_FOR_SIGNATURE:Stage.AWAIT_APPROVAL;
        };
    }
    private static Action required(Action action) { if(action==null)throw invalid();return action; }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid contract workflow transition"); }
}
