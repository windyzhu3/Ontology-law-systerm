package io.github.windyzhu3.ontologylaw.evidence;

import java.io.IOException;

public final class MaterialRejectedException extends IOException {
    public enum Reason { TOO_LARGE, INVALID_MEDIA, INFECTED, SCAN_UNAVAILABLE, INTEGRITY_FAILURE }
    private final Reason reason;
    public MaterialRejectedException(Reason reason) { super(reason.name()); this.reason = reason; }
    public Reason reason() { return reason; }
}
