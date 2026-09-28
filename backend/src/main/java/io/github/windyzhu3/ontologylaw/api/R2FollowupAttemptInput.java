package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import java.time.*;
import java.util.*;

record R2FollowupAttemptInput(CommandAuthorizationBinding.FollowupAttempt binding,OpportunityFollowupAttempt values) {
    static R2FollowupAttemptInput parse(CommandEnvelope e){try{
        require(e.type().followupAttempts()&&e.taskPrecondition()==null&&e.draftPrecondition()==null&&e.identityPrecondition()==null);
        var p=(Map<?,?>)e.payload();require(p.keySet().equals(Set.of("opportunityId","expectedOpportunityRevision","responsibilityBasis","task","waitReceipt","expectedWorkflow","values")));
        var opportunity=new Subject("opportunity.opportunity",uuid(p.get("opportunityId")),revision(p.get("expectedOpportunityRevision")),null);
        var responsibility=(Map<?,?>)p.get("responsibilityBasis");require(responsibility.keySet().equals(Set.of("type","id","revision"))&&Set.of("opportunity.opportunity","opportunity.responsibility_handoff").contains(responsibility.get("type")));
        var basis=new Subject((String)responsibility.get("type"),uuid(responsibility.get("id")),revision(responsibility.get("revision")),null);
        var task=selector(p.get("task"),"responsibility.task_occurrence",false);require(task!=null);
        var wait=selector(p.get("waitReceipt"),"responsibility.wait_receipt",true);var workflow=selector(p.get("expectedWorkflow"),"opportunity.quote_workflow",false);
        require((e.type()==CommandEnvelope.Type.RECORD_QUOTE_FOLLOWUP_ATTEMPT)==(workflow!=null));if(workflow!=null)require(workflow.revision()==0);
        var v=(Map<?,?>)p.get("values");require(v.keySet().equals(Set.of("type","summary","occurredAt","nextCheckAt")));
        var values=new OpportunityFollowupAttempt((String)v.get("type"),(String)v.get("summary"),Instant.parse((String)v.get("occurredAt")),Instant.parse((String)v.get("nextCheckAt")));
        return new R2FollowupAttemptInput(new CommandAuthorizationBinding.FollowupAttempt(opportunity,basis,task,wait,workflow),values);
    }catch(IllegalArgumentException|ClassCastException|NullPointerException|DateTimeException invalid){throw new CommandHandler.Rejected("VALIDATION_FAILED");}}
    static FollowupAttemptService.Basis basis(CommandAuthorizationBinding.FollowupAttempt b){return new FollowupAttemptService.Basis(b.opportunity(),b.basis(),b.task(),b.waitReceipt(),b.workflow());}
    private static Subject selector(Object value,String type,boolean hashed){if(value==null)return null;var p=(Map<?,?>)value;require(p.keySet().equals(Set.of("id",hashed?"hash":"revision")));return new Subject(type,uuid(p.get("id")),hashed?null:revision(p.get("revision")),hashed?(String)p.get("hash"):null);}
    private static UUID uuid(Object value){var id=UUID.fromString((String)value);require(id.toString().equals(value));return id;}
    private static long revision(Object value){require(value instanceof Long||value instanceof Integer);long v=((Number)value).longValue();require(v>=0&&v<=9007199254740991L);return v;}
    private static void require(boolean value){if(!value)throw new CommandHandler.Rejected("VALIDATION_FAILED");}
}
