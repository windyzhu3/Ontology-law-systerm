package io.github.windyzhu3.ontologylaw.lead;

import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Operator-selected exact historical ACK repair; never scheduled or exposed as a human task. */
final class SourceRequestRepairCommand implements CommandHandler {
    private final TaskFactory tasks=TaskFactory.databaseBacked();
    private final LeadIngressService leads;
    private final R1SourcePolicyRegistry sources;
    private final AssignmentPolicy assignment=new AssignmentPolicy();
    SourceRequestRepairCommand(R1SourcePolicyRegistry sources,LeadProtection protection){this.sources=sources;this.leads=LeadIngressService.databaseBacked(protection);}
    public CommandEnvelope.Type type(){return CommandEnvelope.Type.RESTORE_SOURCE_REQUEST_TASK;}
    private static void require(boolean ok,String code){if(!ok)throw new Rejected(code);}
    private static UUID uuid(Map<?,?> p,String name){String v=(String)p.get(name);UUID id=UUID.fromString(v);if(!id.toString().equals(v))throw new IllegalArgumentException();return id;}
    private static long revision(Map<?,?> p,String name){Object n=p.get(name);if(!(n instanceof Long)||((Long)n)<0||((Long)n)>=9007199254740991L)throw new IllegalArgumentException();return (Long)n;}
    private static CommandAuthorizationBinding.SourceRepair input(CommandEnvelope e){
        try{require(e.taskPrecondition()==null&&e.draftPrecondition()==null&&e.identityPrecondition()==null,"VALIDATION_FAILED");
            if(!(e.payload() instanceof Map<?,?> p)||!p.keySet().equals(Set.of("ackTaskId","expectedAckTaskRevision","leadId","expectedLeadRevision","expectedAckOwnerAppointmentId","ackDecisionId","ackDecisionHash","supervisorAppointmentId")))throw new IllegalArgumentException();
            return new CommandAuthorizationBinding.SourceRepair(new Subject("responsibility.task_occurrence",uuid(p,"ackTaskId"),revision(p,"expectedAckTaskRevision"),null),new Subject("lead.lead",uuid(p,"leadId"),revision(p,"expectedLeadRevision"),null),uuid(p,"expectedAckOwnerAppointmentId"),new Subject("responsibility.decision_record",uuid(p,"ackDecisionId"),null,(String)p.get("ackDecisionHash")),uuid(p,"supervisorAppointmentId"));
        }catch(IllegalArgumentException|ClassCastException|NullPointerException ex){throw new Rejected("VALIDATION_FAILED");}
    }
    public Context resolve(Connection c,CommandEnvelope e)throws SQLException {
        var b=input(e);var tenant=e.actor().tenantId();var task=tasks.read(c,tenant,b.task().id());require(task!=null,"NOT_AUTHORIZED");
        var owner=AuthorizationIdentityReader.databaseBacked().owner(c,tenant,task.owner(),tasks.now(c));require(owner!=null,"NOT_AUTHORIZED");
        var request=R1AuthorityReader.databaseBacked().select(c,e.actor(),b.lead(),owner.organizationId(),"SYSTEM_RECOVERY","ROUTING_REVIEW_TASK_RECOVER");require(request!=null,"NOT_AUTHORIZED");
        return new Context(CommandScope.sourceRepair(tenant,b),request,b);
    }
    public void lockRoots(Connection c,CommandEnvelope e,Context ctx)throws SQLException {var b=input(e);leads.lock(c,e.actor().tenantId(),b.lead().id());tasks.lock(c,e.actor().tenantId(),b.task().id());}
    public void recoveryEligibility(Connection c,CommandEnvelope e,Context ctx)throws SQLException {validateBeforeWork(c,e,ctx);}
    private R1SourcePolicyRegistry.SourcePolicy current(Connection c,CommandEnvelope e)throws SQLException {
        var b=input(e);var tenant=e.actor().tenantId();var lead=leads.header(c,tenant,b.lead().id());
        require(lead!=null&&b.lead().equals(lead.selector())&&lead.assignment()==null&&!leads.hasAssignmentOrContactHistory(c,tenant,b.lead().id()),"STALE_SUBJECT");
        var policy=sources.find(lead.source());require(policy!=null,"VALIDATION_FAILED");
        AuthorizationService.databaseBacked().lockForEvaluation(c,tenant);
        require(b.supervisor().equals(assignment.sourceRequestOwner(c,tenant,tasks.read(c,tenant,b.task().id()),policy)),"SUPERVISOR_UNRESOLVED");return policy;
    }
    public void validateBeforeWork(Connection c,CommandEnvelope e,Context ctx)throws SQLException {
        var b=input(e);var task=tasks.read(c,e.actor().tenantId(),b.task().id());
        require(task!=null&&task.type()==TaskFactory.Type.ACK_SOURCE_INTAKE_STOP_REQUEST&&"DONE".equals(task.state())&&task.selector().equals(b.task())&&task.subject().equals(b.lead())&&task.owner().equals(b.owner())&&b.decision().equals(task.completion()),"STALE_TASK");
        current(c,e);
    }
    public Result execute(Connection c,CommandEnvelope e,Context ctx)throws SQLException {
        var b=input(e);var policy=current(c,e);var next=tasks.restoreHistoricalSourceRequest(c,e.actor().tenantId(),b.supervisor(),tasks.read(c,e.actor().tenantId(),b.task().id()),ZoneId.of(policy.businessTimezone()),tasks.now(c));
        return Result.succeeded(next.selector(),Event.SourceRequestTaskRestoredV1);
    }
    public void validateBeforeCommit(Connection c,CommandEnvelope e,Context ctx,Result result)throws SQLException {
        validateBeforeWork(c,e,ctx);var b=input(e);var all=tasks.activeForLead(c,e.actor().tenantId(),b.lead());
        require(all.size()==1&&all.getFirst().selector().equals(result.fact())&&all.getFirst().type()==TaskFactory.Type.RESOLVE_SOURCE_REQUEST&&all.getFirst().owner().equals(b.supervisor())&&all.getFirst().state().equals("OPEN")&&b.decision().equals(tasks.causalSourceRequest(c,e.actor().tenantId(),all.getFirst())),"STALE_TASK");
    }
}
