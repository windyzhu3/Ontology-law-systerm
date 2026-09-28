package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.util.UUID;

/** Server-resolved selectors, never a caller-selected policy. Mutable eligibility belongs to the Owner handler. */
public sealed interface CommandAuthorizationBinding {
    record Transfers(Subject opportunity,Subject workflow) implements CommandAuthorizationBinding {}
    record OpportunityRepair(Subject opportunity,Subject task,UUID owner,Subject basis,Subject draft,java.util.List<Subject> takeover) implements CommandAuthorizationBinding {
        public OpportunityRepair {takeover=java.util.List.copyOf(takeover);}
        public java.util.Map<String,Object> payload(){var p=new java.util.TreeMap<String,Object>();p.put("opportunity",CommandScope.selector(opportunity));p.put("task",CommandScope.selector(task));p.put("ownerAppointmentId",owner.toString());p.put("basis",CommandScope.selector(basis));p.put("draft",CommandScope.selector(draft));p.put("takeoverFacts",takeover.stream().map(CommandScope::selector).toList());return p;}
    }
    record SourceRepair(Subject task,Subject lead,UUID owner,Subject decision,UUID supervisor) implements CommandAuthorizationBinding {
        public java.util.Map<String,Object> payload(){return java.util.Map.of("ackTaskId",task.id().toString(),"expectedAckTaskRevision",task.revision(),"leadId",lead.id().toString(),"expectedLeadRevision",lead.revision(),"expectedAckOwnerAppointmentId",owner.toString(),"ackDecisionId",decision.id().toString(),"ackDecisionHash",decision.hash(),"supervisorAppointmentId",supervisor.toString());}
    }
    record ContractRecovery(Subject opportunity,Subject basis,Subject source,Subject workflow) implements CommandAuthorizationBinding { public String sourceKind(){return switch(source.type()){case "contract.execution_verification"->"TRANSFER_HANDOFF";case "transfer.workflow"->"TRANSFER_RECOVERY";case "contract.signature_archive"->"PAYMENT_HANDOFF";case "contract.payment_request"->"PAYMENT_RECOVERY";case "contract.signature_handoff"->"EXECUTION_HANDOFF";case "contract.negotiation_disposition"->"TERMINATION_REVIEW";case "contract.preparation_workflow"->"AUTHORITY_RETURN";case "contract.signature_readiness"->"SIGNATURE_READINESS";case "contract.signature_workflow"->"SIGNATURE_AUTHORITY_RETURN";default->"ACCEPTED_QUOTE";};} public String resultType(){return sourceKind().startsWith("TRANSFER_")?"transfer.workflow":sourceKind().startsWith("PAYMENT_")?"contract.payment_workflow":sourceKind().equals("EXECUTION_HANDOFF")?"contract.execution_workflow":sourceKind().equals("TERMINATION_REVIEW")?"contract.termination_review_assignment":sourceKind().startsWith("SIGNATURE_")?"contract.signature_workflow":"contract.preparation_workflow";} }
    record Contracts(Subject opportunity,Subject basis,Subject confirmation,Subject contract,Subject draft,Subject version,Subject workflow) implements CommandAuthorizationBinding {}
    record FollowupAttempt(Subject opportunity,Subject basis,Subject task,Subject waitReceipt,Subject workflow) implements CommandAuthorizationBinding {}
    record Quotes(Subject opportunity,Subject basis,Subject confirmation,Subject draft,Subject quote,Subject workflow) implements CommandAuthorizationBinding {}
    record Materials(Subject opportunity,Subject basis,Subject confirmation,Subject previous,Subject upload) implements CommandAuthorizationBinding {}
    record CustomerRequirements(Subject opportunity,Subject basis,Subject draft,Subject confirmation) implements CommandAuthorizationBinding {}
    record OpportunityClosure(Subject opportunity,Subject basis,Subject task,Subject waitReceipt) implements CommandAuthorizationBinding {}
    record OwnerException(Subject opportunity,Subject exception,Subject basis,Subject task,Subject waitReceipt) implements CommandAuthorizationBinding {}
    record OpportunityActivation(Subject opportunity) implements CommandAuthorizationBinding {}
    record Contact(Subject submission,Subject binding) implements CommandAuthorizationBinding {}
    record Capture(String sourceAccountCode, String sourceRecordKeyDigest, Subject organization)
            implements CommandAuthorizationBinding {}
    record Draft(UUID taskId, Subject lead, long taskRevision, UUID draftId, Long draftRevision,
            CommandEnvelope.Type actionCode, int schemaVersion) implements CommandAuthorizationBinding {}
    record Recovery(UUID taskId, Subject lead, long taskRevision, UUID waitReceiptId, String waitReceiptHash)
            implements CommandAuthorizationBinding {}
}
