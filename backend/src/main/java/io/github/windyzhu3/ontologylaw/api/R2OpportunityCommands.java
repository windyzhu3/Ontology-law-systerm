package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Application composition under CommandRuntime's fence; no direct persistence or commit. */
final class R2OpportunityCommands {
    private static final CommandEnvelope.Type ACTION=CommandEnvelope.Type.RECORD_OPPORTUNITY_PROGRESS;
    private final TaskFactory tasks=TaskFactory.databaseBacked();
    private final ActionDraftService drafts=ActionDraftService.databaseBacked();
    private final OpportunityCommandReader opportunities;
    private final OpportunityProgressService progress;
    interface BusinessZone { ZoneId resolve(Connection c,Actor actor,Subject opportunity)throws SQLException; }
    private final BusinessZone zone;
    R2OpportunityCommands(OpportunityProgressProtection protection,ZoneId zone){this(protection,(c,a,o)->zone);}
    R2OpportunityCommands(OpportunityProgressProtection protection,BusinessZone zone){opportunities=OpportunityCommandReader.databaseBacked(protection);progress=R2OpportunityProgressServices.create(protection);this.zone=Objects.requireNonNull(zone);}
    BusinessZone businessZone(){return zone;}
    CommandHandler primary(){return new Handler(false,null);}
    CommandHandler draft(CommandHandler original){return new Handler(true,Objects.requireNonNull(original));}
    private static void require(boolean ok,String code){if(!ok)throw new CommandHandler.Rejected(code);}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object raw){require(raw instanceof Map<?,?>,"VALIDATION_FAILED");return (Map<String,Object>)raw;}
    private static String text(Map<String,Object> values,String key){require(values.get(key) instanceof String,"VALIDATION_FAILED");return (String)values.get(key);}
    private static long revision(Map<String,Object> values,String key){Object n=values.get(key);require(n instanceof Integer||n instanceof Long,"VALIDATION_FAILED");long value=((Number)n).longValue();require(value>=0&&value<=9007199254740991L,"VALIDATION_FAILED");return value;}
    private static OpportunityProgressInput input(Map<String,Object> values){
        require(values.keySet().equals(Set.of("progressTypeCode","progressSummary","occurredAt","nextCheckAt")),"VALIDATION_FAILED");
        try{return new OpportunityProgressInput(text(values,"progressTypeCode"),text(values,"progressSummary"),Instant.parse(text(values,"occurredAt")),Instant.parse(text(values,"nextCheckAt")));}
        catch(IllegalArgumentException|java.time.DateTimeException invalid){throw new CommandHandler.Rejected("VALIDATION_FAILED");}
    }
    static Map<String,Object> candidate(Map<String,Object> values){var in=input(values);return Map.of("progressTypeCode",in.type(),"progressSummary",in.summary(),"occurredAt",in.occurredAt().toString(),"nextCheckAt",in.nextCheckAt().toString());}
    private static ActionDraftService.Confirmation confirmation(CommandEnvelope e){
        var body=object(e.payload());try{
            UUID id=UUID.fromString(text(body,"draftId"));require(id.toString().equals(body.get("draftId")),"VALIDATION_FAILED");
            String digest=text(body,"draftDigest");new Subject("responsibility.action_draft",id,null,digest);
            return new ActionDraftService.Confirmation(id,revision(body,"expectedDraftRevision"),digest);
        }catch(IllegalArgumentException invalid){throw new CommandHandler.Rejected("VALIDATION_FAILED");}
    }
    private final class Handler implements CommandHandler {
        private final boolean saving;private final CommandHandler original;
        Handler(boolean saving,CommandHandler original){this.saving=saving;this.original=original;}
        private boolean legacy(CommandEnvelope e){return saving&&(!(e.payload() instanceof Map<?,?> p)||!ACTION.name().equals(p.get("actionCode")));}
        public CommandEnvelope.Type type(){return saving?CommandEnvelope.Type.SAVE_ACTION_DRAFT:ACTION;}
        private Map<String,Object> values(CommandEnvelope e){
            var body=object(e.payload());
            if(saving){require(body.keySet().equals(Set.of("actionCode","schemaVersion","values"))&&revision(body,"schemaVersion")==1,"VALIDATION_FAILED");return candidate(object(body.get("values")));}
            require(body.keySet().equals(Set.of("progressTypeCode","progressSummary","occurredAt","nextCheckAt","draftId","expectedDraftRevision","draftDigest")),"VALIDATION_FAILED");
            var value=new TreeMap<>(body);value.keySet().removeAll(Set.of("draftId","expectedDraftRevision","draftDigest"));return candidate(value);
        }
        private UUID taskId(CommandEnvelope e){
            if(!saving){require(e.draftPrecondition()==null&&e.taskPrecondition()!=null&&e.taskPrecondition().ifMatch()!=null,"TASK_PRECONDITION_REQUIRED");require(e.taskPrecondition().ifMatch().matches("\"task\\.[A-Za-z0-9_-]{43}\""),"VALIDATION_FAILED");return e.taskPrecondition().taskId();}
            var h=e.draftPrecondition();require(h!=null,"DRAFT_PRECONDITION_REQUIRED");require(e.taskPrecondition()==null,"VALIDATION_FAILED");
            require(h.ifMatch()!=null||h.ifNoneMatch()!=null,"DRAFT_PRECONDITION_REQUIRED");require(h.ifMatch()==null||h.ifNoneMatch()==null,"VALIDATION_FAILED");
            require(h.ifMatch()==null?"*".equals(h.ifNoneMatch()):h.ifMatch().matches("\"draft\\.[A-Za-z0-9_-]{43}\""),"VALIDATION_FAILED");return h.taskId();
        }
        public Context resolve(Connection c,CommandEnvelope e)throws SQLException{
            if(legacy(e))return original.resolve(c,e);
            values(e);UUID id=taskId(e),tenant=e.actor().tenantId();
            require(e.actor().onBehalfAppointmentId()==null,"NOT_AUTHORIZED");
            var task=tasks.read(c,tenant,id);require(task!=null,"NOT_FOUND");require(task.type()==TaskFactory.Type.PROGRESS_OPPORTUNITY,"VALIDATION_FAILED");
            var opportunity=opportunities.header(c,tenant,task.subject().id());require(opportunity!=null,"NOT_FOUND");
            var effective=OpportunityResponsibilityReader.databaseBacked().current(c,tenant,opportunity.selector());
            var taskBasis=CurrentTaskReader.databaseBacked().read(c,tenant,task.selector().id()).responsibilityBasis();
            require(task.owner().equals(e.actor().appointmentId()),"NOT_AUTHORIZED");
            var receipt=CommandReceiptReader.databaseBacked().read(c,tenant,e.commandId());if(receipt==null||!e.type().name().equals(receipt.commandType()))require(effective.appointmentId().equals(task.owner())&&effective.basis().equals(taskBasis),"NOT_AUTHORIZED");
            var owner=AuthorizationIdentityReader.databaseBacked().owner(c,tenant,task.owner(),tasks.now(c));require(owner!=null&&owner.active(),"NOT_AUTHORIZED");
            var organization=OpportunityOwnerExceptionAuthorityReader.databaseBacked().historicalOrganization(c,tenant,opportunity.owner());require(organization!=null,"NOT_AUTHORIZED");
            var request=R1AuthorityReader.databaseBacked().select(c,e.actor(),saving?task.selector():task.subject(),organization,task.type().slot,task.type().authority);
            require(request!=null,"NOT_AUTHORIZED");
            if(!saving){require(drafts.exists(c,tenant,id,confirmation(e).draftId()),"NOT_FOUND");return new Context(CommandScope.opportunity(tenant,id,task.subject()),request);}
            var draft=drafts.read(c,tenant,id);require(e.draftPrecondition().ifMatch()==null||draft!=null,"NOT_FOUND");
            return new Context(CommandScope.draft(tenant,id,ACTION),request,new CommandAuthorizationBinding.Draft(id,task.subject(),task.selector().revision(),draft==null?null:draft.selector().id(),draft==null?null:draft.selector().revision(),ACTION,1));
        }
        public void lockRoots(Connection c,CommandEnvelope e,Context ctx)throws SQLException{
            if(legacy(e)){original.lockRoots(c,e,ctx);return;}
            Subject subject=saving?((CommandAuthorizationBinding.Draft)ctx.binding()).lead():ctx.authorization().subject();
            opportunities.lock(c,e.actor().tenantId(),subject.id());tasks.lock(c,e.actor().tenantId(),ctx.scope().taskId());
        }
        public void recoveryEligibility(Connection c,CommandEnvelope e,Context ctx)throws SQLException{if(legacy(e))original.recoveryEligibility(c,e,ctx);}
        public void validateBeforeWork(Connection c,CommandEnvelope e,Context ctx)throws SQLException{
            if(legacy(e)){original.validateBeforeWork(c,e,ctx);return;}
            UUID tenant=e.actor().tenantId();var task=tasks.read(c,tenant,ctx.scope().taskId());require(task!=null,"NOT_FOUND");
            require(!"DONE".equals(task.state()),"TASK_ALREADY_COMPLETED");require("OPEN".equals(task.state()),"TASK_NOT_OPEN");
            var opportunity=opportunities.header(c,tenant,task.subject().id());require(opportunity!=null&&!opportunity.closed()&&opportunity.selector().equals(task.subject()),"STALE_SUBJECT");
            var effective=OpportunityResponsibilityReader.databaseBacked().current(c,tenant,opportunity.selector());
            var taskBasis=CurrentTaskReader.databaseBacked().read(c,tenant,task.selector().id()).responsibilityBasis();
            require(task.owner().equals(e.actor().appointmentId()),"NOT_AUTHORIZED");
            require(effective.appointmentId().equals(task.owner())&&effective.basis().equals(taskBasis),"NOT_AUTHORIZED");
            require(AuthorizationService.databaseBacked().evaluate(c,new AuthorizationService.Request(e.actor(),effective.basis(),ctx.authorization().scopeOrganizationId(),ctx.authorization().requirement()),true).allowed(),"NOT_AUTHORIZED");
            if(!saving){require(e.taskPrecondition().ifMatch().equals(R1ResourceTags.task(e.actor(),task.selector(),task.state(),taskBasis)),"STALE_TASK");drafts.validate(c,tenant,task,confirmation(e),values(e));return;}
            var binding=(CommandAuthorizationBinding.Draft)ctx.binding();require(task.selector().revision()==binding.taskRevision(),"STALE_TASK");
            var draft=drafts.read(c,tenant,ctx.scope().taskId());var h=e.draftPrecondition();
            if(h.ifNoneMatch()!=null)require(draft==null,"STALE_DRAFT");
            else{require(draft!=null,"NOT_FOUND");require(h.ifMatch().equals(R1ResourceTags.draft(e.actor(),draft.selector(),draft.state())),"STALE_DRAFT");require("DRAFT".equals(draft.state()),"DRAFT_DIGEST_MISMATCH");require(draft.selector().id().equals(binding.draftId())&&Objects.equals(draft.selector().revision(),binding.draftRevision()),"STALE_DRAFT");}
        }
        public Result execute(Connection c,CommandEnvelope e,Context ctx)throws SQLException{
            if(legacy(e))return original.execute(c,e,ctx);
            var task=tasks.read(c,e.actor().tenantId(),ctx.scope().taskId());var now=tasks.now(c);var value=values(e);
            if(saving){var saved=drafts.save(c,e.actor().tenantId(),task,drafts.read(c,e.actor().tenantId(),ctx.scope().taskId()),value,e.actor().appointmentId(),now);return saved.changed()?Result.succeeded(saved.draft().selector(),Event.OpportunityActionDraftSavedV1):Result.noChange(saved.draft().selector());}
            try{
                var in=input(value);in.validateAt(now);
                drafts.confirm(c,e.actor().tenantId(),task,confirmation(e),value,e.actor().appointmentId(),now);
                var result=progress.record(c,e.actor(),task.subject(),task.selector(),in,zone.resolve(c,e.actor(),task.subject()),now);
                return Result.succeeded(result.progress(),Event.OpportunityProgressRecordedV1);
            }catch(OpportunityProgressService.Blocked blocked){throw new Rejected("FORBIDDEN".equals(blocked.code())?"NOT_AUTHORIZED":blocked.code());}
            catch(IllegalArgumentException invalid){throw new Rejected("VALIDATION_FAILED");}
        }
        public void validateBeforeCommit(Connection c,CommandEnvelope e,Context ctx,Result result)throws SQLException{
            if(legacy(e)){original.validateBeforeCommit(c,e,ctx,result);return;}
            var task=tasks.read(c,e.actor().tenantId(),ctx.scope().taskId());var draft=drafts.read(c,e.actor().tenantId(),ctx.scope().taskId());
            require(task!=null&&draft!=null,"STALE_TASK");
            var effective=OpportunityResponsibilityReader.databaseBacked().current(c,e.actor().tenantId(),task.subject());
            require(effective.appointmentId().equals(task.owner())&&effective.basis().equals(CurrentTaskReader.databaseBacked().read(c,e.actor().tenantId(),task.selector().id()).responsibilityBasis()),"STALE_TASK");
            require(AuthorizationService.databaseBacked().evaluate(c,new AuthorizationService.Request(e.actor(),effective.basis(),ctx.authorization().scopeOrganizationId(),ctx.authorization().requirement()),true).allowed(),"NOT_AUTHORIZED");
            if(saving){var b=(CommandAuthorizationBinding.Draft)ctx.binding();require("OPEN".equals(task.state())&&task.completion()==null&&task.selector().revision()==b.taskRevision()&&result.fact().equals(draft.selector()),"STALE_DRAFT");}
            else{require("DONE".equals(task.state())&&result.fact().equals(task.completion())&&"CONFIRMED".equals(draft.state()),"STALE_TASK");var fact=opportunities.progress(c,e.actor().tenantId(),result.fact().id());require(fact!=null&&result.fact().equals(fact.selector()),"STALE_TASK");}
        }
    }
}


