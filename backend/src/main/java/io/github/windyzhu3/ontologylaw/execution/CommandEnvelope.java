package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.PrincipalKind;
import java.util.*;

/** Created by a trusted adapter after authentication and input schema validation. */
public record CommandEnvelope(Type type, UUID commandId, UUID correlationId, Actor actor, Object payload, TaskPrecondition taskPrecondition, DraftPrecondition draftPrecondition, IdentityPrecondition identityPrecondition) {
    public record IdentityPrecondition(UUID id,String ifMatch) {}
    public CommandEnvelope(Type type,UUID commandId,UUID correlationId,Actor actor,Object payload,TaskPrecondition task,DraftPrecondition draft){this(type,commandId,correlationId,actor,payload,task,draft,null);}
    /** Trusted path/header carrier, deliberately outside the canonical request-body digest. */
    public record TaskPrecondition(UUID taskId,String ifMatch) {public TaskPrecondition {Objects.requireNonNull(taskId);}}
    public record DraftPrecondition(UUID taskId,String ifMatch,String ifNoneMatch) {public DraftPrecondition {Objects.requireNonNull(taskId);}}
    public CommandEnvelope(Type type,UUID commandId,UUID correlationId,Actor actor,Object payload) {this(type,commandId,correlationId,actor,payload,null,null);}
    public CommandEnvelope(Type type,UUID commandId,UUID correlationId,Actor actor,Object payload,TaskPrecondition taskPrecondition) {this(type,commandId,correlationId,actor,payload,taskPrecondition,null);}
    public enum Envelope { INTERNAL_TASK, INTERNAL_ADMIN, CUSTOMER_GRANT, SERVICE_ACTOR }
    public enum Type {
        SUBMIT_TRANSFER, RESUBMIT_TRANSFER, RECORD_TRANSFER_CONFLICT_REVIEW, RECORD_TRANSFER_INTAKE, CLASSIFY_MATTER,
        RESOLVE_DUPLICATE_LEAD, COMPLETE_LEAD_INGRESS, ASSIGN_LEAD, RECORD_ROUTING_DISPOSITION,
        RECORD_SOURCE_REQUEST_CONTINUATION, ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST, RECORD_CONTACT_RESULT, REVIEW_LEAD_VALIDITY,
        REPAIR_SUPERSEDED_OPPORTUNITY_TASK, RESTORE_SOURCE_REQUEST_TASK, RECONCILE_CONTRACT_PREPARATION, CAPTURE_LEAD, SAVE_ACTION_DRAFT, RECORD_OPPORTUNITY_PROGRESS, ACTIVATE_INITIAL_OPPORTUNITY_TASK, REOPEN_DUE_CONTACT_TASKS, REOPEN_DUE_ROUTING_REVIEW_TASKS, REOPEN_DUE_SOURCE_REQUEST_TASKS, REOPEN_DUE_OPPORTUNITY_TASKS,
        OBSERVE_OPPORTUNITY_OWNER_EXCEPTION, TRANSFER_OPPORTUNITY_RESPONSIBILITY, RECORD_OPPORTUNITY_OWNER_COORDINATION, CLOSE_OPPORTUNITY,
        SAVE_OPPORTUNITY_CUSTOMER_DRAFT, CONFIRM_OPPORTUNITY_CUSTOMER_REQUIREMENTS, OPEN_OPPORTUNITY_MATERIAL_UPLOAD, ACCEPT_OPPORTUNITY_MATERIAL,
        REQUEST_CONTRACT_RECEIPT_REVIEW, RECORD_CONTRACT_RECEIPT_REVIEW, SUPPLEMENT_CONTRACT_RECEIPT, VERIFY_CONTRACT_EXECUTION_CONDITIONS, END_CONTRACT_NEGOTIATION, REQUEST_CONTRACT_TERMINATION_REVIEW, RECORD_CONTRACT_TERMINATION_REVIEW, RETURN_CONTRACT_SIGNATURE_FOR_REVISION, SAVE_CONTRACT_SIGNATURE_DRAFT, CONFIRM_CONTRACT_SIGNATURE_ARRANGEMENT, SUBMIT_CONTRACT_SIGNATURE, RECORD_CONTRACT_SIGNATURE_VERIFICATION, ARCHIVE_CONTRACT_SIGNATURE, RETURN_CONTRACT_FOR_REVISION, REQUEST_CONTRACT_PREPARATION, RECORD_CONTRACT_PREPARATION_DECISION, START_CONTRACT_PREPARATION, SAVE_CONTRACT_DRAFT, FORM_CONTRACT, REQUEST_CONTRACT_REVIEW, RECORD_CONTRACT_REVIEW, REQUEST_CONTRACT_APPROVAL, RECORD_CONTRACT_DECISION,
        RECORD_OPPORTUNITY_FOLLOWUP_ATTEMPT, RECORD_QUOTE_FOLLOWUP_ATTEMPT, END_QUOTE_NEGOTIATION, START_QUOTE_PREPARATION, SAVE_QUOTE_DRAFT, FORM_QUOTE, REQUEST_QUOTE_APPROVAL, RECORD_QUOTE_DECISION, RECORD_QUOTE_DELIVERY, RECORD_QUOTE_RESPONSE,
        CREATE_IDENTITY_PRINCIPAL, RENAME_IDENTITY_PRINCIPAL, SUSPEND_IDENTITY_PRINCIPAL, RESUME_IDENTITY_PRINCIPAL, DISABLE_IDENTITY_PRINCIPAL,
        CREATE_ORGANIZATION_UNIT, RENAME_ORGANIZATION_UNIT, CLOSE_ORGANIZATION_UNIT,
        CREATE_APPOINTMENT, SUSPEND_APPOINTMENT, RESUME_APPOINTMENT, END_APPOINTMENT, CREATE_AUTHORITY_GRANT, REVOKE_AUTHORITY_GRANT;
        public boolean identity(){return io.github.windyzhu3.ontologylaw.identity.IdentityCommands.registered(name());}
        public boolean transfers(){return this==SUBMIT_TRANSFER||this==RESUBMIT_TRANSFER||this==RECORD_TRANSFER_CONFLICT_REVIEW||this==RECORD_TRANSFER_INTAKE||this==CLASSIFY_MATTER;}
        public boolean contracts(){return this==REQUEST_CONTRACT_RECEIPT_REVIEW||this==RECORD_CONTRACT_RECEIPT_REVIEW||this==SUPPLEMENT_CONTRACT_RECEIPT||this==VERIFY_CONTRACT_EXECUTION_CONDITIONS||this==END_CONTRACT_NEGOTIATION||this==REQUEST_CONTRACT_TERMINATION_REVIEW||this==RECORD_CONTRACT_TERMINATION_REVIEW||this==RETURN_CONTRACT_SIGNATURE_FOR_REVISION||this==SAVE_CONTRACT_SIGNATURE_DRAFT||this==CONFIRM_CONTRACT_SIGNATURE_ARRANGEMENT||this==SUBMIT_CONTRACT_SIGNATURE||this==RECORD_CONTRACT_SIGNATURE_VERIFICATION||this==ARCHIVE_CONTRACT_SIGNATURE||this==RETURN_CONTRACT_FOR_REVISION||this==REQUEST_CONTRACT_PREPARATION||this==RECORD_CONTRACT_PREPARATION_DECISION||this==START_CONTRACT_PREPARATION||this==SAVE_CONTRACT_DRAFT||this==FORM_CONTRACT||this==REQUEST_CONTRACT_REVIEW||this==RECORD_CONTRACT_REVIEW||this==REQUEST_CONTRACT_APPROVAL||this==RECORD_CONTRACT_DECISION;}
        public boolean followupAttempts(){return this==RECORD_OPPORTUNITY_FOLLOWUP_ATTEMPT||this==RECORD_QUOTE_FOLLOWUP_ATTEMPT;}
        public boolean quotes(){return this==END_QUOTE_NEGOTIATION||this==START_QUOTE_PREPARATION||this==SAVE_QUOTE_DRAFT||this==FORM_QUOTE||this==REQUEST_QUOTE_APPROVAL||this==RECORD_QUOTE_DECISION||this==RECORD_QUOTE_DELIVERY||this==RECORD_QUOTE_RESPONSE;}
        public boolean materials(){return this==OPEN_OPPORTUNITY_MATERIAL_UPLOAD||this==ACCEPT_OPPORTUNITY_MATERIAL;}
        public boolean customerRequirements(){return this==SAVE_OPPORTUNITY_CUSTOMER_DRAFT||this==CONFIRM_OPPORTUNITY_CUSTOMER_REQUIREMENTS;}
        public boolean ownerException(){return this==OBSERVE_OPPORTUNITY_OWNER_EXCEPTION||this==TRANSFER_OPPORTUNITY_RESPONSIBILITY||this==RECORD_OPPORTUNITY_OWNER_COORDINATION;}
        public boolean internalMaintenance(){return this==REPAIR_SUPERSEDED_OPPORTUNITY_TASK||this==RESTORE_SOURCE_REQUEST_TASK||this==RECONCILE_CONTRACT_PREPARATION||recovery()||this==ACTIVATE_INITIAL_OPPORTUNITY_TASK||this==OBSERVE_OPPORTUNITY_OWNER_EXCEPTION;}
        public boolean recovery() {return this==REOPEN_DUE_CONTACT_TASKS || this==REOPEN_DUE_ROUTING_REVIEW_TASKS || this==REOPEN_DUE_SOURCE_REQUEST_TASKS || this==REOPEN_DUE_OPPORTUNITY_TASKS;}
    }
    public CommandEnvelope { Objects.requireNonNull(type);Objects.requireNonNull(commandId);Objects.requireNonNull(correlationId);Objects.requireNonNull(actor);mapping(type,actor.principalKind());payload=CanonicalJson.freeze(payload); }
    public Envelope envelope(){return mapping(type,actor.principalKind());}
    private static Envelope mapping(Type type,PrincipalKind kind) {
        if(type.followupAttempts()){if(kind!=PrincipalKind.HUMAN)throw new IllegalArgumentException("Follow-up attempts require HUMAN");return Envelope.INTERNAL_ADMIN;}
        if(type.transfers()){if(kind!=PrincipalKind.HUMAN)throw new IllegalArgumentException("Transfer commands require HUMAN");return Envelope.INTERNAL_ADMIN;}
        if(type.contracts()){if(kind!=PrincipalKind.HUMAN)throw new IllegalArgumentException("Contract commands require HUMAN");return Envelope.INTERNAL_ADMIN;}
        if(type.quotes()){if(kind!=PrincipalKind.HUMAN)throw new IllegalArgumentException("Quote commands require HUMAN");return Envelope.INTERNAL_ADMIN;}
        if(type.identity()){if(kind!=PrincipalKind.HUMAN)throw new IllegalArgumentException("Identity commands require HUMAN");return Envelope.INTERNAL_ADMIN;}
        return switch(type) {
            case CAPTURE_LEAD -> kind==PrincipalKind.SERVICE?Envelope.SERVICE_ACTOR:Envelope.INTERNAL_ADMIN;
            case REPAIR_SUPERSEDED_OPPORTUNITY_TASK,RESTORE_SOURCE_REQUEST_TASK,RECONCILE_CONTRACT_PREPARATION,OBSERVE_OPPORTUNITY_OWNER_EXCEPTION,ACTIVATE_INITIAL_OPPORTUNITY_TASK,REOPEN_DUE_CONTACT_TASKS,REOPEN_DUE_ROUTING_REVIEW_TASKS, REOPEN_DUE_SOURCE_REQUEST_TASKS,REOPEN_DUE_OPPORTUNITY_TASKS -> {
                if(kind!=PrincipalKind.SERVICE)throw new IllegalArgumentException("Recovery requires SERVICE");
                yield Envelope.SERVICE_ACTOR;
            }
            case TRANSFER_OPPORTUNITY_RESPONSIBILITY,RECORD_OPPORTUNITY_OWNER_COORDINATION,CLOSE_OPPORTUNITY,SAVE_OPPORTUNITY_CUSTOMER_DRAFT,CONFIRM_OPPORTUNITY_CUSTOMER_REQUIREMENTS,OPEN_OPPORTUNITY_MATERIAL_UPLOAD,ACCEPT_OPPORTUNITY_MATERIAL -> {
                if(kind!=PrincipalKind.HUMAN)throw new IllegalArgumentException("Owner exception decisions require HUMAN");
                yield Envelope.INTERNAL_ADMIN;
            }
            case RESOLVE_DUPLICATE_LEAD,COMPLETE_LEAD_INGRESS,ASSIGN_LEAD,RECORD_ROUTING_DISPOSITION,
                    RECORD_SOURCE_REQUEST_CONTINUATION,ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST,RECORD_CONTACT_RESULT,REVIEW_LEAD_VALIDITY,SAVE_ACTION_DRAFT,RECORD_OPPORTUNITY_PROGRESS -> {
                if(kind!=PrincipalKind.HUMAN)throw new IllegalArgumentException("Task commands require HUMAN");
                yield Envelope.INTERNAL_TASK;
            }
            default -> throw new IllegalArgumentException("Unregistered envelope");
        };
    }
    @Override public String toString(){return "CommandEnvelope["+type+", "+commandId+"]";}
}
