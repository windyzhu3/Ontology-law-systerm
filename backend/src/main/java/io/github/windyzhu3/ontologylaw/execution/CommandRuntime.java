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
    private final R1CommandPolicy policy;
    private final AuditAppender audit;
    public CommandRuntime(Collection<CommandHandler> handlers,AuthorizationService authorization,String executionNodeCode) {this(handlers,authorization,AuditAppender.databaseBacked(executionNodeCode));}
    public CommandRuntime(Collection<CommandHandler> handlers,AuthorizationService authorization,AuditAppender audit) {
        this(handlers,authorization,audit,null);
    }
    public CommandRuntime(Collection<CommandHandler> handlers,AuthorizationService authorization,String executionNodeCode,R1AuthorizationFacts facts) {
        this(handlers,authorization,AuditAppender.databaseBacked(executionNodeCode),facts);
    }
    public CommandRuntime(Collection<CommandHandler> handlers,AuthorizationService authorization,AuditAppender audit,R1AuthorizationFacts facts) {
        var registry=new EnumMap<CommandEnvelope.Type,CommandHandler>(CommandEnvelope.Type.class);
        for(var handler:handlers) {
            var type=Objects.requireNonNull(handler.type());
            if(registry.put(type,handler)!=null)throw new IllegalArgumentException("Duplicate static handler");
        }
        this.handlers=Map.copyOf(registry);this.policy=new R1CommandPolicy(authorization,facts);this.audit=Objects.requireNonNull(audit);
    }
    /** Caller owns a fresh connection. Commit acknowledgement loss has unknown durability; retry the same key. */
    public CommandResult execute(Connection connection,CommandEnvelope envelope) throws SQLException {
        CommandHandler handler=handlers.get(envelope.type());
        if(handler==null)throw new CommandHandler.Rejected("VALIDATION_FAILED");
        byte[] payload=CanonicalJson.digest(CanonicalJson.encode(envelope.payload()));
        return inTransaction(connection,Capability.QUERY,c->{
            var context=handler.resolve(c,envelope);
            if(!context.scope().tenantId().equals(envelope.actor().tenantId()) || context.scope().type()!=envelope.type()
                    || !context.authorization().actor().equals(envelope.actor()))throw new CommandHandler.Rejected("NOT_AUTHORIZED");
            AuthorizationSnapshot initial=policy.authorize(c,envelope,context,false);
            if(!initial.allowed())throw new CommandHandler.Rejected(initial.rejectionCode());
            setLocalRole(c,Capability.COMMAND);handler.lockRoots(c,envelope,context);
            var store=new JooqCommandStore(c);store.lockCommand(envelope);
            var existing=store.existingOrValidateNew(envelope,context.scope(),payload,x->{handler.recoveryEligibility(x,envelope,context);return null;});
            if(existing!=null) {
                setLocalRole(c,Capability.QUERY);
                var current=policy.authorize(c,envelope,context,true);
                if(!current.allowed())throw new CommandHandler.Rejected(current.rejectionCode());
                return existing;
            }
            UUID slot=store.occupy(envelope,context.scope(),payload);Savepoint business=c.setSavepoint();
            CommandHandler.Result result=null;String rejection=null;AuthorizationSnapshot terminal=null;
            try {
                setLocalRole(c,Capability.QUERY);
                terminal=policy.authorize(c,envelope,context,false);if(!terminal.allowed())throw new CommandHandler.Rejected(terminal.rejectionCode());
                setLocalRole(c,Capability.COMMAND);handler.validateBeforeWork(c,envelope,context);result=handler.execute(c,envelope,context);
                validateResult(envelope.type(),result);
                if(result.status()==CommandOutcome.Status.NO_CHANGE)c.rollback(business);
                setLocalRole(c,Capability.QUERY);handler.validateBeforeCommit(c,envelope,context,result);
                terminal=policy.authorize(c,envelope,context,true);
                if(!terminal.allowed())throw new CommandHandler.Rejected(terminal.rejectionCode());
            } catch(CommandHandler.Rejected denied) {
                c.rollback(business);setLocalRole(c,Capability.QUERY);result=null;rejection=denied.code();
                var current=policy.authorize(c,envelope,context,true);
                // Never erase the denying evidence after rollback makes a later read allowed again.
                if(terminal==null || terminal.allowed())terminal=current;
                else terminal=R1CommandPolicy.retainDenial(terminal,current);
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
