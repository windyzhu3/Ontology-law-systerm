package io.github.windyzhu3.ontologylaw.lead;

import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.sql.*;
import java.util.*;

/** The single Draft command orchestrator; all writes remain in the Responsibility Owner. */
public final class ActionDraftCommands {
    private final TaskFactory tasks=TaskFactory.databaseBacked();
    private final ActionDraftService drafts=ActionDraftService.databaseBacked();
    private final AuthorizationIdentityReader identity=AuthorizationIdentityReader.databaseBacked();
    private final R1AuthorityReader authorities=R1AuthorityReader.databaseBacked();
    private final LeadIngressService leads;
    public ActionDraftCommands(LeadProtection protection) { leads=LeadIngressService.databaseBacked(protection); }
    public List<CommandHandler> handlers() { return List.of(new Save()); }
    private static void require(boolean ok,String code) { if(!ok)throw new CommandHandler.Rejected(code); }
    private static UUID represented(Actor actor) { return actor.onBehalfAppointmentId()==null?actor.appointmentId():actor.onBehalfAppointmentId(); }
    private record Input(CommandEnvelope.Type action,Map<String,Object> values) {}
    private static Input input(CommandEnvelope e) {
        try {
            var body=LeadInputs.object(e.payload());
            LeadInputs.fields(body,Set.of("actionCode","schemaVersion","values"),Set.of());
            require(LeadInputs.revision(body,"schemaVersion")==1,"VALIDATION_FAILED");
            var action=CommandEnvelope.Type.valueOf(LeadInputs.string(body,"actionCode"));
            return new Input(action,LeadCommands.candidate(action,LeadInputs.object(body.get("values"))));
        } catch(IllegalArgumentException invalid) { throw new CommandHandler.Rejected("VALIDATION_FAILED"); }
    }
    private static CommandEnvelope.DraftPrecondition headers(CommandEnvelope e) {
        var h=e.draftPrecondition();
        require(h!=null,"DRAFT_PRECONDITION_REQUIRED");
        require(e.taskPrecondition()==null,"VALIDATION_FAILED");
        require(h.ifMatch()!=null||h.ifNoneMatch()!=null,"DRAFT_PRECONDITION_REQUIRED");
        require(h.ifMatch()==null||h.ifNoneMatch()==null,"VALIDATION_FAILED");
        require(h.ifMatch()==null?"*".equals(h.ifNoneMatch()):h.ifMatch().matches("\"draft\\.[A-Za-z0-9_-]{43}\""),"VALIDATION_FAILED");
        return h;
    }
    private final class Save implements CommandHandler {
        public CommandEnvelope.Type type() { return CommandEnvelope.Type.SAVE_ACTION_DRAFT; }
        public Context resolve(Connection c,CommandEnvelope e)throws SQLException {
            var h=headers(e);var value=input(e);UUID tenant=e.actor().tenantId();
            var task=tasks.read(c,tenant,h.taskId());require(task!=null,"NOT_FOUND");
            require(task.type().command.equals(value.action().name()),"VALIDATION_FAILED");
            require(leads.header(c,tenant,task.lead().id())!=null,"NOT_FOUND");
            var draft=drafts.read(c,tenant,h.taskId());
            require(h.ifMatch()==null||draft!=null,"NOT_FOUND");
            require(task.owner().equals(represented(e.actor())),"NOT_AUTHORIZED");
            var owner=identity.owner(c,tenant,task.owner(),leads.now(c));require(owner!=null&&owner.active(),"NOT_AUTHORIZED");
            var request=authorities.select(c,e.actor(),task.selector(),owner.organizationId(),task.type().slot,task.type().authority);
            require(request!=null,"NOT_AUTHORIZED");
            return new Context(CommandScope.draft(tenant,h.taskId(),value.action()),request,
                    new CommandAuthorizationBinding.Draft(h.taskId(),task.lead(),task.selector().revision(),
                            draft==null?null:draft.selector().id(),draft==null?null:draft.selector().revision(),value.action(),1));
        }
        public void lockRoots(Connection c,CommandEnvelope e,Context ctx)throws SQLException {
            var binding=(CommandAuthorizationBinding.Draft)ctx.binding();
            leads.lock(c,e.actor().tenantId(),binding.lead().id());tasks.lock(c,e.actor().tenantId(),binding.taskId());
        }
        public void recoveryEligibility(Connection c,CommandEnvelope e,Context ctx) {}
        public void validateBeforeWork(Connection c,CommandEnvelope e,Context ctx)throws SQLException {
            UUID tenant=e.actor().tenantId();var binding=(CommandAuthorizationBinding.Draft)ctx.binding();
            var task=tasks.read(c,tenant,binding.taskId());require(task!=null,"NOT_FOUND");
            require(!"DONE".equals(task.state()),"TASK_ALREADY_COMPLETED");require("OPEN".equals(task.state()),"TASK_NOT_OPEN");
            require(task.selector().revision()==binding.taskRevision(),"STALE_TASK");
            var lead=leads.header(c,tenant,task.lead().id());require(lead!=null&&lead.selector().equals(task.lead()),"STALE_TASK");
            var draft=drafts.read(c,tenant,binding.taskId());var h=headers(e);
            if(h.ifNoneMatch()!=null)require(draft==null,"STALE_DRAFT");
            else {
                require(draft!=null,"NOT_FOUND");
                require(h.ifMatch().equals(R1ResourceTags.draft(e.actor(),draft.selector(),draft.state())),"STALE_DRAFT");
                require("DRAFT".equals(draft.state()),"DRAFT_DIGEST_MISMATCH");
                require(draft.selector().id().equals(binding.draftId())&&Objects.equals(draft.selector().revision(),binding.draftRevision()),"STALE_DRAFT");
            }
        }
        public Result execute(Connection c,CommandEnvelope e,Context ctx)throws SQLException {
            var task=tasks.read(c,e.actor().tenantId(),ctx.scope().taskId());
            var saved=drafts.save(c,e.actor().tenantId(),task,drafts.read(c,e.actor().tenantId(),ctx.scope().taskId()),input(e).values(),represented(e.actor()),leads.now(c));
            return saved.changed()?Result.succeeded(saved.draft().selector(),Event.ActionDraftSavedV1):Result.noChange(saved.draft().selector());
        }
        public void validateBeforeCommit(Connection c,CommandEnvelope e,Context ctx,Result result)throws SQLException {
            var binding=(CommandAuthorizationBinding.Draft)ctx.binding();var task=tasks.read(c,e.actor().tenantId(),binding.taskId());
            var draft=drafts.read(c,e.actor().tenantId(),binding.taskId());
            require(task!=null&&"OPEN".equals(task.state())&&task.completion()==null&&task.selector().revision()==binding.taskRevision(),"STALE_TASK");
            require(draft!=null&&result.fact().equals(draft.selector()),"STALE_DRAFT");
        }
    }
}
