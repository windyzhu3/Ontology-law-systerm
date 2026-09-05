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
    private final R1EventFacts eventFacts;
    public CommandRuntime(Collection<CommandHandler> handlers,AuthorizationService authorization,String executionNodeCode) {this(handlers,authorization,AuditAppender.databaseBacked(executionNodeCode));}
    public CommandRuntime(Collection<CommandHandler> handlers,AuthorizationService authorization,AuditAppender audit) {
        this(handlers,authorization,audit,null);
    }
    public CommandRuntime(Collection<CommandHandler> handlers,AuthorizationService authorization,String executionNodeCode,R1AuthorizationFacts facts) {
        this(handlers,authorization,AuditAppender.databaseBacked(executionNodeCode),facts);
    }
    public CommandRuntime(Collection<CommandHandler> handlers,AuthorizationService authorization,AuditAppender audit,R1AuthorizationFacts facts) {
        this(handlers,authorization,audit,facts,null);
    }
    public CommandRuntime(Collection<CommandHandler> handlers,AuthorizationService authorization,AuditAppender audit,R1AuthorizationFacts facts,R1EventFacts eventFacts) {
        var registry=new EnumMap<CommandEnvelope.Type,CommandHandler>(CommandEnvelope.Type.class);
        for(var handler:handlers) {
            var type=Objects.requireNonNull(handler.type());
            if(registry.put(type,handler)!=null)throw new IllegalArgumentException("Duplicate static handler");
        }
        this.handlers=Map.copyOf(registry);this.policy=new R1CommandPolicy(authorization,facts);this.audit=Objects.requireNonNull(audit);this.eventFacts=eventFacts;
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
                var eventPolicy=new R1EventPolicy(eventFacts);eventPolicy.beforeWork(c,envelope,context);
                setLocalRole(c,Capability.COMMAND);handler.validateBeforeWork(c,envelope,context);result=handler.execute(c,envelope,context);
                if(result.status()==CommandOutcome.Status.NO_CHANGE)c.rollback(business);
                setLocalRole(c,Capability.QUERY);handler.validateBeforeCommit(c,envelope,context,result);
                eventPolicy.validate(c,envelope,context,result);
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
}
