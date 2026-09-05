package io.github.windyzhu3.ontologylaw.identity;

import java.time.Instant;
import java.util.*;

/** Immutable evidence from one current database authorization decision. */
public record AuthorizationSnapshot(AuthorizationService.Request request, Instant checkedAt,
        boolean allowed, String rejectionCode, AuthorizationService.Subject authorityFact, String evidence, byte[] digest) {
    public AuthorizationSnapshot { Objects.requireNonNull(request); Objects.requireNonNull(checkedAt); digest = digest.clone(); }
    @Override public byte[] digest() { return digest.clone(); }
}
