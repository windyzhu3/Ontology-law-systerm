package io.github.windyzhu3.ontologylaw.responsibility;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.time.*;
import java.util.*;

public interface TaskFactory {
    enum Type {
        CLASSIFY_MATTER("CLASSIFY_MATTER","OPPORTUNITY_OWNER","MATTER_CLASSIFY","ClassifyMatterV1","transfer.classification"),
        ACCEPT_TRANSFER("RECORD_TRANSFER_INTAKE","OPPORTUNITY_OWNER","TRANSFER_ACCEPT","RecordTransferIntakeV1","transfer.intake"),
        SUPPLEMENT_TRANSFER("RESUBMIT_TRANSFER","OPPORTUNITY_OWNER","TRANSFER_SUBMIT","ResubmitTransferV1","transfer.submission"),
        PREPARE_TRANSFER("SUBMIT_TRANSFER","OPPORTUNITY_OWNER","TRANSFER_SUBMIT","SubmitTransferV1","transfer.submission"),
        REVIEW_TRANSFER("RECORD_TRANSFER_CONFLICT_REVIEW","OPPORTUNITY_OWNER","TRANSFER_REVIEW","RecordTransferConflictReviewV1","transfer.review"),
        CHECK_CONTRACT_RECEIPT("RECORD_CONTRACT_RECEIPT_REVIEW","OPPORTUNITY_OWNER","PAYMENT_CONFIRM","RecordContractReceiptReviewV1","contract.payment_review"),
        SUPPLEMENT_CONTRACT_RECEIPT("SUPPLEMENT_CONTRACT_RECEIPT","OPPORTUNITY_OWNER","PAYMENT_SUBMIT","SupplementContractReceiptV1","contract.payment_review"),
        CHECK_CONTRACT_EXECUTION("VERIFY_CONTRACT_EXECUTION_CONDITIONS","OPPORTUNITY_OWNER","CONTRACT_EXECUTION_VERIFY","VerifyContractExecutionConditionsV1","contract.execution_verification"),
        ARRANGE_CONTRACT_SIGNATURE("CONFIRM_CONTRACT_SIGNATURE_ARRANGEMENT","OPPORTUNITY_OWNER","CONTRACT_PREPARE","ConfirmContractSignatureArrangementV1","contract.signature_arrangement"),
        COLLECT_CONTRACT_SIGNATURE("SUBMIT_CONTRACT_SIGNATURE","OPPORTUNITY_OWNER","CONTRACT_PREPARE","SubmitContractSignatureV1","contract.signature_submission"),
        VERIFY_CONTRACT_SIGNATURE("RECORD_CONTRACT_SIGNATURE_VERIFICATION","OPPORTUNITY_OWNER","CONTRACT_SIGNATURE_VERIFY","RecordContractSignatureVerificationV1","contract.signature_verification"),
        ARCHIVE_CONTRACT_SIGNATURE("ARCHIVE_CONTRACT_SIGNATURE","OPPORTUNITY_OWNER","CONTRACT_SIGNATURE_VERIFY","ArchiveContractSignatureV1","contract.signature_archive"),

        RESOLVE_LEAD_DUPLICATE("RESOLVE_DUPLICATE_LEAD","SOURCE_INTAKE_OWNER","LEAD_INGRESS_RESOLVE","ResolveDuplicateLeadV1","responsibility.decision_record"),
        COMPLETE_LEAD_INGRESS("COMPLETE_LEAD_INGRESS","SOURCE_INTAKE_OWNER","LEAD_INGRESS_COMPLETE","CompleteLeadIngressV1","lead.lead"),
        ASSIGN_LEAD("ASSIGN_LEAD","ROUTING_SUPERVISOR","LEAD_ASSIGN","AssignLeadV1","lead.lead_assignment"),
        RESOLVE_SOURCE_REQUEST("RECORD_SOURCE_REQUEST_CONTINUATION","ROUTING_SUPERVISOR","LEAD_ROUTING_DECIDE","RecordSourceRequestContinuationV1","responsibility.decision_record"),
        RESOLVE_LEAD_ROUTING_GAP("RECORD_ROUTING_DISPOSITION","ROUTING_SUPERVISOR","LEAD_ROUTING_DECIDE","RecordRoutingDispositionV1","responsibility.decision_record"),
        ACK_SOURCE_INTAKE_STOP_REQUEST("ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST","SOURCE_INTAKE_OWNER","SOURCE_INTAKE_REQUEST_ACK","AcknowledgeSourceIntakeStopRequestV1","responsibility.decision_record"),
        CONTACT_LEAD("RECORD_CONTACT_RESULT","ASSIGNMENT_OWNER","SALES_CONTACT_OWNER","RecordContactResultV1","lead.lead_contact_result"),
        REVIEW_LEAD_VALIDITY("REVIEW_LEAD_VALIDITY","ROUTING_SUPERVISOR","LEAD_VALIDITY_REVIEW","ReviewLeadValidityV1","responsibility.decision_record"),
        PROGRESS_OPPORTUNITY("RECORD_OPPORTUNITY_PROGRESS","OPPORTUNITY_OWNER","SALES_OPPORTUNITY_OWNER","RecordOpportunityProgressV1","opportunity.opportunity_progress"),
        PREPARE_QUOTE("FORM_QUOTE","OPPORTUNITY_OWNER","QUOTE_PREPARE","FormQuoteV1","opportunity.quote_revision"),
        SUBMIT_QUOTE_APPROVAL("REQUEST_QUOTE_APPROVAL","OPPORTUNITY_OWNER","QUOTE_PREPARE","RequestQuoteApprovalV1","opportunity.quote_approval_request"),
        APPROVE_QUOTE("RECORD_QUOTE_DECISION","OPPORTUNITY_OWNER","QUOTE_APPROVE","RecordQuoteDecisionV1","opportunity.quote_approval_decision"),
        DELIVER_QUOTE("RECORD_QUOTE_DELIVERY","OPPORTUNITY_OWNER","QUOTE_DELIVER","RecordQuoteDeliveryV1","opportunity.quote_issue"),
        RECORD_QUOTE_REPLY("RECORD_QUOTE_RESPONSE","OPPORTUNITY_OWNER","QUOTE_RESPONSE","RecordQuoteResponseV1","opportunity.quote_response"),
        RESOLVE_QUOTE_AUTHORITY("REQUEST_QUOTE_APPROVAL","OPPORTUNITY_OWNER","QUOTE_PREPARE","RequestQuoteApprovalV1","opportunity.quote_approval_request"),
        REQUEST_CONTRACT_PREPARATION("REQUEST_CONTRACT_PREPARATION","OPPORTUNITY_OWNER","CONTRACT_PREPARE","RequestContractPreparationV1","contract.preparation_request"),
        DECIDE_CONTRACT_PREPARATION("RECORD_CONTRACT_PREPARATION_DECISION","OPPORTUNITY_OWNER","CONTRACT_PREPARATION_DECIDE","RecordContractPreparationDecisionV1","contract.preparation_decision"),
        PREPARE_CONTRACT("FORM_CONTRACT","OPPORTUNITY_OWNER","CONTRACT_PREPARE","FormContractV1","contract.contract_revision"),
        SUBMIT_CONTRACT_REVIEW("REQUEST_CONTRACT_REVIEW","OPPORTUNITY_OWNER","CONTRACT_PREPARE","RequestContractReviewV1","contract.revision_review_request"),
        REVIEW_CONTRACT("RECORD_CONTRACT_REVIEW","OPPORTUNITY_OWNER","CONTRACT_REVIEW","RecordContractReviewV1","contract.revision_review_decision"),
        SUBMIT_CONTRACT_APPROVAL("REQUEST_CONTRACT_APPROVAL","OPPORTUNITY_OWNER","CONTRACT_PREPARE","RequestContractApprovalV1","contract.revision_approval_request"),
        APPROVE_CONTRACT("RECORD_CONTRACT_DECISION","OPPORTUNITY_OWNER","CONTRACT_APPROVE","RecordContractDecisionV1","contract.revision_approval_decision"),
        SUPPLEMENT_CONTRACT_REVIEW("REQUEST_CONTRACT_REVIEW","OPPORTUNITY_OWNER","CONTRACT_PREPARE","RequestContractReviewV1","contract.revision_review_request"),
        REVIEW_CONTRACT_TERMINATION("RECORD_CONTRACT_TERMINATION_REVIEW","OPPORTUNITY_OWNER","CONTRACT_TERMINATION_REVIEW","RecordContractTerminationReviewV1","contract.negotiation_disposition");
        public final String command,slot,authority,schema,completionType;
        Type(String command,String slot,String authority,String schema,String completionType){this.command=command;this.slot=slot;this.authority=authority;this.schema=schema;this.completionType=completionType;}
        public long slaSeconds(){return this==CONTACT_LEAD?1800:this==ACK_SOURCE_INTAKE_STOP_REQUEST?32400:14400;}
        public String slaCode(){return this==CONTACT_LEAD?"R1_CONTACT_30M_V1":this==ACK_SOURCE_INTAKE_STOP_REQUEST?"R1_BUSINESS_1D_V1":this==RESOLVE_SOURCE_REQUEST||this==PROGRESS_OPPORTUNITY||isContract()||isTransfer()?"R2_BUSINESS_4H_V1":"R1_BUSINESS_4H_V1";}
        public boolean isTransfer(){return this==PREPARE_TRANSFER||this==REVIEW_TRANSFER||this==ACCEPT_TRANSFER||this==SUPPLEMENT_TRANSFER||this==CLASSIFY_MATTER;}
        public boolean isContract(){return switch(this){case CHECK_CONTRACT_RECEIPT,SUPPLEMENT_CONTRACT_RECEIPT,CHECK_CONTRACT_EXECUTION,REVIEW_CONTRACT_TERMINATION,ARRANGE_CONTRACT_SIGNATURE,COLLECT_CONTRACT_SIGNATURE,VERIFY_CONTRACT_SIGNATURE,ARCHIVE_CONTRACT_SIGNATURE,REQUEST_CONTRACT_PREPARATION,DECIDE_CONTRACT_PREPARATION,PREPARE_CONTRACT,SUBMIT_CONTRACT_REVIEW,REVIEW_CONTRACT,SUBMIT_CONTRACT_APPROVAL,APPROVE_CONTRACT,SUPPLEMENT_CONTRACT_REVIEW->true;default->false;};}
        public boolean independentDecisionOwner(){return switch(this){case CLASSIFY_MATTER,ACCEPT_TRANSFER,REVIEW_TRANSFER,CHECK_CONTRACT_RECEIPT,REVIEW_CONTRACT_TERMINATION,VERIFY_CONTRACT_SIGNATURE,ARCHIVE_CONTRACT_SIGNATURE,APPROVE_QUOTE,RESOLVE_QUOTE_AUTHORITY,DECIDE_CONTRACT_PREPARATION,REVIEW_CONTRACT,APPROVE_CONTRACT->true;default->false;};}
        public String subjectType(){return switch(this){case RESOLVE_SOURCE_REQUEST,RESOLVE_LEAD_DUPLICATE,COMPLETE_LEAD_INGRESS,ASSIGN_LEAD,RESOLVE_LEAD_ROUTING_GAP,ACK_SOURCE_INTAKE_STOP_REQUEST,CONTACT_LEAD,REVIEW_LEAD_VALIDITY->"lead.lead";case CLASSIFY_MATTER,ACCEPT_TRANSFER,SUPPLEMENT_TRANSFER,PREPARE_TRANSFER,REVIEW_TRANSFER,CHECK_CONTRACT_RECEIPT,SUPPLEMENT_CONTRACT_RECEIPT,CHECK_CONTRACT_EXECUTION,REVIEW_CONTRACT_TERMINATION,ARRANGE_CONTRACT_SIGNATURE,COLLECT_CONTRACT_SIGNATURE,VERIFY_CONTRACT_SIGNATURE,ARCHIVE_CONTRACT_SIGNATURE,PROGRESS_OPPORTUNITY,PREPARE_QUOTE,SUBMIT_QUOTE_APPROVAL,APPROVE_QUOTE,DELIVER_QUOTE,RECORD_QUOTE_REPLY,RESOLVE_QUOTE_AUTHORITY,REQUEST_CONTRACT_PREPARATION,DECIDE_CONTRACT_PREPARATION,PREPARE_CONTRACT,SUBMIT_CONTRACT_REVIEW,REVIEW_CONTRACT,SUBMIT_CONTRACT_APPROVAL,APPROVE_CONTRACT,SUPPLEMENT_CONTRACT_REVIEW->"opportunity.opportunity";};}
        public String publicCompletionType(){return switch(this){case PREPARE_TRANSFER,SUPPLEMENT_TRANSFER->"TRANSFER_SUBMISSION";case ACCEPT_TRANSFER->"TRANSFER_INTAKE";case CLASSIFY_MATTER->"MATTER_CLASSIFICATION";case REVIEW_TRANSFER->"TRANSFER_CONFLICT_REVIEW";case CHECK_CONTRACT_RECEIPT,SUPPLEMENT_CONTRACT_RECEIPT->"CONTRACT_PAYMENT_REVIEW";case CHECK_CONTRACT_EXECUTION->"CONTRACT_EXECUTION_VERIFICATION";case REVIEW_CONTRACT_TERMINATION->"CONTRACT_NEGOTIATION_DISPOSITION";case ARRANGE_CONTRACT_SIGNATURE->"CONTRACT_SIGNATURE_ARRANGEMENT";case COLLECT_CONTRACT_SIGNATURE->"CONTRACT_SIGNATURE_SUBMISSION";case VERIFY_CONTRACT_SIGNATURE->"CONTRACT_SIGNATURE_VERIFICATION";case ARCHIVE_CONTRACT_SIGNATURE->"CONTRACT_SIGNATURE_ARCHIVE";case REQUEST_CONTRACT_PREPARATION->"CONTRACT_PREPARATION_REQUEST";case DECIDE_CONTRACT_PREPARATION->"CONTRACT_PREPARATION_DECISION";case PREPARE_CONTRACT->"CONTRACT_REVISION";case SUBMIT_CONTRACT_REVIEW,SUPPLEMENT_CONTRACT_REVIEW->"CONTRACT_REVIEW_REQUEST";case REVIEW_CONTRACT->"CONTRACT_REVIEW_DECISION";case SUBMIT_CONTRACT_APPROVAL->"CONTRACT_APPROVAL_REQUEST";case APPROVE_CONTRACT->"CONTRACT_APPROVAL_DECISION";default->completionType.substring(completionType.indexOf('.')+1).toUpperCase(Locale.ROOT);};}
    }
    record Task(Subject selector,UUID owner,Type type,Subject subject,String state,Instant createdAt,Subject completion) {
        /** Compatibility alias for the existing Lead handlers. New Owner code uses subject(). */
        public Subject lead(){return subject;}
    }
    record HandoffResult(Task task,Instant originalDueAt,Subject originalWait,Subject newWait) {}
    HandoffResult handoffOpportunityTask(Connection c,UUID tenant,Subject handoff,Subject opportunity,Subject expectedBasis,Subject expectedTask,Subject expectedWait,UUID newOwner,UUID newTaskId,UUID actor,ZoneId zone)throws SQLException;
    Task read(Connection c,UUID tenant,UUID id) throws SQLException;
    List<Task> activeForLead(Connection c,UUID tenant,Subject lead) throws SQLException;
    void lock(Connection c,UUID tenant,UUID id) throws SQLException;
    Instant now(Connection c) throws SQLException;
    Task reopen(Connection c,UUID tenant,Task task) throws SQLException;
    /** Trusted command composition; exact due wait and completed predecessor, no task creation or SLA reset. */
    Task reopenOpportunityFollowup(Connection c,UUID tenant,Subject expectedTask,Subject opportunity,UUID owner,Subject wait,Subject progress,Instant due) throws SQLException;
    Task reopenFollowupAttempt(Connection c,UUID tenant,Subject expectedTask,Subject opportunity,UUID owner,Subject wait,Subject attempt,Instant due) throws SQLException;
    Task create(Connection c,UUID tenant,Type type,UUID owner,Subject lead,ZoneId zone,Instant now) throws SQLException;
    /** Contract takeover inherits the exact prior business deadline; prior must have just completed or cancelled. */
    Task currentTask(Connection c,UUID tenant,UUID originalId)throws SQLException;
    Task createContractTakingOver(Connection c,UUID tenant,Type type,UUID owner,Subject opportunity,Task prior,ZoneId zone,Instant now)throws SQLException;
    default Task createSignatureTask(Connection c,UUID tenant,Type type,UUID owner,Subject opportunity,ZoneId zone,Instant now,Instant dueAt)throws SQLException{throw new UnsupportedOperationException("Signature deadline port required");}
    /** Trusted Owner use only; serializes the initial identity and returns existing tasks in every state. */
    default Task createTransferTask(Connection c,UUID tenant,Type type,UUID owner,Subject opportunity,ZoneId zone,Instant now,Instant due)throws SQLException{throw new UnsupportedOperationException("Transfer task port required");}
    default void cancelTransferTask(Connection c,UUID tenant,Task task,Instant now)throws SQLException{throw new UnsupportedOperationException("Transfer cancellation port required");}
    Task createInitialOpportunity(Connection c,UUID tenant,UUID owner,Subject opportunity,ZoneId zone,Instant now) throws SQLException;
    /** Trusted Owner continuation: exact completed predecessor, one distinct future responsibility. */
    Task arrangeFollowupAttempt(Connection c,UUID tenant,Task task,Subject fact,ZoneId zone,Instant now,Instant due)throws SQLException;
    Task createOpportunityFollowup(Connection c,UUID tenant,Task predecessor,Subject progress,ZoneId zone,Instant now,Instant due) throws SQLException;
    Task createContactRetry(Connection c,UUID tenant,UUID owner,Subject lead,ZoneId zone,Instant now,Instant resume) throws SQLException;
    void cancelForContract(Connection c,UUID tenant,Task task,String reason,Instant now)throws SQLException;
    void cancelForQuote(Connection c,UUID tenant,Task task,String reason,Instant now)throws SQLException;
    void cancelForQuoteTermination(Connection c,UUID tenant,Task task,Subject termination,Instant now)throws SQLException;
    void cancelForContractNegotiation(Connection c,UUID tenant,Task task,Subject disposition,Instant now)throws SQLException;
    Task createTerminationReview(Connection c,UUID tenant,UUID owner,Subject opportunity,Instant now,Instant due)throws SQLException;
    Task resumeAfterContractNegotiation(Connection c,UUID tenant,Task prior,Subject disposition,UUID cancelledMember,Instant now)throws SQLException;
    void complete(Connection c,UUID tenant,Task task,Subject fact,Instant now) throws SQLException;
    Subject decision(Connection c,UUID tenant,Task task,UUID actor,String contract,String decision,String rationale,Map<String,Object> digestValues,Instant now) throws SQLException;
    Subject waitForQuoteReply(Connection c,UUID tenant,Task task,UUID actor,Instant due,Instant now,Subject response) throws SQLException;
    /** Indefinite exact-version receipt gate; never changes the original SLA. */
    default Task waitForContractReceipt(Connection c,UUID tenant,Task task,UUID actor,Instant now,Subject version)throws SQLException{throw new UnsupportedOperationException("Exact contract receipt wait port required");}
    default Task resumeContractReceipt(Connection c,UUID tenant,Task task,Subject version)throws SQLException{throw new UnsupportedOperationException("Exact contract receipt resumption port required");}
    Subject waitUntil(Connection c,UUID tenant,Task task,UUID actor,Instant due,Instant now) throws SQLException;
    Task createSourceRequestContinuation(Connection c,UUID tenant,UUID owner,Task prior,Subject decision,ZoneId zone,Instant now)throws SQLException;
    /** Historical repair only. Caller must hold the Lead root, authorize the receiving owner and exclude downstream takeover.
     * Does not mutate the original completed ACK. Rejects any existing successor, including terminal history. */
    Task restoreHistoricalSourceRequest(Connection c,UUID tenant,UUID owner,Task completedAck,ZoneId zone,Instant now)throws SQLException;
    Subject waitForSourceRequestReview(Connection c,UUID tenant,Task task,UUID actor,Instant due,Instant now,Subject decision)throws SQLException;
    Subject causalSourceRequest(Connection c,UUID tenant,Task task) throws SQLException;
    Subject causalStop(Connection c,UUID tenant,Task task) throws SQLException;
    UUID sourceRequestOriginOwner(Connection c,UUID tenant,Task acknowledgement) throws SQLException;
    static TaskFactory databaseBacked(){return new io.github.windyzhu3.ontologylaw.responsibility.internal.persistence.JooqTaskRepository();}
}
