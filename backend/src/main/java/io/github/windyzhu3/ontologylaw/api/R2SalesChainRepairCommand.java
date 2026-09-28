package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.contract.OpportunityContractReader;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.sql.*;
import java.util.*;

/** Named operator maintenance. Exact root lock and snapshot; no task discovery, UI or business progression. */
final class R2SalesChainRepairCommand implements CommandHandler {
    private final OpportunityCommandReader opportunities;
    private final TaskFactory tasks=TaskFactory.databaseBacked();
    private final CurrentTaskReader current=CurrentTaskReader.databaseBacked();
    private final OpportunityContractReader contracts=OpportunityContractReader.databaseBacked();
    R2SalesChainRepairCommand(OpportunityProgressProtection protection){opportunities=OpportunityCommandReader.databaseBacked(protection);}
    public CommandEnvelope.Type type(){return CommandEnvelope.Type.REPAIR_SUPERSEDED_OPPORTUNITY_TASK;}
    private static void require(boolean ok,String code){if(!ok)throw new Rejected(code);}
    private static Subject selector(Object value){
        if(!(value instanceof Map<?,?> m)||!m.keySet().equals(Set.of("type","id","revision"))||!(m.get("revision") instanceof Long))throw new IllegalArgumentException();
        String id=(String)m.get("id");UUID uuid=UUID.fromString(id);if(!uuid.toString().equals(id))throw new IllegalArgumentException();return new Subject((String)m.get("type"),uuid,(Long)m.get("revision"),null);
    }
    static CommandAuthorizationBinding.OpportunityRepair input(CommandEnvelope e){
        try{require(e.taskPrecondition()==null&&e.draftPrecondition()==null&&e.identityPrecondition()==null,"VALIDATION_FAILED");
            if(!(e.payload() instanceof Map<?,?> p)||!p.keySet().equals(Set.of("opportunity","task","ownerAppointmentId","basis","draft","takeoverFacts"))||!(p.get("takeoverFacts") instanceof List<?> downstream))throw new IllegalArgumentException();
            String owner=(String)p.get("ownerAppointmentId");UUID ownerId=UUID.fromString(owner);if(!ownerId.toString().equals(owner))throw new IllegalArgumentException();
            var b=new CommandAuthorizationBinding.OpportunityRepair(selector(p.get("opportunity")),selector(p.get("task")),ownerId,selector(p.get("basis")),p.get("draft")==null?null:selector(p.get("draft")),downstream.stream().map(R2SalesChainRepairCommand::selector).toList());
            CommandScope.opportunityRepair(e.actor().tenantId(),b);return b;
        }catch(IllegalArgumentException|ClassCastException|NullPointerException ex){throw new Rejected("VALIDATION_FAILED");}
    }
    public Context resolve(Connection c,CommandEnvelope e)throws SQLException {
        var b=input(e);var header=opportunities.header(c,e.actor().tenantId(),b.opportunity().id());require(header!=null,"NOT_AUTHORIZED");
        var org=OpportunityOwnerExceptionAuthorityReader.databaseBacked().historicalOrganization(c,e.actor().tenantId(),header.owner());require(org!=null,"NOT_AUTHORIZED");
        var request=R1AuthorityReader.databaseBacked().select(c,e.actor(),b.opportunity(),org,"R2_OPPORTUNITY_SYSTEM","OPPORTUNITY_TASK_RECOVER");require(request!=null,"NOT_AUTHORIZED");
        return new Context(CommandScope.opportunityRepair(e.actor().tenantId(),b),request,b);
    }
    public void lockRoots(Connection c,CommandEnvelope e,Context ctx)throws SQLException {var b=input(e);opportunities.lock(c,e.actor().tenantId(),b.opportunity().id());tasks.lock(c,e.actor().tenantId(),b.task().id());}
    public void recoveryEligibility(Connection c,CommandEnvelope e,Context ctx)throws SQLException {validateBeforeWork(c,e,ctx);}
    private void root(Connection c,CommandEnvelope e)throws SQLException {
        var b=input(e);var tenant=e.actor().tenantId();var h=opportunities.header(c,tenant,b.opportunity().id());
        require(h!=null&&h.selector().equals(b.opportunity()),"STALE_SUBJECT");
        var owner=OpportunityResponsibilityReader.databaseBacked().current(c,tenant,b.opportunity());require(owner.basis().equals(b.basis())&&owner.appointmentId().equals(b.owner()),"STALE_SUBJECT");
        var downstream=contracts.takeoverFacts(c,tenant,b.opportunity().id());
        require(!downstream.isEmpty()&&downstream.size()==b.takeover().size()&&new HashSet<>(downstream).equals(new HashSet<>(b.takeover())),"STALE_SUBJECT");
    }
    public void validateBeforeWork(Connection c,CommandEnvelope e,Context ctx)throws SQLException {
        root(c,e);var b=input(e);var tenant=e.actor().tenantId();var task=current.read(c,tenant,b.task().id());
        require(task!=null&&task.type()==TaskFactory.Type.PROGRESS_OPPORTUNITY&&task.selector().equals(b.task())&&task.subject().equals(b.opportunity())&&task.owner().equals(b.owner())&&task.responsibilityBasis().equals(b.basis())&&Set.of("OPEN","WAITING").contains(task.state()),"STALE_TASK");
        var metadata=EventResponsibilityReader.databaseBacked().task(c,tenant,b.task().id());var draft=metadata.draft();
        require(Objects.equals(b.draft(),draft==null?null:draft.selector())&&(draft==null||!"DRAFT".equals(draft.state())),"STALE_DRAFT");
    }
    public Result execute(Connection c,CommandEnvelope e,Context ctx)throws SQLException {
        var b=input(e);var tenant=e.actor().tenantId();tasks.cancelForContract(c,tenant,tasks.read(c,tenant,b.task().id()),"CONTRACT_WORKFLOW_TAKEOVER",tasks.now(c));
        return Result.succeeded(tasks.read(c,tenant,b.task().id()).selector(),Event.OpportunitySupersededTaskRepairedV1);
    }
    static boolean result(Connection c,UUID tenant,CommandAuthorizationBinding.OpportunityRepair b,Subject fact)throws SQLException {
        var reader=CurrentTaskReader.databaseBacked();var task=reader.read(c,tenant,b.task().id());
        return task!=null&&task.selector().equals(fact)&&fact.id().equals(b.task().id())&&fact.revision()==b.task().revision()+1&&task.type()==TaskFactory.Type.PROGRESS_OPPORTUNITY&&task.subject().equals(b.opportunity())&&task.owner().equals(b.owner())&&task.responsibilityBasis().equals(b.basis())&&"CANCELLED".equals(task.state())&&task.completion()==null&&"CONTRACT_WORKFLOW_TAKEOVER".equals(reader.cancellationReason(c,tenant,b.task().id()));
    }
    public void validateBeforeCommit(Connection c,CommandEnvelope e,Context ctx,Result result)throws SQLException {root(c,e);require(result(c,e.actor().tenantId(),input(e),result.fact()),"STALE_TASK");}
}
