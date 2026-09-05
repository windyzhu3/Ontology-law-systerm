package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.util.*;

public record CommandOutcome(UUID receiptId, Status status, Subject resultFact, String rejectionCode, boolean payloadConflict) {
    public enum Status { SUCCEEDED, NO_CHANGE, REJECTED }
    public CommandOutcome {
        Objects.requireNonNull(receiptId);Objects.requireNonNull(status);
        if((status==Status.REJECTED)!=(resultFact==null) || (status==Status.REJECTED)!=(rejectionCode!=null))throw new IllegalArgumentException("Invalid terminal receipt");
    }
}
