package io.github.windyzhu3.ontologylaw.execution;

import java.sql.*;
import java.util.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationSnapshot;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import io.github.windyzhu3.ontologylaw.execution.internal.persistence.JooqCommandStore;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;

public final class CommandRuntime {
    private final Map<CommandEnvelope.Type,CommandHandler> handlers;
    private final AuthorizationService authorization;
    private final AuditAppender audit;
    public CommandRuntime(Collection<CommandHandler> handlers,AuthorizationService authorization,String executionNodeCode) {this(handlers,authorization,AuditAppender.databaseBacked(executionNodeCode));}
    public CommandRuntime(Collection<CommandHandler> handlers,AuthorizationService authorization,AuditAppender audit) {
        var registry=new EnumMap<CommandEnvelope.Type,CommandHandler>(CommandEnvelope.Type.class);
        for(var handler:handlers)if(registry.put(Objects.requireNonNull(handler.type()),handler)!=null)throw new IllegalArgumentException("Duplicate static handler");
        this.handlers=Map.copyOf(registry);this.authorization=Objects.requireNonNull(authorization);this.audit=Objects.requireNonNull(audit);
    }
    /** Caller owns a fresh connection. Commit acknowledgement loss has unknown durability; retry the same key. */
    public CommandOutcome execute(Connection connection,CommandEnvelope envelope) throws SQLException {
        CommandHandler handler=handlers.get(envelope.type());
        if(handler==null)throw new CommandHandler.Rejected("VALIDATION_FAILED");
        byte[] payload=CanonicalJson.digest(CanonicalJson.encode(envelope.payload()));
        return inTransaction(connection,Capability.QUERY,c->{
            var context=handler.resolve(c,envelope);
            if(!context.scope().tenantId().equals(envelope.actor().tenantId()) || context.scope().type()!=envelope.type()
                    || !context.authorization().actor().equals(envelope.actor()))throw new CommandHandler.Rejected("NOT_AUTHORIZED");
            validatePolicy(envelope.type(),context.authorization().requirement());
            AuthorizationSnapshot initial=authorization.evaluate(c,context.authorization(),false);
            if(!initial.allowed())throw new CommandHandler.Rejected(initial.rejectionCode());
            setLocalRole(c,Capability.COMMAND);handler.lockRoots(c,envelope,context);
            var store=new JooqCommandStore(c);store.lockCommand(envelope);
            var existing=store.existing(envelope,context.scope(),payload);if(existing!=null)return existing;
            if(envelope.type().recovery())handler.recoveryEligibility(c,envelope,context);
            UUID slot=store.occupy(envelope,context.scope(),payload);Savepoint business=c.setSavepoint();
            CommandHandler.Result result=null;String rejection=null;AuthorizationSnapshot terminal;
            try {
                setLocalRole(c,Capability.QUERY);
                var before=authorization.evaluate(c,context.authorization(),false);if(!before.allowed())throw new CommandHandler.Rejected(before.rejectionCode());
                setLocalRole(c,Capability.COMMAND);handler.validateBeforeWork(c,envelope,context);result=handler.execute(c,envelope,context);
                validateResult(envelope.type(),result);
                if(result.status()==CommandOutcome.Status.NO_CHANGE)c.rollback(business);
                setLocalRole(c,Capability.QUERY);handler.validateBeforeCommit(c,envelope,context,result);
                terminal=authorization.evaluate(c,context.authorization(),true);
                if(!terminal.allowed())throw new CommandHandler.Rejected(terminal.rejectionCode());
            } catch(CommandHandler.Rejected denied) {
                c.rollback(business);setLocalRole(c,Capability.QUERY);result=null;rejection=denied.code();
                terminal=authorization.evaluate(c,context.authorization(),true);
            }
            var status=result==null?CommandOutcome.Status.REJECTED:result.status();
            setLocalRole(c,Capability.COMMAND);
            if(status==CommandOutcome.Status.SUCCEEDED)store.event(envelope,result);
            var receipt=store.receipt(envelope,slot,status,result==null?null:result.fact(),rejection);
            UUID auditId=store.newId();
            String summary=CanonicalJson.encode(Map.of("result",status.name(),"authorizationEvidence",terminal.evidence()));
            setLocalRole(c,Capability.AUDIT);
            audit.append(c,new AuditAppender.Entry(auditId,envelope.commandId(),envelope.type().name(),envelope.correlationId(),status.name(),terminal,summary,CanonicalJson.digest(summary)));
            return receipt;
        });
    }
    private static void validatePolicy(CommandEnvelope.Type type,AuthorizationService.Requirement requirement) {
        String expected=switch(type) {
            case RESOLVE_DUPLICATE_LEAD -> "SOURCE_INTAKE_OWNER:LEAD_INGRESS_RESOLVE";
            case COMPLETE_LEAD_INGRESS -> "SOURCE_INTAKE_OWNER:LEAD_INGRESS_COMPLETE";
            case ASSIGN_LEAD -> "ROUTING_SUPERVISOR:LEAD_ASSIGN";
            case RECORD_ROUTING_DISPOSITION -> "ROUTING_SUPERVISOR:LEAD_ROUTING_DECIDE";
            case ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST -> "SOURCE_INTAKE_OWNER:SOURCE_INTAKE_REQUEST_ACK";
            case RECORD_CONTACT_RESULT -> "ASSIGNMENT_OWNER:SALES_CONTACT_OWNER";
            case REVIEW_LEAD_VALIDITY -> "ROUTING_SUPERVISOR:LEAD_VALIDITY_REVIEW";
            default -> null;
        };
        if((type.recovery())!=(requirement.path()==AuthorizationService.Path.SYSTEM)
                || (expected!=null && !expected.equals(requirement.slot()+":"+requirement.authorityCode())))throw new CommandHandler.Rejected("NOT_AUTHORIZED");
    }
    private static void validateResult(CommandEnvelope.Type type,CommandHandler.Result result)throws SQLException {
        String expected=switch(type) {
            case COMPLETE_LEAD_INGRESS,CAPTURE_LEAD -> "lead.lead";
            case ASSIGN_LEAD -> "lead.lead_assignment";
            case RECORD_CONTACT_RESULT -> "lead.lead_contact_result";
            case SAVE_ACTION_DRAFT -> "responsibility.action_draft";
            case REOPEN_DUE_CONTACT_TASKS,REOPEN_DUE_ROUTING_REVIEW_TASKS -> "responsibility.task_occurrence";
            default -> "responsibility.decision_record";
        };
        if(!expected.equals(result.fact().type()))throw new SQLException("Unexpected result fact type","22000");
        Set<CommandHandler.Event> events=switch(type) {
            case RESOLVE_DUPLICATE_LEAD -> Set.of(CommandHandler.Event.LeadDuplicateResolutionRecordedV1);
            case COMPLETE_LEAD_INGRESS -> Set.of(CommandHandler.Event.LeadIngressCompletedV1);
            case ASSIGN_LEAD -> Set.of(CommandHandler.Event.LeadAssignedV1);
            case RECORD_ROUTING_DISPOSITION -> Set.of(CommandHandler.Event.LeadRoutingDispositionRecordedV1,CommandHandler.Event.SourceIntakeStopRequestedV1);
            case ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST -> Set.of(CommandHandler.Event.SourceIntakeStopRequestAcknowledgedV1);
            case RECORD_CONTACT_RESULT -> Set.of(CommandHandler.Event.LeadContactResultRecordedV1,CommandHandler.Event.LeadContactRetryExhaustedV1);
            case REVIEW_LEAD_VALIDITY -> Set.of(CommandHandler.Event.LeadValidityReviewedV1);
            default -> Set.of(); // Missing non-completion descriptors fail closed; later static contract required.
        };
        var unique=new HashSet<CommandHandler.Notification>();
        for(var notification:result.notifications())if(!events.contains(notification.event()) || !notification.event().sourceFactType().equals(notification.sourceFact().type()) || !unique.add(notification))throw new SQLException("Unregistered event descriptor","22000");
    }
}
