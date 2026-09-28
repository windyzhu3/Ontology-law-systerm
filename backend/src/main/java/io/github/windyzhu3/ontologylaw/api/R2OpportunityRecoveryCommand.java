package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Closed internal SERVICE command; CommandRuntime owns locks, idempotency, audit and commit. */
final class R2OpportunityRecoveryCommand implements CommandHandler {
    private final TaskFactory tasks=TaskFactory.databaseBacked();
    private final OpportunityCommandReader opportunities;
    private final R2OpportunityFollowupServices recovery;
    R2OpportunityRecoveryCommand(OpportunityProgressProtection protection){opportunities=OpportunityCommandReader.databaseBacked(protection);recovery=R2OpportunityFollowupServices.create(protection);}
    private record Input(Subject opportunity,Subject task,Subject waitReceipt,Subject progress,Instant due) {}
    public CommandEnvelope.Type type(){return CommandEnvelope.Type.REOPEN_DUE_OPPORTUNITY_TASKS;}
    private static void require(boolean valid,String code){if(!valid)throw new Rejected(code);}
    private static long revision(Map<?,?> values,String name){Object n=values.get(name);require(n instanceof Integer||n instanceof Long,"VALIDATION_FAILED");long v=((Number)n).longValue();require(v>=0&&v<=9007199254740991L,"VALIDATION_FAILED");return v;}
    private static UUID uuid(Map<?,?> values,String name){var text=(String)values.get(name);var value=UUID.fromString(text);require(value.toString().equals(text),"VALIDATION_FAILED");return value;}
    private Input input(Connection c,CommandEnvelope e)throws SQLException{
        try{
            require(e.taskPrecondition()==null&&e.draftPrecondition()==null&&e.identityPrecondition()==null,"VALIDATION_FAILED");
            require(e.payload() instanceof Map<?,?>,"VALIDATION_FAILED");var values=(Map<?,?>)e.payload();
            require(values.keySet().equals(Set.of("opportunityId","expectedOpportunityRevision","taskId","expectedTaskRevision","waitReceiptId","waitReceiptHash","progressId","progressHash","dueCutoff")),"VALIDATION_FAILED");
            var due=Instant.parse((String)values.get("dueCutoff"));require(due.getNano()%1000==0,"VALIDATION_FAILED");
            return new Input(new Subject("opportunity.opportunity",uuid(values,"opportunityId"),revision(values,"expectedOpportunityRevision"),null),
                    new Subject("responsibility.task_occurrence",uuid(values,"taskId"),revision(values,"expectedTaskRevision"),null),
                    new Subject("responsibility.wait_receipt",uuid(values,"waitReceiptId"),null,(String)values.get("waitReceiptHash")),
                    new Subject(sourceType(c,e.actor().tenantId(),uuid(values,"taskId")),uuid(values,"progressId"),null,(String)values.get("progressHash")),due);
        }catch(IllegalArgumentException|ClassCastException|NullPointerException|DateTimeException invalid){throw new Rejected("VALIDATION_FAILED");}
    }
    private String sourceType(Connection c,UUID tenant,UUID id)throws SQLException {
        var task=tasks.read(c,tenant,id);
        return task==null?"opportunity.opportunity_progress":FollowupAttemptRecovery.source(c,tenant,id).type();
    }
    private boolean currentBasis(Connection c,UUID tenant,TaskFactory.Task task,Subject basis)throws SQLException {
        if(task.type()!=TaskFactory.Type.RECORD_QUOTE_REPLY||FollowupAttemptService.FACT.equals(FollowupAttemptRecovery.source(c,tenant,task.selector().id()).type()))return basis.equals(CurrentTaskReader.databaseBacked().read(c,tenant,task.selector().id()).responsibilityBasis());
        var source=QuoteFollowupRecovery.source(c,tenant,task.selector().id()).basis();
        return source.type().equals(basis.type())&&source.id().equals(basis.id())&&("opportunity.opportunity".equals(source.type())||source.revision().equals(basis.revision()));
    }
    public Context resolve(Connection c,CommandEnvelope e)throws SQLException {
        var in=input(c,e);var tenant=e.actor().tenantId();var task=tasks.read(c,tenant,in.task().id());
        require(task!=null&&Set.of(TaskFactory.Type.PROGRESS_OPPORTUNITY,TaskFactory.Type.RECORD_QUOTE_REPLY).contains(task.type())&&task.subject().equals(in.opportunity()),"NOT_FOUND");
        var owner=AuthorizationIdentityReader.databaseBacked().owner(c,tenant,task.owner(),tasks.now(c));require(owner!=null&&owner.active(),"NOT_AUTHORIZED");
        var opportunity=opportunities.header(c,tenant,in.opportunity().id());require(opportunity!=null,"NOT_FOUND");
        var organization=OpportunityOwnerExceptionAuthorityReader.databaseBacked().historicalOrganization(c,tenant,opportunity.owner());require(organization!=null,"NOT_AUTHORIZED");
        var request=R1AuthorityReader.databaseBacked().select(c,e.actor(),in.task(),organization,"R2_OPPORTUNITY_SYSTEM","OPPORTUNITY_TASK_RECOVER");require(request!=null,"NOT_AUTHORIZED");
        return new Context(CommandScope.opportunityRecovery(tenant,in.task().id(),in.opportunity(),in.waitReceipt(),in.progress()),request,
                new CommandAuthorizationBinding.Recovery(in.task().id(),in.opportunity(),in.task().revision(),in.waitReceipt().id(),in.waitReceipt().hash()));
    }
    public void lockRoots(Connection c,CommandEnvelope e,Context ctx)throws SQLException {var in=input(c,e);opportunities.lock(c,e.actor().tenantId(),in.opportunity().id());tasks.lock(c,e.actor().tenantId(),in.task().id());}
    public void recoveryEligibility(Connection c,CommandEnvelope e,Context ctx)throws SQLException {validateBeforeWork(c,e,ctx);}
    public void validateBeforeWork(Connection c,CommandEnvelope e,Context ctx)throws SQLException {
        var in=input(c,e);var tenant=e.actor().tenantId();var task=tasks.read(c,tenant,in.task().id());
        var wait=EventResponsibilityReader.databaseBacked().latestWait(c,tenant,in.task().id());
        var opportunity=opportunities.header(c,tenant,in.opportunity().id());
        require(opportunity!=null&&!opportunity.closed()&&opportunity.selector().equals(in.opportunity()),"STALE_SUBJECT");
        var effective=OpportunityResponsibilityReader.databaseBacked().current(c,tenant,opportunity.selector());
        require(task!=null&&task.selector().equals(in.task())&&"WAITING".equals(task.state())&&task.owner().equals(effective.appointmentId())&&currentBasis(c,tenant,task,effective.basis())
                &&wait!=null&&wait.selector().equals(in.waitReceipt())&&wait.taskRevision()==in.task().revision()&&wait.version()==1
                &&(task.type()==TaskFactory.Type.RECORD_QUOTE_REPLY?Set.of("R2_QUOTE_FOLLOWUP_V1","R2_QUOTE_HANDOFF_WAIT_V1","R2_SALES_ATTEMPT_WAIT_V1","R2_ATTEMPT_HANDOFF_WAIT_V1").contains(wait.profile()):Set.of("R2_OPPORTUNITY_FOLLOWUP_V1","R2_OPPORTUNITY_HANDOFF_WAIT_V1","R2_SALES_ATTEMPT_WAIT_V1","R2_ATTEMPT_HANDOFF_WAIT_V1").contains(wait.profile()))&&wait.resumeDue().equals(in.due())&&!in.due().isAfter(tasks.now(c)),"STALE_TASK");
        require(AuthorizationService.databaseBacked().evaluate(c,new Request(e.actor(),effective.basis(),ctx.authorization().scopeOrganizationId(),ctx.authorization().requirement()),true).allowed(),"NOT_AUTHORIZED");
        var origin=FollowupAttemptRecovery.source(c,tenant,in.task().id());
        require(in.progress().equals(origin),"STALE_PROGRESS");
    }
    public Result execute(Connection c,CommandEnvelope e,Context ctx)throws SQLException {
        var in=input(c,e);
        try{return Result.succeeded(recovery.reopen(c,e.actor().tenantId(),in.opportunity(),in.task(),in.waitReceipt(),in.progress(),in.due()),Event.OpportunityTaskReopenedV1);}
        catch(OpportunityProgressService.Blocked blocked){throw new Rejected("FORBIDDEN".equals(blocked.code())?"NOT_AUTHORIZED":blocked.code());}
    }
    public void validateBeforeCommit(Connection c,CommandEnvelope e,Context ctx,Result result)throws SQLException {
        var in=input(c,e);var task=tasks.read(c,e.actor().tenantId(),in.task().id());
        var effective=OpportunityResponsibilityReader.databaseBacked().current(c,e.actor().tenantId(),in.opportunity());
        require(AuthorizationService.databaseBacked().evaluate(c,new Request(e.actor(),effective.basis(),ctx.authorization().scopeOrganizationId(),ctx.authorization().requirement()),true).allowed(),"NOT_AUTHORIZED");
        require(task!=null&&effective.appointmentId().equals(task.owner())&&"OPEN".equals(task.state())&&task.selector().equals(result.fact())&&task.subject().equals(in.opportunity())&&task.selector().revision()==CommandHandler.nextRevision(in.task().revision()),"STALE_TASK");
    }
}


