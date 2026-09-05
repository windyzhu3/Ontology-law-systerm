package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor;
import java.util.*;

/** Created by a trusted adapter after authentication and input schema validation. */
public record CommandEnvelope(Type type, UUID commandId, UUID correlationId, Actor actor, Object payload) {
    public enum Envelope { INTERNAL_TASK, INTERNAL_ADMIN, CUSTOMER_GRANT, SERVICE_ACTOR }
    public enum Type {
        RESOLVE_DUPLICATE_LEAD, COMPLETE_LEAD_INGRESS, ASSIGN_LEAD, RECORD_ROUTING_DISPOSITION,
        ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST, RECORD_CONTACT_RESULT, REVIEW_LEAD_VALIDITY,
        CAPTURE_LEAD, SAVE_ACTION_DRAFT, REOPEN_DUE_CONTACT_TASKS, REOPEN_DUE_ROUTING_REVIEW_TASKS;
        public boolean recovery() {return this==REOPEN_DUE_CONTACT_TASKS || this==REOPEN_DUE_ROUTING_REVIEW_TASKS;}
        public Envelope envelope(){return recovery()?Envelope.SERVICE_ACTOR:this==CAPTURE_LEAD?Envelope.INTERNAL_ADMIN:Envelope.INTERNAL_TASK;}
    }
    public CommandEnvelope { Objects.requireNonNull(type);Objects.requireNonNull(commandId);Objects.requireNonNull(correlationId);Objects.requireNonNull(actor);payload=CanonicalJson.freeze(payload); }
    @Override public String toString(){return "CommandEnvelope["+type+", "+commandId+"]";}
}
