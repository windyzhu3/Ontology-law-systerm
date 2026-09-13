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
