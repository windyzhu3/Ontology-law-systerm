package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Immutable execution facts, not an authorization or disclosure API. */
public interface CommandReceiptReader {
    record Receipt(UUID commandId,String commandType,String envelope,byte[] scopeDigest,CommandOutcome outcome,Instant completedAt,Subject selector) {
        public Receipt {scopeDigest=scopeDigest.clone();}
        @Override public byte[] scopeDigest(){return scopeDigest.clone();}
        public Map<String,Object> projection(Actor actor) {
            var body=new TreeMap<String,Object>();body.put("commandId",commandId.toString());body.put("receiptId",outcome.receiptId().toString());
            body.put("outcome",outcome.status().name());body.put("completedAt",completedAt.toString());
            if(outcome.resultFact()!=null) {
                var source=outcome.resultFact();var fact=new TreeMap<String,Object>();
                String type=switch(source.type()) {case "transfer.submission"->"TRANSFER_SUBMISSION";case "transfer.review"->"TRANSFER_CONFLICT_REVIEW";case "transfer.intake"->"TRANSFER_INTAKE";case "transfer.classification"->"MATTER_CLASSIFICATION";case "transfer.workflow"->"TRANSFER_WORKFLOW";case "transfer.transfer_request"->"TRANSFER_REQUEST";case "contract.payment_request"->"CONTRACT_PAYMENT_REQUEST";case "contract.payment_review"->"CONTRACT_PAYMENT_REVIEW";case "contract.payment_workflow"->"CONTRACT_PAYMENT_WORKFLOW";case "contract.execution_workflow"->"CONTRACT_EXECUTION_WORKFLOW";case "contract.execution_verification"->"CONTRACT_EXECUTION_VERIFICATION";case "contract.negotiation_disposition"->"CONTRACT_NEGOTIATION_DISPOSITION";case "contract.termination_review_assignment"->"CONTRACT_TERMINATION_REVIEW_ASSIGNMENT";case "contract.signature_draft"->"CONTRACT_SIGNATURE_DRAFT";case "contract.signature_arrangement"->"CONTRACT_SIGNATURE_ARRANGEMENT";case "contract.signature_submission"->"CONTRACT_SIGNATURE_SUBMISSION";case "contract.signature_verification"->"CONTRACT_SIGNATURE_VERIFICATION";case "contract.signature_archive"->"CONTRACT_SIGNATURE_ARCHIVE";case "contract.signature_revision_return"->"CONTRACT_SIGNATURE_REVISION_RETURN";case "contract.signature_workflow"->"CONTRACT_SIGNATURE_WORKFLOW";case "contract.signature_handoff"->"CONTRACT_SIGNATURE_HANDOFF";case "identity.principal"->"IDENTITY_PRINCIPAL";case "identity.organization_unit"->"ORGANIZATION_UNIT";case "identity.appointment"->"APPOINTMENT";case "identity.authority_grant"->"AUTHORITY_GRANT";case "lead.lead"->"LEAD";case "responsibility.action_draft"->"ACTION_DRAFT";case "responsibility.task_occurrence"->"TASK_OCCURRENCE";case "responsibility.decision_record"->"DECISION_RECORD";case "lead.lead_assignment"->"LEAD_ASSIGNMENT";case "lead.lead_contact_result"->"LEAD_CONTACT_RESULT";case "opportunity.owner_exception"->"OPPORTUNITY_OWNER_EXCEPTION";case "audit.audit_entry"->"OPPORTUNITY_OWNER_VALIDATION";case "opportunity.opportunity_progress"->"OPPORTUNITY_PROGRESS";case "evidence.material_upload_basis"->"OPPORTUNITY_MATERIAL_UPLOAD";case "opportunity.material_version"->"OPPORTUNITY_MATERIAL_VERSION";case "opportunity.customer_requirement_draft"->"OPPORTUNITY_CUSTOMER_DRAFT";case "opportunity.customer_requirement_confirmation"->"OPPORTUNITY_CUSTOMER_CONFIRMATION";case "contract.preparation_workflow"->"CONTRACT_PREPARATION_WORKFLOW";case "contract.preparation_request"->"CONTRACT_PREPARATION_REQUEST";case "contract.preparation_decision"->"CONTRACT_PREPARATION_DECISION";case "contract.contract"->"CONTRACT";case "contract.preparation_draft"->"CONTRACT_DRAFT";case "contract.contract_revision"->"CONTRACT_REVISION";case "contract.revision_review_request"->"CONTRACT_REVIEW_REQUEST";case "contract.revision_review_decision"->"CONTRACT_REVIEW_DECISION";case "contract.revision_approval_request"->"CONTRACT_APPROVAL_REQUEST";case "contract.revision_approval_decision"->"CONTRACT_APPROVAL_DECISION";case "opportunity.followup_attempt"->"FOLLOWUP_ATTEMPT";case "opportunity.quote_termination"->"QUOTE_TERMINATION";case "opportunity.quote_preparation_intent"->"QUOTE_PREPARATION_INTENT";case "opportunity.quote_draft"->"QUOTE_DRAFT";case "opportunity.quote_revision"->"QUOTE_REVISION";case "opportunity.quote_approval_request"->"QUOTE_APPROVAL_REQUEST";case "opportunity.quote_approval_decision"->"QUOTE_APPROVAL_DECISION";case "opportunity.quote_issue"->"QUOTE_ISSUE";case "opportunity.quote_response"->"QUOTE_RESPONSE";case "opportunity.closure"->"OPPORTUNITY_CLOSURE";default->throw new IllegalArgumentException("Unsupported receipt fact");};
                fact.put("factType",type);fact.put("factRef",PublicFactReferences.reference(actor,source.type(),source.id()));
                if(source.revision()!=null)fact.put("revision",source.revision());else fact.put("digest",source.hash());body.put("resultFact",Collections.unmodifiableMap(fact));
            } else body.put("rejectionCode",outcome.rejectionCode());
            return Collections.unmodifiableMap(body);
        }
    }
    Receipt read(Connection c,UUID tenant,UUID commandId) throws SQLException;
    static CommandReceiptReader databaseBacked(){return new io.github.windyzhu3.ontologylaw.execution.internal.persistence.JooqCommandReceiptReader();}
}
