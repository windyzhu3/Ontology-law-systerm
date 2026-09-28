package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.util.*;
import static io.github.windyzhu3.ontologylaw.execution.CommandHandler.Event.*;

/** Per-invocation verifier. Reads exact Owner facts on the runtime transaction, never caller outcome labels. */
public final class R1EventPolicy {
    public record Branch(String id,CommandEnvelope.Type command,String outcome,Set<CommandHandler.Event> events) {
        public Branch { events=Set.copyOf(events); }
    }
    public static CommandHandler.Event transferEvent(CommandEnvelope.Type type){return switch(type){case SUBMIT_TRANSFER->TransferSubmittedV1;case RESUBMIT_TRANSFER->TransferResubmittedV1;case RECORD_TRANSFER_CONFLICT_REVIEW->TransferConflictReviewRecordedV1;case RECORD_TRANSFER_INTAKE->TransferIntakeRecordedV1;case CLASSIFY_MATTER->MatterClassifiedV1;default->throw new IllegalArgumentException("Not a transfer command");};}
    private static Branch branch(String id,CommandEnvelope.Type command,String outcome,CommandHandler.Event... events){return new Branch(id,command,outcome,Set.of(events));}
    private static final List<Branch> BRANCHES=List.of(
        branch("CAPTURE_LEAD_CREATED",CommandEnvelope.Type.CAPTURE_LEAD,"CREATED",LeadCapturedV1),
        branch("SAVE_ACTION_DRAFT_CHANGED",CommandEnvelope.Type.SAVE_ACTION_DRAFT,"CREATED_OR_CHANGED",ActionDraftSavedV1),
        branch("REOPEN_DUE_CONTACT_TASKS_REOPENED",CommandEnvelope.Type.REOPEN_DUE_CONTACT_TASKS,"WAITING_TO_OPEN",ContactTaskReopenedV1),
        branch("REOPEN_DUE_ROUTING_REVIEW_TASKS_REOPENED",CommandEnvelope.Type.REOPEN_DUE_ROUTING_REVIEW_TASKS,"WAITING_TO_OPEN",RoutingReviewTaskReopenedV1),
        branch("P0_01_LINK_EXISTING",CommandEnvelope.Type.RESOLVE_DUPLICATE_LEAD,"LINK_EXISTING_PARTY",LeadDuplicateResolutionRecordedV1),
        branch("P0_01_KEEP_SEPARATE",CommandEnvelope.Type.RESOLVE_DUPLICATE_LEAD,"KEEP_SEPARATE",LeadDuplicateResolutionRecordedV1),
        branch("P0_02_COMPLETE",CommandEnvelope.Type.COMPLETE_LEAD_INGRESS,"INGRESS_COMPLETED",LeadIngressCompletedV1),
        branch("P0_03_ASSIGN",CommandEnvelope.Type.ASSIGN_LEAD,"ASSIGNED",LeadAssignedV1),
        branch("P0_04_SCHEDULE_ROUTING_REVIEW",CommandEnvelope.Type.RECORD_ROUTING_DISPOSITION,"SCHEDULE_ROUTING_REVIEW",LeadRoutingDispositionRecordedV1),
        branch("P0_04_RETRY_ASSIGNMENT_NOW",CommandEnvelope.Type.RECORD_ROUTING_DISPOSITION,"RETRY_ASSIGNMENT_NOW",LeadRoutingDispositionRecordedV1),
        branch("P0_04_REQUEST_SOURCE_INTAKE_STOP",CommandEnvelope.Type.RECORD_ROUTING_DISPOSITION,"REQUEST_SOURCE_INTAKE_STOP",SourceIntakeStopRequestedV1),
        branch("ACK_SOURCE_INTAKE_STOP_REQUEST",CommandEnvelope.Type.ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST,"SOURCE_INTAKE_STOP_REQUEST_ACKNOWLEDGED",SourceIntakeStopRequestAcknowledgedV1),
        branch("CONTACT_CONNECTED_VALID",CommandEnvelope.Type.RECORD_CONTACT_RESULT,"CONNECTED_VALID",LeadContactResultRecordedV1,OpportunityOpened),
        branch("CONTACT_NOT_CONNECTED_RETRY",CommandEnvelope.Type.RECORD_CONTACT_RESULT,"NOT_CONNECTED_RETRY",LeadContactResultRecordedV1),
        branch("CONTACT_NOT_CONNECTED_EXHAUSTED",CommandEnvelope.Type.RECORD_CONTACT_RESULT,"NOT_CONNECTED_EXHAUSTED",LeadContactRetryExhaustedV1),
        branch("CONTACT_SUSPECT_INVALID",CommandEnvelope.Type.RECORD_CONTACT_RESULT,"SUSPECT_INVALID",LeadContactResultRecordedV1),
        branch("REVIEW_CONFIRM_INVALID",CommandEnvelope.Type.REVIEW_LEAD_VALIDITY,"CONFIRM_INVALID",LeadValidityReviewedV1),
        branch("REVIEW_CLOSE_UNREACHED",CommandEnvelope.Type.REVIEW_LEAD_VALIDITY,"CLOSE_UNREACHED",LeadValidityReviewedV1),
        branch("REVIEW_REOPEN_CONTACT",CommandEnvelope.Type.REVIEW_LEAD_VALIDITY,"REOPEN_CONTACT",LeadValidityReviewedV1));
    private static final List<Branch> R2_BRANCHES=List.of(
        branch("R2_SUBMIT_TRANSFER",CommandEnvelope.Type.SUBMIT_TRANSFER,"RECORDED",TransferSubmittedV1),
        branch("R2_RESUBMIT_TRANSFER",CommandEnvelope.Type.RESUBMIT_TRANSFER,"RECORDED",TransferResubmittedV1),
        branch("R2_RECORD_TRANSFER_CONFLICT_REVIEW",CommandEnvelope.Type.RECORD_TRANSFER_CONFLICT_REVIEW,"RECORDED",TransferConflictReviewRecordedV1),
        branch("R2_RECORD_TRANSFER_INTAKE",CommandEnvelope.Type.RECORD_TRANSFER_INTAKE,"RECORDED",TransferIntakeRecordedV1),
        branch("R2_CLASSIFY_MATTER",CommandEnvelope.Type.CLASSIFY_MATTER,"RECORDED",MatterClassifiedV1),


        branch("R2_REQUEST_CONTRACT_RECEIPT_REVIEW",CommandEnvelope.Type.REQUEST_CONTRACT_RECEIPT_REVIEW,"RECORDED",ContractReceiptReviewRequestedV1),
        branch("R2_RECORD_CONTRACT_RECEIPT_REVIEW",CommandEnvelope.Type.RECORD_CONTRACT_RECEIPT_REVIEW,"RECORDED",ContractReceiptReviewRecordedV1),
        branch("R2_SUPPLEMENT_CONTRACT_RECEIPT",CommandEnvelope.Type.SUPPLEMENT_CONTRACT_RECEIPT,"RECORDED",ContractReceiptSupplementedV1),

        branch("R2_OPPORTUNITY_SUPERSEDED_TASK_REPAIRED",CommandEnvelope.Type.REPAIR_SUPERSEDED_OPPORTUNITY_TASK,"CANCELLED",OpportunitySupersededTaskRepairedV1),
        branch("R2_SOURCE_REQUEST_RESTORED",CommandEnvelope.Type.RESTORE_SOURCE_REQUEST_TASK,"RESTORED",SourceRequestTaskRestoredV1),
        branch("R2_SOURCE_REQUEST_END",CommandEnvelope.Type.RECORD_SOURCE_REQUEST_CONTINUATION,"END_LEAD",SourceRequestContinuationRecordedV1),
        branch("R2_SOURCE_REQUEST_ASSIGN",CommandEnvelope.Type.RECORD_SOURCE_REQUEST_CONTINUATION,"ASSIGN_SELECTED",SourceRequestContinuationRecordedV1),
        branch("R2_SOURCE_REQUEST_REVIEW",CommandEnvelope.Type.RECORD_SOURCE_REQUEST_CONTINUATION,"SCHEDULE_REVIEW",SourceRequestContinuationRecordedV1),
        branch("R2_END_CONTRACT_NEGOTIATION",CommandEnvelope.Type.END_CONTRACT_NEGOTIATION,"RECORDED",ContractNegotiationEndedV1),
        branch("R2_REQUEST_CONTRACT_TERMINATION_REVIEW",CommandEnvelope.Type.REQUEST_CONTRACT_TERMINATION_REVIEW,"RECORDED",ContractTerminationReviewRequestedV1),
        branch("R2_RECORD_CONTRACT_TERMINATION_REVIEW",CommandEnvelope.Type.RECORD_CONTRACT_TERMINATION_REVIEW,"RECORDED",ContractTerminationReviewRecordedV1),

        branch("R2_OPPORTUNITY_FOLLOWUP_ATTEMPT",CommandEnvelope.Type.RECORD_OPPORTUNITY_FOLLOWUP_ATTEMPT,"RECORDED",SalesFollowupAttemptRecordedV1),
        branch("R2_QUOTE_FOLLOWUP_ATTEMPT",CommandEnvelope.Type.RECORD_QUOTE_FOLLOWUP_ATTEMPT,"RECORDED",SalesFollowupAttemptRecordedV1),
        branch("R2_RETURN_CONTRACT_SIGNATURE_FOR_REVISION",CommandEnvelope.Type.RETURN_CONTRACT_SIGNATURE_FOR_REVISION,"RECORDED",ContractSignatureVerificationReturnedV1),
        branch("R2_SAVE_CONTRACT_SIGNATURE_DRAFT",CommandEnvelope.Type.SAVE_CONTRACT_SIGNATURE_DRAFT,"RECORDED",ContractSignatureDraftSavedV1),
        branch("R2_CONFIRM_CONTRACT_SIGNATURE_ARRANGEMENT",CommandEnvelope.Type.CONFIRM_CONTRACT_SIGNATURE_ARRANGEMENT,"RECORDED",ContractSignatureArrangementConfirmedV1),
        branch("R2_SUBMIT_CONTRACT_SIGNATURE",CommandEnvelope.Type.SUBMIT_CONTRACT_SIGNATURE,"RECORDED",ContractSignatureSubmittedV1),
        branch("R2_RECORD_CONTRACT_SIGNATURE_VERIFICATION",CommandEnvelope.Type.RECORD_CONTRACT_SIGNATURE_VERIFICATION,"RECORDED",ContractSignatureVerificationRecordedV1),
        branch("R2_VERIFY_CONTRACT_EXECUTION_CONDITIONS",CommandEnvelope.Type.VERIFY_CONTRACT_EXECUTION_CONDITIONS,"RECORDED",ContractExecutionConditionsVerifiedV1),
        branch("R2_ARCHIVE_CONTRACT_SIGNATURE",CommandEnvelope.Type.ARCHIVE_CONTRACT_SIGNATURE,"RECORDED",ContractSignatureArchivedV1),
        branch("R2_RETURN_CONTRACT_FOR_REVISION",CommandEnvelope.Type.RETURN_CONTRACT_FOR_REVISION,"RECORDED",ContractReturnedForRevisionV1),
        branch("R2_RECONCILE_CONTRACT_TERMINATION_REVIEW",CommandEnvelope.Type.RECONCILE_CONTRACT_PREPARATION,"TERMINATION_REVIEW",ContractTerminationReviewReconciledV1),
        branch("R2_RECONCILE_TRANSFER_WORKFLOW",CommandEnvelope.Type.RECONCILE_CONTRACT_PREPARATION,"TRANSFER",TransferWorkflowReconciledV1),
        branch("R2_RECONCILE_CONTRACT_PAYMENT",CommandEnvelope.Type.RECONCILE_CONTRACT_PREPARATION,"PAYMENT",ContractPaymentReconciledV1),
        branch("R2_RECONCILE_CONTRACT_EXECUTION",CommandEnvelope.Type.RECONCILE_CONTRACT_PREPARATION,"EXECUTION",ContractExecutionReconciledV1),
        branch("R2_RECONCILE_CONTRACT_SIGNATURE",CommandEnvelope.Type.RECONCILE_CONTRACT_PREPARATION,"SIGNATURE",ContractSignatureReconciledV1),
        branch("R2_RECONCILE_CONTRACT_PREPARATION",CommandEnvelope.Type.RECONCILE_CONTRACT_PREPARATION,"RECORDED",ContractPreparationReconciledV1),
        branch("R2_REQUEST_CONTRACT_PREPARATION",CommandEnvelope.Type.REQUEST_CONTRACT_PREPARATION,"RECORDED",ContractPreparationRequestedV1),
        branch("R2_RECORD_CONTRACT_PREPARATION_DECISION",CommandEnvelope.Type.RECORD_CONTRACT_PREPARATION_DECISION,"RECORDED",ContractPreparationDecisionRecordedV1),
        branch("R2_START_CONTRACT_PREPARATION",CommandEnvelope.Type.START_CONTRACT_PREPARATION,"RECORDED",ContractPreparationStartedV1),
        branch("R2_SAVE_CONTRACT_DRAFT",CommandEnvelope.Type.SAVE_CONTRACT_DRAFT,"RECORDED",ContractDraftSavedV1),
        branch("R2_FORM_CONTRACT",CommandEnvelope.Type.FORM_CONTRACT,"RECORDED",ContractFormedV1),
        branch("R2_REQUEST_CONTRACT_REVIEW",CommandEnvelope.Type.REQUEST_CONTRACT_REVIEW,"RECORDED",ContractReviewRequestedV1),
        branch("R2_RECORD_CONTRACT_REVIEW",CommandEnvelope.Type.RECORD_CONTRACT_REVIEW,"RECORDED",ContractReviewRecordedV1),
        branch("R2_REQUEST_CONTRACT_APPROVAL",CommandEnvelope.Type.REQUEST_CONTRACT_APPROVAL,"RECORDED",ContractApprovalRequestedV1),
        branch("R2_RECORD_CONTRACT_DECISION",CommandEnvelope.Type.RECORD_CONTRACT_DECISION,"RECORDED",ContractDecisionRecordedV1),

        branch("R2_END_QUOTE_NEGOTIATION",CommandEnvelope.Type.END_QUOTE_NEGOTIATION,"RECORDED",QuoteNegotiationEndedV1),
        branch("R2_SAVE_QUOTE_DRAFT",CommandEnvelope.Type.SAVE_QUOTE_DRAFT,"RECORDED",QuoteDraftSavedV1),
        branch("R2_START_QUOTE_PREPARATION",CommandEnvelope.Type.START_QUOTE_PREPARATION,"RECORDED",QuotePreparationStartedV1),
        branch("R2_FORM_QUOTE",CommandEnvelope.Type.FORM_QUOTE,"RECORDED",QuoteFormedV1),
        branch("R2_REQUEST_QUOTE_APPROVAL",CommandEnvelope.Type.REQUEST_QUOTE_APPROVAL,"RECORDED",QuoteApprovalRequestedV1),
        branch("R2_RECORD_QUOTE_DECISION",CommandEnvelope.Type.RECORD_QUOTE_DECISION,"RECORDED",QuoteDecisionRecordedV1),
        branch("R2_RECORD_QUOTE_DELIVERY",CommandEnvelope.Type.RECORD_QUOTE_DELIVERY,"RECORDED",QuoteDeliveredV1),
        branch("R2_RECORD_QUOTE_RESPONSE",CommandEnvelope.Type.RECORD_QUOTE_RESPONSE,"RECORDED",QuoteResponseRecordedV1),
        branch("R2_MATERIAL_UPLOAD_OPENED",CommandEnvelope.Type.OPEN_OPPORTUNITY_MATERIAL_UPLOAD,"OPENED",OpportunityMaterialUploadOpenedV1),
        branch("R2_MATERIAL_ACCEPTED",CommandEnvelope.Type.ACCEPT_OPPORTUNITY_MATERIAL,"ACCEPTED",OpportunityMaterialAcceptedV1),
        branch("R2_CUSTOMER_DRAFT_SAVED",CommandEnvelope.Type.SAVE_OPPORTUNITY_CUSTOMER_DRAFT,"SAVED",OpportunityCustomerDraftSavedV1),
        branch("R2_CUSTOMER_REQUIREMENTS_CONFIRMED",CommandEnvelope.Type.CONFIRM_OPPORTUNITY_CUSTOMER_REQUIREMENTS,"CONFIRMED",OpportunityCustomerRequirementsConfirmedV1),
        branch("R2_OPPORTUNITY_CLOSED",CommandEnvelope.Type.CLOSE_OPPORTUNITY,"CLOSED",OpportunityClosedV1),
        branch("R2_OWNER_EXCEPTION_OBSERVED",CommandEnvelope.Type.OBSERVE_OPPORTUNITY_OWNER_EXCEPTION,"OBSERVED",OpportunityOwnerExceptionObservedV1),
        branch("R2_OWNER_COORDINATION_RECORDED",CommandEnvelope.Type.RECORD_OPPORTUNITY_OWNER_COORDINATION,"COORDINATED",OpportunityOwnerCoordinationRecordedV1),
        branch("R2_OPPORTUNITY_RESPONSIBILITY_TRANSFERRED",CommandEnvelope.Type.TRANSFER_OPPORTUNITY_RESPONSIBILITY,"TRANSFERRED",OpportunityResponsibilityTransferredV1),
        branch("R2_OPPORTUNITY_INITIAL_TASK_ACTIVATED",CommandEnvelope.Type.ACTIVATE_INITIAL_OPPORTUNITY_TASK,"INITIAL_CREATED",OpportunityInitialTaskActivatedV1),
        branch("R2_OPPORTUNITY_DRAFT_CHANGED",CommandEnvelope.Type.SAVE_ACTION_DRAFT,"R2_CREATED_OR_CHANGED",OpportunityActionDraftSavedV1),
        branch("R2_OPPORTUNITY_PROGRESS_RECORDED",CommandEnvelope.Type.RECORD_OPPORTUNITY_PROGRESS,"PROGRESS_RECORDED",OpportunityProgressRecordedV1),
        branch("R2_SOURCE_REQUEST_REOPENED",CommandEnvelope.Type.REOPEN_DUE_SOURCE_REQUEST_TASKS,"WAITING_TO_OPEN",SourceRequestReviewReopenedV1),
        branch("R2_OPPORTUNITY_TASK_REOPENED",CommandEnvelope.Type.REOPEN_DUE_OPPORTUNITY_TASKS,"WAITING_TO_OPEN",OpportunityTaskReopenedV1));
    public static CommandHandler.Event contractEvent(CommandEnvelope.Type type){return switch(type){case REQUEST_CONTRACT_RECEIPT_REVIEW->ContractReceiptReviewRequestedV1;case RECORD_CONTRACT_RECEIPT_REVIEW->ContractReceiptReviewRecordedV1;case SUPPLEMENT_CONTRACT_RECEIPT->ContractReceiptSupplementedV1;case VERIFY_CONTRACT_EXECUTION_CONDITIONS->ContractExecutionConditionsVerifiedV1;case END_CONTRACT_NEGOTIATION->ContractNegotiationEndedV1;case REQUEST_CONTRACT_TERMINATION_REVIEW->ContractTerminationReviewRequestedV1;case RECORD_CONTRACT_TERMINATION_REVIEW->ContractTerminationReviewRecordedV1;case RETURN_CONTRACT_SIGNATURE_FOR_REVISION->ContractSignatureVerificationReturnedV1;case SAVE_CONTRACT_SIGNATURE_DRAFT->ContractSignatureDraftSavedV1;case CONFIRM_CONTRACT_SIGNATURE_ARRANGEMENT->ContractSignatureArrangementConfirmedV1;case SUBMIT_CONTRACT_SIGNATURE->ContractSignatureSubmittedV1;case RECORD_CONTRACT_SIGNATURE_VERIFICATION->ContractSignatureVerificationRecordedV1;case ARCHIVE_CONTRACT_SIGNATURE->ContractSignatureArchivedV1;case RETURN_CONTRACT_FOR_REVISION->ContractReturnedForRevisionV1;case REQUEST_CONTRACT_PREPARATION->ContractPreparationRequestedV1;case RECORD_CONTRACT_PREPARATION_DECISION->ContractPreparationDecisionRecordedV1;case START_CONTRACT_PREPARATION->ContractPreparationStartedV1;case SAVE_CONTRACT_DRAFT->ContractDraftSavedV1;case FORM_CONTRACT->ContractFormedV1;case REQUEST_CONTRACT_REVIEW->ContractReviewRequestedV1;case RECORD_CONTRACT_REVIEW->ContractReviewRecordedV1;case REQUEST_CONTRACT_APPROVAL->ContractApprovalRequestedV1;case RECORD_CONTRACT_DECISION->ContractDecisionRecordedV1;default->throw new IllegalArgumentException("Not a contract command");};}
    public static CommandHandler.Event quoteEvent(CommandEnvelope.Type type){return switch(type){case END_QUOTE_NEGOTIATION->QuoteNegotiationEndedV1;case START_QUOTE_PREPARATION->QuotePreparationStartedV1;case SAVE_QUOTE_DRAFT->QuoteDraftSavedV1;case FORM_QUOTE->QuoteFormedV1;case REQUEST_QUOTE_APPROVAL->QuoteApprovalRequestedV1;case RECORD_QUOTE_DECISION->QuoteDecisionRecordedV1;case RECORD_QUOTE_DELIVERY->QuoteDeliveredV1;case RECORD_QUOTE_RESPONSE->QuoteResponseRecordedV1;default->throw new IllegalArgumentException("Not a quote command");};}
    public static List<Branch> branches(){return BRANCHES;}
    public static List<Branch> r2Branches(){return R2_BRANCHES;}
    private final R1EventFacts facts;
    private R1EventFacts.OwnerException beforeOwnerException;
    private R1EventFacts.Task beforeTask;
    private R1EventFacts.Wait beforeWait;
    private Subject beforeCapture;
    private R1EventFacts.InitialResponsibility beforeInitial;
    private boolean beforeContactExists=true; // Absence must be observed for the locked Task.
    private boolean prepared;
    public R1EventPolicy(R1EventFacts facts){this.facts=facts;}

    /** Runtime calls once under root locks, before any Handler writes. Instance never crosses invocations. */
    public void beforeWork(Connection c,CommandEnvelope e,CommandHandler.Context context)throws SQLException {
        require(!prepared);prepared=true;
        if(facts==null)return; // A successful result still fails closed in validate.
        if(context.binding() instanceof CommandAuthorizationBinding.OwnerException b)beforeOwnerException=b.exception()!=null?facts.ownerException(c,e.actor().tenantId(),b.exception()):facts.activeOwnerException(c,e.actor().tenantId(),b.opportunity().id());
        if(context.scope().taskId()!=null)beforeTask=facts.task(c,e.actor().tenantId(),context.scope().taskId());
        if(e.type()==CommandEnvelope.Type.RECORD_CONTACT_RESULT && beforeTask!=null)beforeContactExists=facts.contactExistsForTask(c,e.actor().tenantId(),beforeTask.selector().id());
        if(context.binding() instanceof CommandAuthorizationBinding.OpportunityActivation b)beforeInitial=facts.initialOpportunityTask(c,e.actor().tenantId(),b.opportunity().id());
        if(e.type().recovery())beforeWait=facts.latestWait(c,e.actor().tenantId(),context.scope().taskId());
        if(context.binding() instanceof CommandAuthorizationBinding.Capture b)beforeCapture=facts.capturedLead(c,e.actor().tenantId(),b.sourceAccountCode(),b.sourceRecordKeyDigest());
    }
    public void validate(Connection c,CommandEnvelope e,CommandHandler.Context context,CommandHandler.Result result)throws SQLException {
        if(e.type()==CommandEnvelope.Type.REPAIR_SUPERSEDED_OPPORTUNITY_TASK){
            require(prepared&&facts!=null&&beforeTask!=null&&context.binding() instanceof CommandAuthorizationBinding.OpportunityRepair);
            var b=(CommandAuthorizationBinding.OpportunityRepair)context.binding();var after=facts.task(c,e.actor().tenantId(),b.task().id());
            require(beforeTask.selector().equals(b.task())&&beforeTask.purpose().equals("PROGRESS_OPPORTUNITY")&&Set.of("OPEN","WAITING").contains(beforeTask.state())&&beforeTask.owner().equals(b.owner())&&beforeTask.lead().equals(b.opportunity()));
            require(after!=null&&Objects.equals(beforeTask.draft(),after.draft())&&beforeTask.slaCode().equals(after.slaCode())&&beforeTask.slaSeconds()==after.slaSeconds()&&beforeTask.slaDue().equals(after.slaDue())&&facts.opportunityRepairResult(c,e,b,result.fact()));
            require(result.status()==CommandOutcome.Status.SUCCEEDED&&result.notifications().equals(List.of(new CommandHandler.Notification(OpportunitySupersededTaskRepairedV1,result.fact()))));return;
        }

        if(e.type()==CommandEnvelope.Type.RESTORE_SOURCE_REQUEST_TASK){
            require(prepared&&facts!=null&&beforeTask!=null&&context.binding() instanceof CommandAuthorizationBinding.SourceRepair);
            var b=(CommandAuthorizationBinding.SourceRepair)context.binding();var tenant=e.actor().tenantId();
            var original=facts.task(c,tenant,b.task().id());var next=facts.task(c,tenant,result.fact().id());var decision=facts.decision(c,tenant,b.decision().id());
            require(beforeTask.equals(original)&&original.selector().equals(b.task())&&original.purpose().equals("ACK_SOURCE_INTAKE_STOP_REQUEST")&&original.state().equals("DONE")&&b.decision().equals(original.completion()));
            require(decision!=null&&decision.selector().equals(b.decision())&&decision.taskId().equals(b.task().id())&&decision.subject().equals(b.lead())&&decision.version()==1&&decision.contract().equals("SOURCE_INTAKE_STOP_REQUEST_ACKNOWLEDGED")&&decision.code().equals("SOURCE_INTAKE_STOP_REQUEST_ACKNOWLEDGED"));
            require(next!=null&&next.selector().equals(result.fact())&&next.selector().revision()==0&&next.purpose().equals("RESOLVE_SOURCE_REQUEST")&&next.primaryCommand().equals("RECORD_SOURCE_REQUEST_CONTINUATION")&&next.lead().equals(b.lead())&&next.owner().equals(b.supervisor())&&next.state().equals("OPEN")&&next.completion()==null);
            require(result.status()==CommandOutcome.Status.SUCCEEDED&&result.notifications().equals(List.of(new CommandHandler.Notification(SourceRequestTaskRestoredV1,result.fact()))));return;
        }

        if(e.type()==CommandEnvelope.Type.RECONCILE_CONTRACT_PREPARATION){require(prepared&&facts!=null&&context.binding() instanceof CommandAuthorizationBinding.ContractRecovery);require(((CommandAuthorizationBinding.ContractRecovery)context.binding()).resultType().equals(result.fact().type())&&facts.contractRecoveryResult(c,e,(CommandAuthorizationBinding.ContractRecovery)context.binding(),result.fact(),result.status()==CommandOutcome.Status.SUCCEEDED));if(result.status()==CommandOutcome.Status.SUCCEEDED)require(result.notifications().equals(List.of(new CommandHandler.Notification(result.fact().type().equals("transfer.workflow")?TransferWorkflowReconciledV1:result.fact().type().equals("contract.payment_workflow")?ContractPaymentReconciledV1:result.fact().type().equals("contract.execution_workflow")?ContractExecutionReconciledV1:result.fact().type().equals("contract.termination_review_assignment")?ContractTerminationReviewReconciledV1:result.fact().type().equals("contract.signature_workflow")?ContractSignatureReconciledV1:ContractPreparationReconciledV1,result.fact()))));else require(result.status()==CommandOutcome.Status.NO_CHANGE&&result.notifications().isEmpty());return;}
        if(e.type().transfers()){
            require(prepared&&facts!=null&&context.binding() instanceof CommandAuthorizationBinding.Transfers);require(R1CommandPolicy.transferResultType(e.type()).equals(result.fact().type())&&facts.transferResult(c,e,(CommandAuthorizationBinding.Transfers)context.binding(),result.fact()));require(result.status()==CommandOutcome.Status.SUCCEEDED&&result.notifications().equals(List.of(new CommandHandler.Notification(transferEvent(e.type()),result.fact()))));return;
        }
        if(e.type().contracts()){
            require(prepared&&facts!=null&&context.binding() instanceof CommandAuthorizationBinding.Contracts);
            require(R1CommandPolicy.contractResultType(e.type()).equals(result.fact().type())&&facts.contractResult(c,e,(CommandAuthorizationBinding.Contracts)context.binding(),result.fact()));
            require(result.status()==CommandOutcome.Status.SUCCEEDED&&result.notifications().equals(List.of(new CommandHandler.Notification(contractEvent(e.type()),result.fact()))));return;
        }
        if(e.type().followupAttempts()){
            require(prepared&&facts!=null&&context.binding() instanceof CommandAuthorizationBinding.FollowupAttempt);
            require("opportunity.followup_attempt".equals(result.fact().type())&&facts.followupAttemptResult(c,e,(CommandAuthorizationBinding.FollowupAttempt)context.binding(),result.fact()));
            require(result.status()==CommandOutcome.Status.SUCCEEDED&&result.notifications().equals(List.of(new CommandHandler.Notification(SalesFollowupAttemptRecordedV1,result.fact()))));return;
        }
        if(e.type().quotes()){
            require(prepared&&facts!=null&&context.binding() instanceof CommandAuthorizationBinding.Quotes);
            require(R1CommandPolicy.quoteResultType(e.type()).equals(result.fact().type())&&facts.quoteResult(c,e,(CommandAuthorizationBinding.Quotes)context.binding(),result.fact()));
            require(result.status()==CommandOutcome.Status.SUCCEEDED&&result.notifications().equals(List.of(new CommandHandler.Notification(quoteEvent(e.type()),result.fact()))));return;
        }
        if(e.type().materials()){
            require(prepared&&facts!=null&&context.binding() instanceof CommandAuthorizationBinding.Materials);var b=(CommandAuthorizationBinding.Materials)context.binding();var m=facts.materialFact(c,e.actor().tenantId(),result.fact());boolean open=e.type()==CommandEnvelope.Type.OPEN_OPPORTUNITY_MATERIAL_UPLOAD;
            require(m!=null&&m.selector().equals(result.fact())&&m.opportunity().equals(b.opportunity())&&m.basis().equals(b.basis())&&m.owner().equals(e.actor().appointmentId())&&Objects.equals(m.confirmation(),b.confirmation())&&Objects.equals(m.previous(),b.previous())&&(open?m.upload().equals(result.fact()):m.upload().equals(b.upload())));
            require(result.status()==CommandOutcome.Status.SUCCEEDED&&result.notifications().equals(List.of(new CommandHandler.Notification(open?OpportunityMaterialUploadOpenedV1:OpportunityMaterialAcceptedV1,result.fact()))));return;
        }
        if(e.type().customerRequirements()){
            require(prepared&&facts!=null&&context.binding() instanceof CommandAuthorizationBinding.CustomerRequirements);var b=(CommandAuthorizationBinding.CustomerRequirements)context.binding();var m=facts.customerRequirements(c,e.actor().tenantId(),result.fact());
            boolean draft=e.type()==CommandEnvelope.Type.SAVE_OPPORTUNITY_CUSTOMER_DRAFT;require(m!=null&&m.opportunity().equals(b.opportunity())&&m.responsibility().equals(b.basis())&&m.owner().equals(e.actor().appointmentId())&&Objects.equals(m.previous(),draft?b.draft():b.confirmation())&&(draft?m.draft()==null:Objects.equals(m.draft(),b.draft())));
            require(result.status()==CommandOutcome.Status.SUCCEEDED&&result.notifications().equals(List.of(new CommandHandler.Notification(draft?OpportunityCustomerDraftSavedV1:OpportunityCustomerRequirementsConfirmedV1,result.fact()))));return;
        }
        if(e.type()==CommandEnvelope.Type.CLOSE_OPPORTUNITY){validateClosure(c,e,context,result);return;}
        if(e.type().ownerException()){validateOwnerException(c,e,context,result);return;}
        String expectedType=switch(e.type()) {
            case CAPTURE_LEAD,COMPLETE_LEAD_INGRESS -> "lead.lead";
            case SAVE_ACTION_DRAFT -> "responsibility.action_draft";
            case ACTIVATE_INITIAL_OPPORTUNITY_TASK,REOPEN_DUE_CONTACT_TASKS,REOPEN_DUE_ROUTING_REVIEW_TASKS,REOPEN_DUE_SOURCE_REQUEST_TASKS,REOPEN_DUE_OPPORTUNITY_TASKS -> "responsibility.task_occurrence";
            case ASSIGN_LEAD -> "lead.lead_assignment";
            case RECORD_CONTACT_RESULT -> "lead.lead_contact_result";
            case RECORD_OPPORTUNITY_PROGRESS -> "opportunity.opportunity_progress";
            default -> "responsibility.decision_record";
        };
        require(expectedType.equals(result.fact().type()));
        if(result.status()!=CommandOutcome.Status.SUCCEEDED){require(result.notifications().isEmpty());return;}
        require(prepared && facts!=null);
        var tenant=e.actor().tenantId();var receipt=result.fact();
        var task=context.scope().taskId()==null?null:facts.task(c,tenant,context.scope().taskId());
        if(!R1CommandPolicy.dedicated(e.type()))require(task!=null && task.lead().equals(context.authorization().subject()));
        var expectedSources=new EnumMap<CommandHandler.Event,Subject>(CommandHandler.Event.class);
        String outcome;
        switch(e.type()) {
            case ACTIVATE_INITIAL_OPPORTUNITY_TASK -> {
                require(context.binding() instanceof CommandAuthorizationBinding.OpportunityActivation);
                var b=(CommandAuthorizationBinding.OpportunityActivation)context.binding();
                var initial=facts.initialOpportunityTask(c,tenant,b.opportunity().id());var opening=facts.opportunity(c,tenant,b.opportunity().id());
                require(beforeInitial!=null&&beforeInitial.task()==null&&initial!=null&&initial.task()!=null&&opening!=null);
                var t=initial.task();require(receipt.equals(t.selector())&&receipt.revision()==0&&t.lead().equals(b.opportunity())&&t.owner().equals(opening.owner())
                        &&"OPEN".equals(t.state())&&"PROGRESS_OPPORTUNITY".equals(t.purpose())&&"RECORD_OPPORTUNITY_PROGRESS".equals(t.primaryCommand())
                        &&"R2_BUSINESS_4H_V1".equals(t.slaCode())&&t.slaSeconds()==14400&&t.draft()==null&&t.completion()==null
                        &&!initial.createdAt().isBefore(beforeInitial.observedAt())&&!initial.createdAt().isAfter(initial.observedAt())&&t.slaDue().isAfter(initial.createdAt()));
                outcome="INITIAL_CREATED";
            }
            case CAPTURE_LEAD -> {
                require(context.binding() instanceof CommandAuthorizationBinding.Capture);
                var b=(CommandAuthorizationBinding.Capture)context.binding();
                require(beforeCapture==null && receipt.equals(facts.capturedLead(c,tenant,b.sourceAccountCode(),b.sourceRecordKeyDigest())));
                outcome="CREATED";
            }
            case SAVE_ACTION_DRAFT -> {
                require(context.binding() instanceof CommandAuthorizationBinding.Draft && beforeTask!=null && task!=null);
                var b=(CommandAuthorizationBinding.Draft)context.binding();var draft=task.draft();
                require(beforeTask.selector().revision()==b.taskRevision());
                require(b.draftId()==null?beforeTask.draft()==null:beforeTask.draft()!=null && beforeTask.draft().selector().equals(new Subject("responsibility.action_draft",b.draftId(),b.draftRevision(),null)));
                require("OPEN".equals(beforeTask.state()) && sameTask(beforeTask,task) && beforeTask.selector().equals(task.selector()) && "OPEN".equals(task.state()) && task.completion()==null);
                require(draft!=null && receipt.equals(draft.selector()) && "DRAFT".equals(draft.state()) && draft.taskId().equals(b.taskId()) && draft.action().equals(b.actionCode().name()) && draft.version()==b.schemaVersion());
                require(draft.selector().revision()==(b.draftRevision()==null?0:CommandHandler.nextRevision(b.draftRevision())) && (b.draftId()==null || b.draftId().equals(draft.selector().id())));
                require(beforeTask.draft()==null || "DRAFT".equals(beforeTask.draft().state()) && beforeTask.draft().schema().equals(draft.schema()));
                outcome=b.actionCode()==CommandEnvelope.Type.RECORD_OPPORTUNITY_PROGRESS?"R2_CREATED_OR_CHANGED":"CREATED_OR_CHANGED";
            }
            case REOPEN_DUE_CONTACT_TASKS,REOPEN_DUE_ROUTING_REVIEW_TASKS,REOPEN_DUE_SOURCE_REQUEST_TASKS,REOPEN_DUE_OPPORTUNITY_TASKS -> {
                require(context.binding() instanceof CommandAuthorizationBinding.Recovery && beforeTask!=null && task!=null && beforeWait!=null);
                var b=(CommandAuthorizationBinding.Recovery)context.binding();
                boolean contact=e.type()==CommandEnvelope.Type.REOPEN_DUE_CONTACT_TASKS;
                boolean opportunity=e.type()==CommandEnvelope.Type.REOPEN_DUE_OPPORTUNITY_TASKS;
                boolean source=e.type()==CommandEnvelope.Type.REOPEN_DUE_SOURCE_REQUEST_TASKS;
                require("WAITING".equals(beforeTask.state()) && "OPEN".equals(task.state()) && sameTask(beforeTask,task) && task.completion()==null && Objects.equals(beforeTask.draft(),task.draft()));
                require(receipt.equals(task.selector()) && task.selector().revision()==CommandHandler.nextRevision(beforeTask.selector().revision()) && beforeTask.selector().revision()==b.taskRevision());
                boolean registeredWait=opportunity
                        ?("RECORD_QUOTE_REPLY".equals(task.purpose())?Set.of("R2_QUOTE_FOLLOWUP_V1","R2_QUOTE_HANDOFF_WAIT_V1","R2_SALES_ATTEMPT_WAIT_V1","R2_ATTEMPT_HANDOFF_WAIT_V1").contains(beforeWait.profile()):Set.of("R2_OPPORTUNITY_FOLLOWUP_V1","R2_OPPORTUNITY_HANDOFF_WAIT_V1","R2_SALES_ATTEMPT_WAIT_V1","R2_ATTEMPT_HANDOFF_WAIT_V1").contains(beforeWait.profile()))
                        :beforeWait.profile().equals(source?"R2_SOURCE_REQUEST_REVIEW_WAIT_V1":contact?"CONTACT_RETRY_V1":"R1_ROUTING_REVIEW_WAIT_V1");
                require((opportunity?Set.of("PROGRESS_OPPORTUNITY","RECORD_QUOTE_REPLY").contains(task.purpose()):task.purpose().equals(source?"RESOLVE_SOURCE_REQUEST":contact?"CONTACT_LEAD":"RESOLVE_LEAD_ROUTING_GAP")) && registeredWait && beforeWait.version()==1);
                require(beforeWait.selector().equals(new Subject("responsibility.wait_receipt",b.waitReceiptId(),null,b.waitReceiptHash())) && beforeWait.taskRevision()==b.taskRevision() && beforeWait.equals(facts.latestWait(c,tenant,b.taskId())));
                outcome="WAITING_TO_OPEN";
            }
            case COMPLETE_LEAD_INGRESS -> {
                completed(task,beforeTask,e,receipt);
                require(receipt.equals(facts.lead(c,tenant,task.lead().id())) && receipt.revision()==CommandHandler.nextRevision(task.lead().revision()));
                outcome="INGRESS_COMPLETED";
            }
            case ASSIGN_LEAD -> {
                completed(task,beforeTask,e,receipt);var assignment=facts.assignment(c,tenant,receipt.id());
                require(assignment!=null && receipt.equals(assignment.selector()) && receipt.revision()==0 && assignment.leadId().equals(task.lead().id()));
                require(context.scope().canonical().equals(CommandScope.task(tenant,e.type(),task.selector().id(),task.lead(),Map.of("selectedOwnerAppointmentId",assignment.owner())).canonical()));
                outcome="ASSIGNED";
            }
            case RECORD_OPPORTUNITY_PROGRESS -> {
                completed(task,beforeTask,e,receipt);var progress=facts.progress(c,tenant,receipt.id());
                require(progress!=null && receipt.equals(progress.selector()) && progress.opportunityId().equals(task.lead().id()) && progress.taskId().equals(task.selector().id()));
                require(context.scope().canonical().equals(CommandScope.opportunity(tenant,task.selector().id(),task.lead()).canonical()));
                var beforeDraft=beforeTask.draft();var draft=task.draft();
                require(beforeDraft!=null && "DRAFT".equals(beforeDraft.state()) && draft!=null && "CONFIRMED".equals(draft.state()));
                require(beforeDraft.selector().id().equals(draft.selector().id()) && draft.selector().revision()==CommandHandler.nextRevision(beforeDraft.selector().revision()));
                require(beforeDraft.taskId().equals(task.selector().id()) && draft.taskId().equals(beforeDraft.taskId()) && beforeDraft.action().equals(e.type().name()) && draft.action().equals(beforeDraft.action()));
                require("RecordOpportunityProgressV1".equals(beforeDraft.schema()) && draft.schema().equals(beforeDraft.schema()) && beforeDraft.version()==1 && draft.version()==1);
                outcome="PROGRESS_RECORDED";
            }
            case RECORD_CONTACT_RESULT -> {
                completed(task,beforeTask,e,receipt);var contact=facts.contact(c,tenant,receipt.id());
                require(contact!=null && receipt.equals(contact.selector()) && contact.taskId().equals(task.selector().id()) && contact.leadId().equals(task.lead().id()) && contact.contactNo()>=1 && contact.contactNo()<=9007199254740991L);
                var assignment=facts.assignment(c,tenant,contact.assignmentId());
                require(assignment!=null && assignment.leadId().equals(contact.leadId()) && assignment.owner().equals(task.owner()));
                require(context.scope().canonical().equals(CommandScope.task(tenant,e.type(),task.selector().id(),task.lead(),Map.of("leadAssignmentId",assignment.selector().id(),"leadAssignmentRevision",assignment.selector().revision())).canonical()));
                var opportunity=facts.opportunityForContact(c,tenant,contact.selector().id());
                outcome=contact.code();
                if("CONNECTED_VALID".equals(outcome)) {
                    require(!beforeContactExists);
                    require(opportunity!=null && opportunity.selector().revision()==0 && opportunity.leadId().equals(contact.leadId()) && opportunity.assignmentId().equals(contact.assignmentId()) && opportunity.contactId().equals(contact.selector().id()) && opportunity.owner().equals(assignment.owner()));
                    var beforeDraft=beforeTask.draft();var draft=task.draft();
                    require(beforeDraft!=null && "DRAFT".equals(beforeDraft.state()) && draft!=null && "CONFIRMED".equals(draft.state()));
                    require(beforeDraft.selector().id().equals(draft.selector().id()) && beforeDraft.selector().revision()!=null && draft.selector().revision()==CommandHandler.nextRevision(beforeDraft.selector().revision()));
                    expectedSources.put(OpportunityOpened,opportunity.selector());
                } else {
                    require(opportunity==null);
                    if("NOT_CONNECTED".equals(outcome))outcome=contact.contactNo()>=3?"NOT_CONNECTED_EXHAUSTED":"NOT_CONNECTED_RETRY";
                }
            }
            default -> {
                completed(task,beforeTask,e,receipt);var decision=facts.decision(c,tenant,receipt.id());
                require(decision!=null && receipt.equals(decision.selector()) && decision.taskId().equals(task.selector().id()) && decision.subject().equals(task.lead()) && decision.version()==1);
                String contract=switch(e.type()) {
                    case RESOLVE_DUPLICATE_LEAD -> "LEAD_DUPLICATE_RESOLUTION";
                    case RECORD_SOURCE_REQUEST_CONTINUATION -> "SOURCE_REQUEST_CONTINUATION";
                    case RECORD_ROUTING_DISPOSITION -> "LEAD_ROUTING_DISPOSITION";
                    case ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST -> "SOURCE_INTAKE_STOP_REQUEST_ACKNOWLEDGED";
                    case REVIEW_LEAD_VALIDITY -> "LEAD_VALIDITY_REVIEW";
                    default -> throw violation();
                };
                require(contract.equals(decision.contract()));outcome=decision.code();
                if(e.type()==CommandEnvelope.Type.REVIEW_LEAD_VALIDITY){
                    var trigger=facts.reviewTrigger(c,tenant,task.selector().id());
                    require(trigger!=null&&e.payload() instanceof Map<?,?>);
                    var payload=(Map<?,?>)e.payload();require(trigger.id().toString().equals(payload.get("triggeringContactResultId"))&&trigger.hash().equals(payload.get("triggeringContactResultHash")));
                }
            }
        }
        String branchOutcome=outcome;
        var branch=java.util.stream.Stream.concat(BRANCHES.stream(),R2_BRANCHES.stream()).filter(b->b.command()==e.type() && b.outcome().equals(branchOutcome)).findFirst().orElseThrow(R1EventPolicy::violation);
        for(var event:branch.events())expectedSources.putIfAbsent(event,receipt);
        require(result.notifications().size()==expectedSources.size());
        var seen=EnumSet.noneOf(CommandHandler.Event.class);
        for(var n:result.notifications()) {
            require(seen.add(n.event()) && n.sourceFact().equals(expectedSources.get(n.event())) && n.event().sourceFactType().equals(n.sourceFact().type()));
            require(n.event().sourceSelector().startsWith("hash:")?n.sourceFact().hash()!=null:n.sourceFact().revision()!=null);
        }
        require(seen.equals(branch.events()));
    }
    private void validateClosure(Connection c,CommandEnvelope e,CommandHandler.Context context,CommandHandler.Result result)throws SQLException {
        require(prepared&&facts!=null&&context.binding() instanceof CommandAuthorizationBinding.OpportunityClosure);
        var binding=(CommandAuthorizationBinding.OpportunityClosure)context.binding();var closure=facts.opportunityClosure(c,e.actor().tenantId(),result.fact());
        require(result.status()==CommandOutcome.Status.SUCCEEDED&&closure!=null&&closure.selector().equals(result.fact())&&closure.opportunity().equals(binding.opportunity())&&closure.responsibility().equals(binding.basis())&&Objects.equals(closure.task(),binding.task())&&Objects.equals(closure.waitReceipt(),binding.waitReceipt())&&closure.actor().equals(e.actor().appointmentId()));
        var opening=facts.opportunity(c,e.actor().tenantId(),binding.opportunity().id());require(opening!=null&&opening.selector().revision()==CommandHandler.nextRevision(binding.opportunity().revision()));
        if(binding.task()!=null){var task=facts.task(c,e.actor().tenantId(),binding.task().id());require(task!=null&&"CANCELLED".equals(task.state())&&task.completion()==null&&task.selector().revision()==CommandHandler.nextRevision(binding.task().revision())&&result.fact().equals(facts.opportunityTaskCancellation(c,e.actor().tenantId(),binding.task().id())));}
        require(result.notifications().equals(List.of(new CommandHandler.Notification(OpportunityClosedV1,result.fact()))));
    }
    private void validateOwnerException(Connection c,CommandEnvelope e,CommandHandler.Context context,CommandHandler.Result result)throws SQLException {
        require(prepared&&facts!=null&&context.binding() instanceof CommandAuthorizationBinding.OwnerException);var b=(CommandAuthorizationBinding.OwnerException)context.binding();
        if(result.status()==CommandOutcome.Status.NO_CHANGE){require(result.notifications().isEmpty());require(e.type()==CommandEnvelope.Type.OBSERVE_OPPORTUNITY_OWNER_EXCEPTION?beforeOwnerException==null&&"audit.audit_entry".equals(result.fact().type())&&result.fact().hash()!=null:beforeOwnerException!=null&&beforeOwnerException.selector().equals(result.fact()));return;}
        require("opportunity.owner_exception".equals(result.fact().type()));var after=facts.ownerException(c,e.actor().tenantId(),result.fact());require(after!=null&&(after.opportunity().equals(b.opportunity())||e.type()==CommandEnvelope.Type.OBSERVE_OPPORTUNITY_OWNER_EXCEPTION&&"NO_LONGER_APPLICABLE".equals(after.state())&&b.opportunity().equals(after.resolution())));
        CommandHandler.Event event;
        if(e.type()==CommandEnvelope.Type.OBSERVE_OPPORTUNITY_OWNER_EXCEPTION){require(beforeOwnerException==null?after.selector().revision()==0:after.selector().id().equals(beforeOwnerException.selector().id())&&after.selector().revision()==CommandHandler.nextRevision(beforeOwnerException.selector().revision()));event=OpportunityOwnerExceptionObservedV1;}
        else {
            require(beforeOwnerException!=null&&beforeOwnerException.selector().equals(b.exception())&&after.selector().id().equals(b.exception().id())&&after.selector().revision()==CommandHandler.nextRevision(b.exception().revision()));
            require(after.disposition()!=null&&after.decidedException().equals(b.exception())&&after.actor().equals(e.actor().appointmentId()));var p=(Map<?,?>)e.payload();require(after.reason().equals(p.get("reason")));
            if(e.type()==CommandEnvelope.Type.RECORD_OPPORTUNITY_OWNER_COORDINATION){require("COORDINATION".equals(after.dispositionKind())&&"COORDINATING".equals(after.state())&&after.basis().equals(beforeOwnerException.basis())&&Objects.equals(after.task(),beforeOwnerException.task())&&Objects.equals(after.waitReceipt(),beforeOwnerException.waitReceipt())&&after.reviewDueAt().equals(java.time.Instant.parse((String)p.get("reviewDueAt")))&&after.resolution()==null);event=OpportunityOwnerCoordinationRecordedV1;}
            else {
                require("TRANSFER".equals(after.dispositionKind())&&"RESOLVED".equals(after.state())&&after.resolution()!=null&&after.resolution().equals(after.basis())&&"opportunity.responsibility_handoff".equals(after.basis().type())&&after.owner().equals(UUID.fromString((String)p.get("receiverAppointmentId")))&&after.owner().equals(after.receiver()));
                var next=facts.task(c,e.actor().tenantId(),after.task().id());require(next!=null&&next.selector().equals(after.task())&&next.owner().equals(after.owner())&&next.draft()==null&&next.completion()==null&&("OPEN".equals(next.state())||"WAITING".equals(next.state())));
                if(beforeOwnerException.task()!=null){var old=facts.task(c,e.actor().tenantId(),beforeOwnerException.task().id());require(old!=null&&"CANCELLED".equals(old.state())&&old.completion()==null&&after.basis().equals(facts.opportunityTaskCancellation(c,e.actor().tenantId(),old.selector().id()))&&old.purpose().equals(next.purpose())&&old.primaryCommand().equals(next.primaryCommand())&&old.slaCode().equals(next.slaCode())&&old.slaSeconds()==next.slaSeconds()&&old.slaDue().equals(next.slaDue())&&old.selector().revision()==CommandHandler.nextRevision(beforeOwnerException.task().revision()));}
                if(beforeOwnerException.waitReceipt()==null)require("OPEN".equals(next.state())&&after.waitReceipt()==null);
                else {var oldWait=facts.latestWait(c,e.actor().tenantId(),beforeOwnerException.task().id());var newWait=facts.latestWait(c,e.actor().tenantId(),next.selector().id());require("WAITING".equals(next.state())&&oldWait!=null&&newWait!=null&&oldWait.selector().equals(beforeOwnerException.waitReceipt())&&newWait.selector().equals(after.waitReceipt())&&(Set.of("R2_SALES_ATTEMPT_WAIT_V1","R2_ATTEMPT_HANDOFF_WAIT_V1").contains(oldWait.profile())?"R2_ATTEMPT_HANDOFF_WAIT_V1":"RECORD_QUOTE_REPLY".equals(next.purpose())?"R2_QUOTE_HANDOFF_WAIT_V1":"R2_OPPORTUNITY_HANDOFF_WAIT_V1").equals(newWait.profile())&&oldWait.resumeDue().equals(newWait.resumeDue()));}
                event=OpportunityResponsibilityTransferredV1;
            }
        }
        require(result.notifications().equals(List.of(new CommandHandler.Notification(event,result.fact()))));
    }
    private static void completed(R1EventFacts.Task task,R1EventFacts.Task before,CommandEnvelope e,Subject receipt)throws SQLException {
        require(task!=null && before!=null && "OPEN".equals(before.state()) && "DONE".equals(task.state()) && e.type().name().equals(task.primaryCommand()) && receipt.equals(task.completion()) && sameTask(before,task) && task.selector().revision()==CommandHandler.nextRevision(before.selector().revision()));
        require(task.owner().equals(e.actor().onBehalfAppointmentId()==null?e.actor().appointmentId():e.actor().onBehalfAppointmentId()));
    }
    private static boolean sameTask(R1EventFacts.Task before,R1EventFacts.Task after) {
        return before.selector().id().equals(after.selector().id()) && before.lead().equals(after.lead()) && before.owner().equals(after.owner()) && before.purpose().equals(after.purpose()) && before.primaryCommand().equals(after.primaryCommand()) && before.slaCode().equals(after.slaCode()) && before.slaSeconds()==after.slaSeconds() && before.slaDue().equals(after.slaDue());
    }
    private static void require(boolean condition)throws SQLException {if(!condition)throw violation();}
    private static SQLException violation(){return new SQLException("R1 persisted event contract violation","22000");}
}
