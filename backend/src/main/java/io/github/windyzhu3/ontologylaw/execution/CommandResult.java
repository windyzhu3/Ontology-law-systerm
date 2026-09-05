package io.github.windyzhu3.ontologylaw.execution;

import java.util.Objects;
import java.util.UUID;

/** A terminal receipt or a conflict's safe reference, never both. */
public sealed interface CommandResult permits CommandOutcome, CommandResult.Conflict {
    UUID receiptId();

    record Conflict(UUID receiptId) implements CommandResult {
        public Conflict { Objects.requireNonNull(receiptId); }
        public String code() { return "COMMAND_PAYLOAD_CONFLICT"; }
    }
}
