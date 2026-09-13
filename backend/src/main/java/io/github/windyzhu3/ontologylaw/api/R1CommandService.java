package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService;
import io.github.windyzhu3.ontologylaw.lead.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.sql.*;
import java.util.*;

/** API composition only. Runtime owns locking/authorization/commit; Owners own every fact read and write. */
final class R1CommandService {
    record Response(int status,Map<String,Object> body,String etag,String errorCode,Map<String,Object> receiptRef,Map<String,Object> currentETag) {
        Response(int status,Map<String,Object> body,String etag,String errorCode,Map<String,Object> receiptRef){this(status,body,etag,errorCode,receiptRef,null);}
    }
    private final RuntimeDatabase database;
    private final CommandRuntime runtime;
    private final TaskFactory tasks=TaskFactory.databaseBacked();
    private final ActionDraftService drafts=ActionDraftService.databaseBacked();
    private final CurrentLeadReader leads;
    private final R1TaskPreconditionRuntime tags;
    R1CommandService(RuntimeDatabase database,R1SourcePolicyRegistry sources,LeadProtection protection,R1ServiceSourceBinding services,String node) {
        this.database=database;leads=CurrentLeadReader.databaseBacked(protection);
        var handlers=new ArrayList<>(new LeadCommands(sources,protection).handlers());handlers.addAll(new ActionDraftCommands(protection).handlers());
        runtime=new CommandRuntime(handlers,AuthorizationService.databaseBacked(),AuditAppender.databaseBacked(node),R1AuthorizationReaders.databaseBacked(sources,services),R1EventReaders.databaseBacked());
        tags=new R1TaskPreconditionRuntime(R1AuthorizationReaders.databaseBacked(sources,services),this::tag);
    }
    Map<String,Object> precondition(io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor actor,CommandEnvelope.Type operation,UUID taskId,String kind){
        try(var c=database.open()){String value=tags.read(c,actor,operation,taskId,R1TaskPreconditionRuntime.Kind.valueOf(kind));return value==null?null:Map.of("resourceKind",kind,"value",value);}
        catch(SQLException unavailable){throw new R1HttpFailure("SERVICE_UNAVAILABLE");}
    }
    private String tag(Connection c,io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor actor,UUID taskId,R1TaskPreconditionRuntime.Kind kind)throws SQLException {
        var task=tasks.read(c,actor.tenantId(),taskId);if(task==null)return null;
        return switch(kind){case TASK->R1ResourceTags.task(actor,task.selector(),task.state());case DRAFT->{var draft=drafts.read(c,actor.tenantId(),taskId);yield draft==null?null:R1ResourceTags.draft(actor,draft.selector(),draft.state());}case SUBJECT->{var lead=leads.selector(c,actor.tenantId(),task.lead().id());yield lead==null?null:R1ResourceTags.subject(actor,lead);}};
    }
    Response execute(CommandEnvelope envelope) {
        try(var c=database.open()) {return runtime.executeProjected(c,envelope,(tx,result)->project(tx,envelope,result));}
        catch(CommandHandler.Rejected rejected){return new Response(0,null,null,rejected.code(),null);}
        catch(SQLException unavailable){return new Response(503,null,null,"SERVICE_UNAVAILABLE",null);}
    }
    private Response project(Connection c,CommandEnvelope e,CommandResult result)throws SQLException {
        if(result instanceof CommandResult.Conflict)return new Response(409,null,null,"COMMAND_PAYLOAD_CONFLICT",reference(e));
        var outcome=(CommandOutcome)result;
        if(outcome.status()==CommandOutcome.Status.REJECTED){
            Map<String,Object> currentTag=null;String kind=ProblemDetailsAdvice.tagKind(outcome.rejectionCode());UUID task=e.taskPrecondition()!=null?e.taskPrecondition().taskId():e.draftPrecondition()!=null?e.draftPrecondition().taskId():null;
            if(kind!=null&&task!=null){String value=tags.current(c,e.actor(),e.type(),task,R1TaskPreconditionRuntime.Kind.valueOf(kind));if(value!=null)currentTag=Map.of("resourceKind",kind,"value",value);}
            return new Response(0,null,null,outcome.rejectionCode(),reference(e),currentTag);
        }
        var receipt=CommandReceiptReader.databaseBacked().read(c,e.actor().tenantId(),e.commandId());
        if(receipt==null||!receipt.outcome().equals(outcome))throw new SQLException("Receipt projection unavailable","XX000");
        var body=receipt.projection(e.actor());
        if(e.type().recovery()){
            var task=tasks.read(c,e.actor().tenantId(),UUID.fromString((String)((Map<?,?>)e.payload()).get("taskId")));
            if(task==null)throw new SQLException("Task projection unavailable","XX000");
            return new Response(200,body,R1ResourceTags.task(e.actor(),task.selector(),task.state()),null,null);
        }
        if(e.type()!=CommandEnvelope.Type.SAVE_ACTION_DRAFT)return new Response(e.type()==CommandEnvelope.Type.CAPTURE_LEAD?201:200,body,null,null,null);
        // Replay intentionally returns the immutable receipt alongside the currently authorized Draft.
        var task=tasks.read(c,e.actor().tenantId(),e.draftPrecondition().taskId());
        var draft=drafts.read(c,e.actor().tenantId(),e.draftPrecondition().taskId());
        if(task==null||draft==null||!draft.taskId().equals(task.selector().id())||!task.type().command.equals(draft.actionCode())||draft.schemaVersion()!=1)
            throw new SQLException("Draft projection unavailable","XX000");
        var values=CurrentLeadReader.validatedDraftValues(draft.actionCode(),draft.values());
        if(!Base64.getUrlEncoder().withoutPadding().encodeToString(CanonicalJson.digest(CanonicalJson.encode(values))).equals(draft.digest()))throw new SQLException("Draft projection unavailable","XX000");
        var lead=leads.selector(c,e.actor().tenantId(),task.lead().id());
        if(lead==null)throw new SQLException("Draft projection unavailable","XX000");
        var owner=e.actor().onBehalfAppointmentId()==null?e.actor().appointmentId():e.actor().onBehalfAppointmentId();
        String etag=R1ResourceTags.draft(e.actor(),draft.selector(),draft.state());
        var projection=new TreeMap<String,Object>();projection.put("draftId",draft.selector().id().toString());projection.put("draftRevision",draft.selector().revision());projection.put("actionCode",draft.actionCode());projection.put("schemaVersion",1);projection.put("values",values);projection.put("digest",draft.digest());projection.put("updatedAt",draft.updatedAt().toString());projection.put("editable","DRAFT".equals(draft.state())&&"OPEN".equals(task.state())&&task.owner().equals(owner));
        var preconditions=Map.of("taskETag",R1ResourceTags.task(e.actor(),task.selector(),task.state()),"draftETag",etag,"subjectETag",R1ResourceTags.subject(e.actor(),lead));
        return new Response(e.draftPrecondition().ifNoneMatch()!=null?201:200,Map.of("receipt",body,"draft",Collections.unmodifiableMap(projection),"preconditions",preconditions),etag,null,null);
    }
    static Map<String,Object> reference(CommandEnvelope e){return Map.of("commandId",e.commandId().toString(),"href","/api/v1/commands/"+e.commandId()+"/receipt");}
}
