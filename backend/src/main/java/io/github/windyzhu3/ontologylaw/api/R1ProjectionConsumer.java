package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.api.adapter.generated.model.ConsumeR1ProjectionV1;
import io.github.windyzhu3.ontologylaw.lead.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.responsibility.EventResponsibilityReader;
import java.sql.*;
import java.time.Instant;
import java.util.*;
public final class R1ProjectionConsumer {
    public record Response(int status,String errorCode) {}
    private final R1SourcePolicyRegistry policies;
    public R1ProjectionConsumer(R1SourcePolicyRegistry policies) {this.policies=Objects.requireNonNull(policies);}
    public Response consume(Connection c,Actor actor,ConsumeR1ProjectionV1 request){
        try{return R1ServiceReadRuntime.read(c,actor,(connection,now)->{
            if(!R1ServiceAuthorityReader.databaseBacked().projectionCoverage(connection,actor,Set.of(),now))throw forbidden();
            if(request==null)throw new R1ServiceReadRuntime.Failure(400,"VALIDATION_FAILED");
            var token=new R1ProjectionClaimReader.Token(request.getDomainEventOutboxId(),request.getDomainEventId(),request.getExpectedOutboxRevision(),request.getLeaseOwner(),request.getFencingToken());
            var claims=R1ProjectionClaimReader.databaseBacked();var event=claims.read(connection,actor.tenantId(),token);
            route(connection,actor,event,now);
            // No event payload is an authority source. Recheck the current lease immediately before commit.
            claims.read(connection,actor.tenantId(),token);
            return new Response(204,null);
        });}catch(R1ServiceReadRuntime.Failure failure){return new Response(failure.status(),failure.code());}
    }
    private void route(Connection c,Actor actor,R1ProjectionClaimReader.Notification event,Instant now)throws SQLException{
        var facts=R1EventReaders.databaseBacked();var responsibility=EventResponsibilityReader.databaseBacked();var source=event.source();var tenant=actor.tenantId();
        switch(event.type()){
            case LeadCapturedV1,LeadIngressCompletedV1 -> {
                var lead=require(facts.leadAnchor(c,tenant,source.id()));mutable(source,lead.selector());
                if(event.type()==CommandHandler.Event.LeadCapturedV1){
                    var policy=policies.find(lead.sourceAccount());if(policy==null)throw forbidden();var organization=AuthorizationIdentityReader.databaseBacked().organization(c,tenant,policy.sourceIntakeRootCode());if(organization==null)throw forbidden();authorize(c,actor,organization.id(),List.of(lead.selector()));
                }else{var task=require(responsibility.completedIngress(c,tenant,source.id(),source.revision()));authorize(c,actor,owner(c,actor,task.owner(),now),List.of(lead.selector(),task.selector()));}
            }
            case ActionDraftSavedV1 -> {
                var draft=require(responsibility.draft(c,tenant,source.id()));mutable(source,draft.selector());var task=require(facts.task(c,tenant,draft.taskId()));r1(task);var lead=lead(c,facts,tenant,task);
                if(task.draft()==null||!task.draft().selector().id().equals(draft.selector().id())||!draft.action().equals(task.primaryCommand())||draft.version()!=1)throw invalid();
                authorize(c,actor,owner(c,actor,task.owner(),now),List.of(task.selector(),lead));
            }
            case ContactTaskReopenedV1,RoutingReviewTaskReopenedV1 -> {
                var task=require(facts.task(c,tenant,source.id()));mutable(source,task.selector());r1(task);boolean contact=event.type()==CommandHandler.Event.ContactTaskReopenedV1;
                if(!task.purpose().equals(contact?"CONTACT_LEAD":"RESOLVE_LEAD_ROUTING_GAP"))throw invalid();
                var wait=require(facts.latestWait(c,tenant,source.id()));if(wait.version()!=1||!wait.profile().equals(contact?"CONTACT_RETRY_V1":"R1_ROUTING_REVIEW_WAIT_V1")||wait.taskRevision()>task.selector().revision()||"WAITING".equals(task.state())&&wait.taskRevision()!=task.selector().revision())throw invalid();
                authorize(c,actor,owner(c,actor,task.owner(),now),List.of(task.selector(),lead(c,facts,tenant,task)));
            }
            case LeadAssignedV1 -> {
                var assignment=require(facts.assignment(c,tenant,source.id()));mutable(source,assignment.selector());var lead=require(facts.leadAnchor(c,tenant,assignment.leadId()));
                if(lead.currentAssignment()!=null&&!require(facts.assignment(c,tenant,lead.currentAssignment())).leadId().equals(lead.selector().id()))throw invalid();
                // Assignment identity is retained lineage; later current-assignment changes do not rewrite this event.
                authorize(c,actor,owner(c,actor,assignment.owner(),now),List.of(assignment.selector(),lead.selector()));
            }
            case LeadDuplicateResolutionRecordedV1,LeadRoutingDispositionRecordedV1,SourceIntakeStopRequestedV1,SourceIntakeStopRequestAcknowledgedV1,LeadValidityReviewedV1 -> {
                var decision=require(facts.decision(c,tenant,source.id()));if(!source.equals(decision.selector())||decision.version()!=1)throw invalid();var task=require(facts.task(c,tenant,decision.taskId()));r1(task);
                if(!"DONE".equals(task.state())||!source.equals(task.completion()))throw invalid();var lead=lead(c,facts,tenant,task);
                if(!"lead.lead".equals(decision.subject().type())||!lead.id().equals(decision.subject().id()))throw invalid();
                boolean valid=switch(event.type()){
                    case LeadDuplicateResolutionRecordedV1 -> task.purpose().equals("RESOLVE_LEAD_DUPLICATE")&&decision.contract().equals("LEAD_DUPLICATE_RESOLUTION")&&Set.of("KEEP_SEPARATE","LINK_EXISTING_PARTY").contains(decision.code());
                    case LeadRoutingDispositionRecordedV1 -> task.purpose().equals("RESOLVE_LEAD_ROUTING_GAP")&&decision.contract().equals("LEAD_ROUTING_DISPOSITION")&&Set.of("SCHEDULE_ROUTING_REVIEW","RETRY_ASSIGNMENT_NOW").contains(decision.code());
                    case SourceIntakeStopRequestedV1 -> task.purpose().equals("RESOLVE_LEAD_ROUTING_GAP")&&decision.contract().equals("LEAD_ROUTING_DISPOSITION")&&decision.code().equals("REQUEST_SOURCE_INTAKE_STOP");
                    case SourceIntakeStopRequestAcknowledgedV1 -> task.purpose().equals("ACK_SOURCE_INTAKE_STOP_REQUEST")&&decision.contract().equals("SOURCE_INTAKE_STOP_REQUEST_ACKNOWLEDGED")&&decision.code().equals("SOURCE_INTAKE_STOP_REQUEST_ACKNOWLEDGED");
                    case LeadValidityReviewedV1 -> task.purpose().equals("REVIEW_LEAD_VALIDITY")&&decision.contract().equals("LEAD_VALIDITY_REVIEW")&&Set.of("CONFIRM_INVALID","CLOSE_UNREACHED","REOPEN_CONTACT").contains(decision.code());
                    default -> false;
                };if(!valid)throw invalid();authorize(c,actor,owner(c,actor,task.owner(),now),List.of(decision.selector(),task.selector(),lead));
            }
            case LeadContactResultRecordedV1,LeadContactRetryExhaustedV1 -> {
                var contact=contact(c,actor,facts,source.id(),now,null);if(!source.equals(contact.selector()))throw invalid();
                boolean exhausted=contact.code().equals("NOT_CONNECTED")&&contact.contactNo()>=3;
                if((event.type()==CommandHandler.Event.LeadContactRetryExhaustedV1)!=exhausted||!Set.of("CONNECTED_VALID","NOT_CONNECTED","SUSPECT_INVALID").contains(contact.code()))throw invalid();
            }
            case OpportunityOpened -> {
                var opportunity=require(facts.opportunity(c,tenant,source.id()));mutable(source,opportunity.selector());
                var contact=contact(c,actor,facts,opportunity.contactId(),now,opportunity.selector());var assignment=require(facts.assignment(c,tenant,opportunity.assignmentId()));
                if(!contact.code().equals("CONNECTED_VALID")||!opportunity.leadId().equals(contact.leadId())||!opportunity.assignmentId().equals(contact.assignmentId())||!opportunity.owner().equals(assignment.owner()))throw invalid();
            }
        }
    }
    private R1EventFacts.Contact contact(Connection c,Actor actor,R1EventFacts facts,UUID id,Instant now,Subject opportunity)throws SQLException{
        var anchor=require(facts.contactAnchor(c,actor.tenantId(),id));var task=require(facts.task(c,actor.tenantId(),anchor.taskId()));var assignment=require(facts.assignment(c,actor.tenantId(),anchor.assignmentId()));var lead=require(facts.lead(c,actor.tenantId(),anchor.leadId()));
        if(!task.purpose().equals("CONTACT_LEAD")||!"DONE".equals(task.state())||!assignment.leadId().equals(lead.id())||!task.lead().id().equals(lead.id())||!task.owner().equals(assignment.owner()))throw invalid();
        var subjects=new ArrayList<>(List.of(task.selector(),assignment.selector(),lead));if(opportunity!=null)subjects.addFirst(opportunity);
        authorize(c,actor,owner(c,actor,assignment.owner(),now),subjects);
        // This is the first call that reads summary, and hashing stays entirely inside the Lead Owner.
        var result=require(facts.contact(c,actor.tenantId(),id));if(!result.selector().equals(task.completion()))throw invalid();return result;
    }
    private static Subject lead(Connection c,R1EventFacts facts,UUID tenant,R1EventFacts.Task task)throws SQLException{
        if(task.lead()==null||!"lead.lead".equals(task.lead().type()))throw invalid();return require(facts.lead(c,tenant,task.lead().id()));
    }
    private static void r1(R1EventFacts.Task task){try{io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.Type.valueOf(task.purpose());}catch(IllegalArgumentException failure){throw invalid();}}
    private static UUID owner(Connection c,Actor actor,UUID appointment,Instant now)throws SQLException{var owner=AuthorizationIdentityReader.databaseBacked().owner(c,actor.tenantId(),appointment,now);if(owner==null)throw forbidden();return owner.organizationId();}
    private static void authorize(Connection c,Actor actor,UUID organization,List<Subject> subjects)throws SQLException{
        var request=R1AuthorityReader.databaseBacked().select(c,actor,subjects.getFirst(),organization,"SYSTEM_PROJECTION","R1_PROJECTION_CONSUME");if(request==null)throw forbidden();
        for(var subject:subjects)if(!AuthorizationService.databaseBacked().evaluate(c,new Request(actor,subject,organization,request.requirement()),true).allowed())throw forbidden();
    }
    private static void mutable(Subject source,Subject current){if(!source.type().equals(current.type())||!source.id().equals(current.id())||source.revision()==null||current.revision()==null||current.revision()<source.revision())throw invalid();}
    private static <T>T require(T value){if(value==null)throw new R1ServiceReadRuntime.Failure(404,"NOT_FOUND");return value;}
    private static R1ServiceReadRuntime.Failure invalid(){return new R1ServiceReadRuntime.Failure(422,"PROJECTION_EVENT_INVALID");}
    private static R1ServiceReadRuntime.Failure forbidden(){return new R1ServiceReadRuntime.Failure(403,"NOT_AUTHORIZED");}
}
