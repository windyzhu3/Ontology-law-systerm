package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.util.UUID;

/** Server-resolved selectors, never a caller-selected policy. Mutable eligibility belongs to the Owner handler. */
public sealed interface CommandAuthorizationBinding {
    record Capture(String sourceAccountCode, String sourceRecordKeyDigest, Subject organization)
            implements CommandAuthorizationBinding {}
    record Draft(UUID taskId, Subject lead, long taskRevision, UUID draftId, Long draftRevision,
            CommandEnvelope.Type actionCode, int schemaVersion) implements CommandAuthorizationBinding {}
    record Recovery(UUID taskId, Subject lead, long taskRevision, UUID waitReceiptId, String waitReceiptHash)
            implements CommandAuthorizationBinding {}
}
