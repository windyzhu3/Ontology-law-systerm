package io.github.windyzhu3.ontologylaw.responsibility;

import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Single-Task recovery; no discovery, scheduling or business-clock injection. */
public final class WaitLifecycleService {
    @FunctionalInterface public interface LeadLocker {void lock(Connection c,UUID tenant,UUID lead)throws SQLException;}
    private final LeadLocker leadLocker;
    private final TaskFactory tasks=TaskFactory.databaseBacked();
    private final EventResponsibilityReader waits=EventResponsibilityReader.databaseBacked();
    private final AuthorizationIdentityReader identity=AuthorizationIdentityReader.databaseBacked();
    private final R1AuthorityReader authorities=R1AuthorityReader.databaseBacked();
    public WaitLifecycleService(LeadLocker leadLocker){this.leadLocker=Objects.requireNonNull(leadLocker);}
    public List<CommandHandler> handlers(){return List.of(new Recovery(true),new Recovery(false));}
    private static void require(boolean ok,String code){if(!ok)throw new CommandHandler.Rejected(code);}
    private record Input(UUID task,long revision,UUID waitId,String waitHash,Instant cutoff){}
    private static Input input(CommandEnvelope e){
        try{
            if(e.taskPrecondition()!=null||e.draftPrecondition()!=null||!(e.payload() instanceof Map<?,?> p)
                    ||!p.keySet().equals(Set.of("taskId","expectedTaskRevision","waitReceiptId","waitReceiptHash","dueCutoff")))throw new IllegalArgumentException();
            UUID task=uuid(p.get("taskId")),wait=uuid(p.get("waitReceiptId"));
            if(!(p.get("expectedTaskRevision") instanceof Long revision)||revision<0||revision>=9007199254740991L)throw new IllegalArgumentException();
            String hash=(String)p.get("waitReceiptHash");new Subject("responsibility.wait_receipt",wait,null,hash);
            String timestamp=(String)p.get("dueCutoff");
            if(timestamp==null||!timestamp.matches("\\d{4}-\\d{2}-\\d{2}[Tt]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,6})?(?:[Zz]|[+-]\\d{2}:\\d{2})"))throw new IllegalArgumentException();
            return new Input(task,revision,wait,hash,OffsetDateTime.parse(timestamp).toInstant());
        }catch(IllegalArgumentException|ClassCastException|DateTimeException ex){throw new CommandHandler.Rejected("VALIDATION_FAILED");}
    }
    private static UUID uuid(Object value){if(!(value instanceof String text)||!text.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw new IllegalArgumentException();return UUID.fromString(text);}
    private final class Recovery implements CommandHandler {
        private final boolean contact;
        Recovery(boolean contact){this.contact=contact;}
        public CommandEnvelope.Type type(){return contact?CommandEnvelope.Type.REOPEN_DUE_CONTACT_TASKS:CommandEnvelope.Type.REOPEN_DUE_ROUTING_REVIEW_TASKS;}
        public Context resolve(Connection c,CommandEnvelope e)throws SQLException{
            var p=input(e);UUID tenant=e.actor().tenantId();var task=tasks.read(c,tenant,p.task());require(task!=null,"NOT_AUTHORIZED");
            var owner=identity.owner(c,tenant,task.owner(),tasks.now(c));require(owner!=null&&owner.active(),"NOT_AUTHORIZED");
            var subject=new Subject("responsibility.task_occurrence",p.task(),p.revision(),null);
            var request=authorities.select(c,e.actor(),subject,owner.organizationId(),"SYSTEM_RECOVERY",contact?"CONTACT_TASK_RECOVER":"ROUTING_REVIEW_TASK_RECOVER");require(request!=null,"NOT_AUTHORIZED");
            return new Context(CommandScope.reopen(tenant,type(),p.task(),p.waitId(),p.waitHash()),request,new CommandAuthorizationBinding.Recovery(p.task(),task.lead(),p.revision(),p.waitId(),p.waitHash()));
        }
        public void lockRoots(Connection c,CommandEnvelope e,Context ctx)throws SQLException{
            var binding=(CommandAuthorizationBinding.Recovery)ctx.binding();leadLocker.lock(c,e.actor().tenantId(),binding.lead().id());tasks.lock(c,e.actor().tenantId(),binding.taskId());
        }
        public void recoveryEligibility(Connection c,CommandEnvelope e,Context ctx)throws SQLException{
            var p=input(e);UUID tenant=e.actor().tenantId();var task=tasks.read(c,tenant,p.task());var wait=waits.latestWait(c,tenant,p.task());
            require(task!=null&&task.type()==(contact?TaskFactory.Type.CONTACT_LEAD:TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP),"VALIDATION_FAILED");
            require(wait!=null&&wait.selector().equals(new Subject("responsibility.wait_receipt",p.waitId(),null,p.waitHash()))&&wait.taskRevision()==p.revision()
                &&wait.profile().equals(contact?"CONTACT_RETRY_V1":"R1_ROUTING_REVIEW_WAIT_V1")&&wait.version()==1
                &&!wait.resumeDue().isAfter(p.cutoff())&&!p.cutoff().isAfter(tasks.now(c)),"VALIDATION_FAILED");
            require(task.state().equals("WAITING")&&task.selector().revision()==p.revision()
                ||task.state().equals("OPEN")&&task.selector().revision()==p.revision()+1,"VALIDATION_FAILED");
        }
        public void validateBeforeWork(Connection c,CommandEnvelope e,Context ctx)throws SQLException{recoveryEligibility(c,e,ctx);}
        public Result execute(Connection c,CommandEnvelope e,Context ctx)throws SQLException{
            var task=tasks.read(c,e.actor().tenantId(),ctx.scope().taskId());
            if(task.state().equals("OPEN"))return Result.noChange(task.selector());
            var reopened=tasks.reopen(c,e.actor().tenantId(),task);
            return Result.succeeded(reopened.selector(),contact?Event.ContactTaskReopenedV1:Event.RoutingReviewTaskReopenedV1);
        }
        public void validateBeforeCommit(Connection c,CommandEnvelope e,Context ctx,Result result)throws SQLException{
            recoveryEligibility(c,e,ctx);var task=tasks.read(c,e.actor().tenantId(),ctx.scope().taskId());
            require(task.state().equals("OPEN")&&task.selector().equals(result.fact()),"VALIDATION_FAILED");
        }
    }
}
