package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.PrincipalKind;
import java.util.*;

/** Created by a trusted adapter after authentication and input schema validation. */
public record CommandEnvelope(Type type, UUID commandId, UUID correlationId, Actor actor, Object payload, TaskPrecondition taskPrecondition) {
    /** Trusted path/header carrier, deliberately outside the canonical request-body digest. */
    public record TaskPrecondition(UUID taskId,String ifMatch) {public TaskPrecondition {Objects.requireNonNull(taskId);}}
    public CommandEnvelope(Type type,UUID commandId,UUID correlationId,Actor actor,Object payload) {this(type,commandId,correlationId,actor,payload,null);}
    public enum Envelope { INTERNAL_TASK, INTERNAL_ADMIN, CUSTOMER_GRANT, SERVICE_ACTOR }
    public enum Type {
        RESOLVE_DUPLICATE_LEAD, COMPLETE_LEAD_INGRESS, ASSIGN_LEAD, RECORD_ROUTING_DISPOSITION,
        ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST, RECORD_CONTACT_RESULT, REVIEW_LEAD_VALIDITY,
        CAPTURE_LEAD, SAVE_ACTION_DRAFT, REOPEN_DUE_CONTACT_TASKS, REOPEN_DUE_ROUTING_REVIEW_TASKS;
        public boolean recovery() {return this==REOPEN_DUE_CONTACT_TASKS || this==REOPEN_DUE_ROUTING_REVIEW_TASKS;}
    }
    public CommandEnvelope { Objects.requireNonNull(type);Objects.requireNonNull(commandId);Objects.requireNonNull(correlationId);Objects.requireNonNull(actor);mapping(type,actor.principalKind());payload=CanonicalJson.freeze(payload); }
    public Envelope envelope(){return mapping(type,actor.principalKind());}
    private static Envelope mapping(Type type,PrincipalKind kind) {
        return switch(type) {
            case CAPTURE_LEAD -> kind==PrincipalKind.SERVICE?Envelope.SERVICE_ACTOR:Envelope.INTERNAL_ADMIN;
            case REOPEN_DUE_CONTACT_TASKS,REOPEN_DUE_ROUTING_REVIEW_TASKS -> {
                if(kind!=PrincipalKind.SERVICE)throw new IllegalArgumentException("Recovery requires SERVICE");
                yield Envelope.SERVICE_ACTOR;
            }
            case RESOLVE_DUPLICATE_LEAD,COMPLETE_LEAD_INGRESS,ASSIGN_LEAD,RECORD_ROUTING_DISPOSITION,
                    ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST,RECORD_CONTACT_RESULT,REVIEW_LEAD_VALIDITY,SAVE_ACTION_DRAFT -> {
                if(kind!=PrincipalKind.HUMAN)throw new IllegalArgumentException("Task commands require HUMAN");
                yield Envelope.INTERNAL_TASK;
            }
        };
    }
    @Override public String toString(){return "CommandEnvelope["+type+", "+commandId+"]";}
}
