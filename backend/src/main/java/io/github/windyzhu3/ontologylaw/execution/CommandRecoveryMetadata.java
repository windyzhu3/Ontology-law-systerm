package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** ADR0013 construction from the resolved Context, never from command payload values. */
final class CommandRecoveryMetadata {
    private CommandRecoveryMetadata() {}

    @SuppressWarnings("unchecked")
    static Map<String,Object> freeze(CommandEnvelope command, CommandHandler.Context context) {
        if (command.type().recovery()) throw new IllegalArgumentException("Not a public command");
        var binding = new TreeMap<String,Object>();
        switch (command.type()) {
            case SUBMIT_TRANSFER, RESUBMIT_TRANSFER, RECORD_TRANSFER_CONFLICT_REVIEW, RECORD_TRANSFER_INTAKE, CLASSIFY_MATTER -> {
                if(!(context.binding() instanceof CommandAuthorizationBinding.Transfers b)||!context.scope().canonical().equals(CommandScope.transfers(command,b).canonical()))throw invalid();
                binding.put("kind","TRANSFERS");
            }

            case RECORD_OPPORTUNITY_FOLLOWUP_ATTEMPT, RECORD_QUOTE_FOLLOWUP_ATTEMPT -> {
                if(!(context.binding() instanceof CommandAuthorizationBinding.FollowupAttempt b)||!context.scope().canonical().equals(CommandScope.followupAttempt(command,b).canonical()))throw invalid();
                binding.put("kind","FOLLOWUP_ATTEMPT");
            }

            case REQUEST_CONTRACT_RECEIPT_REVIEW, RECORD_CONTRACT_RECEIPT_REVIEW, SUPPLEMENT_CONTRACT_RECEIPT, VERIFY_CONTRACT_EXECUTION_CONDITIONS, END_CONTRACT_NEGOTIATION, REQUEST_CONTRACT_TERMINATION_REVIEW, RECORD_CONTRACT_TERMINATION_REVIEW, RETURN_CONTRACT_SIGNATURE_FOR_REVISION, SAVE_CONTRACT_SIGNATURE_DRAFT, CONFIRM_CONTRACT_SIGNATURE_ARRANGEMENT, SUBMIT_CONTRACT_SIGNATURE, RECORD_CONTRACT_SIGNATURE_VERIFICATION, ARCHIVE_CONTRACT_SIGNATURE, RETURN_CONTRACT_FOR_REVISION, REQUEST_CONTRACT_PREPARATION, RECORD_CONTRACT_PREPARATION_DECISION, START_CONTRACT_PREPARATION, SAVE_CONTRACT_DRAFT, FORM_CONTRACT, REQUEST_CONTRACT_REVIEW, RECORD_CONTRACT_REVIEW, REQUEST_CONTRACT_APPROVAL, RECORD_CONTRACT_DECISION -> {
                if(!(context.binding() instanceof CommandAuthorizationBinding.Contracts b)||!context.scope().canonical().equals(CommandScope.contracts(command,b).canonical()))throw invalid();
                binding.put("kind","CONTRACTS");
            }
            case END_QUOTE_NEGOTIATION, START_QUOTE_PREPARATION, SAVE_QUOTE_DRAFT, FORM_QUOTE, REQUEST_QUOTE_APPROVAL, RECORD_QUOTE_DECISION, RECORD_QUOTE_DELIVERY, RECORD_QUOTE_RESPONSE -> {
                if(!(context.binding() instanceof CommandAuthorizationBinding.Quotes b)||!context.scope().canonical().equals(CommandScope.quotes(command,b).canonical()))throw invalid();
                binding.put("kind","QUOTES");
            }
            case OPEN_OPPORTUNITY_MATERIAL_UPLOAD, ACCEPT_OPPORTUNITY_MATERIAL -> {
                if(!(context.binding() instanceof CommandAuthorizationBinding.Materials b)||!context.scope().canonical().equals(CommandScope.materials(command,b.opportunity(),b.basis(),b.confirmation(),b.previous(),b.upload()).canonical()))throw invalid();
                binding.put("kind","MATERIALS");
            }
            case SAVE_OPPORTUNITY_CUSTOMER_DRAFT, CONFIRM_OPPORTUNITY_CUSTOMER_REQUIREMENTS -> {
                if (!(context.binding() instanceof CommandAuthorizationBinding.CustomerRequirements requirements)
                        || !context.scope().canonical().equals(CommandScope.customerRequirements(command,
                        requirements.opportunity(),requirements.basis(),requirements.draft(),requirements.confirmation()).canonical())) throw invalid();
                binding.put("kind","CUSTOMER_REQUIREMENTS");
            }
            case TRANSFER_OPPORTUNITY_RESPONSIBILITY,RECORD_OPPORTUNITY_OWNER_COORDINATION -> {
                if(!(context.binding() instanceof CommandAuthorizationBinding.OwnerException owner)||!context.scope().canonical().equals(CommandScope.ownerException(command,owner.opportunity(),owner.exception(),owner.basis(),owner.task(),owner.waitReceipt()).canonical()))throw invalid();
                binding.put("kind","OWNER_EXCEPTION");
            }
            case CLOSE_OPPORTUNITY -> {
                if(!(context.binding() instanceof CommandAuthorizationBinding.OpportunityClosure closure)||!context.scope().canonical().equals(CommandScope.opportunityClosure(command,closure.opportunity(),closure.basis(),closure.task(),closure.waitReceipt()).canonical()))throw invalid();
                binding.put("kind","OPPORTUNITY_CLOSURE");
            }
            case CAPTURE_LEAD -> {
                if (!(context.binding() instanceof CommandAuthorizationBinding.Capture capture)
                        || !capture.organization().equals(context.authorization().subject())
                        || !context.scope().canonical().equals(CommandScope.capture(command.actor().tenantId(),
                                capture.sourceAccountCode(), capture.sourceRecordKeyDigest()).canonical())) throw invalid();
                binding.put("kind", "CAPTURE");
            }
            case SAVE_ACTION_DRAFT -> {
                if (!(context.binding() instanceof CommandAuthorizationBinding.Draft draft)
                        || (draft.draftId() == null) != (draft.draftRevision() == null)
                        || !context.scope().canonical().equals(CommandScope.draft(command.actor().tenantId(),
                                draft.taskId(), draft.actionCode()).canonical())) throw invalid();
                binding.put("kind", "DRAFT");
                binding.put("lead", selector(draft.lead()));
                binding.put("taskRevision", draft.taskRevision());
                binding.put("draft", draft.draftId() == null ? null : selector(
                        new Subject("responsibility.action_draft", draft.draftId(), draft.draftRevision(), null)));
            }
            default -> {
                if (R1CommandPolicy.primaryPolicy(command.type()) == null || context.scope().taskId() == null) throw invalid();
                binding.put("kind", "TASK");
                if (context.binding() == null) binding.put("evidence", null);
                else if (command.type() == CommandEnvelope.Type.RECORD_CONTACT_RESULT
                        && context.binding() instanceof CommandAuthorizationBinding.Contact contact) {
                    if (!"evidence.evidence_submission".equals(contact.submission().type()) || contact.submission().hash() == null
                            || !"evidence.evidence_binding".equals(contact.binding().type()) || contact.binding().revision() == null) throw invalid();
                    binding.put("evidence", Map.of("submission", selector(contact.submission()), "binding", selector(contact.binding())));
                } else throw invalid();
            }
        }
        var result = Map.<String,Object>of("profile", "R1_COMMAND_RECEIPT_RECOVERY_V1", "scope", context.scope().fields(), "binding", binding);
        if (CanonicalJson.encode(result).getBytes(StandardCharsets.UTF_8).length > 8192) throw invalid();
        return (Map<String,Object>) CanonicalJson.freeze(result);
    }

    private static Map<String,Object> selector(Subject subject) {
        return subject.revision() == null
                ? Map.of("type", subject.type(), "id", subject.id().toString(), "hash", subject.hash())
                : Map.of("type", subject.type(), "id", subject.id().toString(), "revision", subject.revision());
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid command recovery metadata"); }
}
