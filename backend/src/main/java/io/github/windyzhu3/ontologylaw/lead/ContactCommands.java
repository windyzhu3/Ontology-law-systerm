package io.github.windyzhu3.ontologylaw.lead;

import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import io.github.windyzhu3.ontologylaw.opportunity.OpportunityOpeningService;
import io.github.windyzhu3.ontologylaw.evidence.EvidenceReferenceReader;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** Contact/review orchestration. Runtime owns authorization timing, locks and terminal writes. */
public final class ContactCommands {
    private final R1SourcePolicyRegistry sources;
    private final LeadProtection protection;
    private final LeadIngressService leads;
    private final ContactResultService contacts=ContactResultService.databaseBacked();
    private final TaskFactory tasks=TaskFactory.databaseBacked();
    private final ActionDraftService drafts=ActionDraftService.databaseBacked();
    private final AuthorizationIdentityReader identities=AuthorizationIdentityReader.databaseBacked();
    private final R1AuthorityReader authorities=R1AuthorityReader.databaseBacked();
    private final AuthorizationService authorization=AuthorizationService.databaseBacked();
    private final AssignmentPolicy assignmentPolicy=new AssignmentPolicy();
    private final Supplier<Instant> businessTime;
    public ContactCommands(R1SourcePolicyRegistry sources,LeadProtection protection){this(sources,protection,null);}
    ContactCommands(R1SourcePolicyRegistry sources,LeadProtection protection,Supplier<Instant> businessTime){this.sources=Objects.requireNonNull(sources);this.protection=Objects.requireNonNull(protection);this.leads=LeadIngressService.databaseBacked(protection);this.businessTime=businessTime;}
    public List<CommandHandler> handlers(){return List.of(new Contact(CommandEnvelope.Type.RECORD_CONTACT_RESULT),new Contact(CommandEnvelope.Type.REVIEW_LEAD_VALIDITY));}
    private static void require(boolean ok,String code){if(!ok)throw new CommandHandler.Rejected(code);}
    private static UUID represented(Actor actor){return actor.onBehalfAppointmentId()==null?actor.appointmentId():actor.onBehalfAppointmentId();}
    private Instant now(Connection c)throws SQLException{return businessTime==null?leads.now(c):businessTime.get();}
    private R1SourcePolicyRegistry.SourcePolicy policy(String source){var p=sources.find(source);require(p!=null,"VALIDATION_FAILED");return p;}
    private static Map<String,Object> values(CommandEnvelope e){var p=LeadInputs.object(e.payload());p.remove("draftId");p.remove("expectedDraftRevision");p.remove("draftDigest");return LeadCommands.candidate(e.type(),p);}
    private static ActionDraftService.Confirmation confirmation(CommandEnvelope e){try{var p=LeadInputs.object(e.payload());return new ActionDraftService.Confirmation(LeadInputs.uuid(p,"draftId"),LeadInputs.revision(p,"expectedDraftRevision"),LeadInputs.hash(p,"draftDigest"));}catch(IllegalArgumentException ex){throw new CommandHandler.Rejected("VALIDATION_FAILED");}}
    private void check(Connection c,Request base,Subject subject)throws SQLException{
        var result=authorization.evaluate(c,new Request(base.actor(),subject,base.scopeOrganizationId(),base.requirement()),false);require(result.allowed(),result.rejectionCode()==null?"NOT_AUTHORIZED":result.rejectionCode());
    }
    private LeadIngressService.Assignment assignment(Connection c,UUID tenant,TaskFactory.Task task,LeadIngressService.Lead lead)throws SQLException{
        var a=lead.assignment()==null?null:leads.assignment(c,tenant,lead.assignment());
        require(a!=null&&a.lead().equals(lead.selector().id())&&a.owner().equals(task.owner())&&"OPEN".equals(a.state()),"STALE_SUBJECT");return a;
    }
    private final class Contact implements CommandHandler {
        private final CommandEnvelope.Type type;
        Contact(CommandEnvelope.Type type){this.type=type;}
        public CommandEnvelope.Type type(){return type;}
        private boolean review(){return type==CommandEnvelope.Type.REVIEW_LEAD_VALIDITY;}
        public Context resolve(Connection c,CommandEnvelope e)throws SQLException{
            require(e.taskPrecondition()!=null&&e.taskPrecondition().ifMatch()!=null,"TASK_PRECONDITION_REQUIRED");
            require(e.taskPrecondition().ifMatch().matches("\"task\\.[A-Za-z0-9_-]{43}\""),"VALIDATION_FAILED");
            var v=values(e);var confirm=confirmation(e);UUID tenant=e.actor().tenantId();var task=tasks.read(c,tenant,e.taskPrecondition().taskId());require(task!=null,"NOT_FOUND");
            require(task.type().command.equals(type.name()),"VALIDATION_FAILED");var lead=leads.header(c,tenant,task.lead().id());require(lead!=null,"NOT_FOUND");policy(lead.source());
            require(drafts.exists(c,tenant,task.selector().id(),confirm.draftId()),"NOT_FOUND");require(task.owner().equals(represented(e.actor())),"NOT_AUTHORIZED");
            var owner=identities.owner(c,tenant,task.owner(),leads.now(c));require(owner!=null&&owner.active(),"NOT_AUTHORIZED");
            var request=authorities.select(c,e.actor(),task.lead(),owner.organizationId(),task.type().slot,task.type().authority);require(request!=null,"NOT_AUTHORIZED");
            check(c,request,task.selector());check(c,request,lead.selector());
            CommandAuthorizationBinding.Contact evidenceBinding=null;
            if(v.containsKey("evidenceSubmissionId")){
                var qualified=EvidenceReferenceReader.databaseBacked().qualify(c,request,task.selector(),task.lead(),LeadInputs.uuid(v,"evidenceSubmissionId"));require(qualified!=null,"NOT_FOUND");
                evidenceBinding=new CommandAuthorizationBinding.Contact(qualified.reference().submission(),qualified.reference().binding());
            }
            var bindings=review()?Map.<String,Object>of("triggeringContactResultId",LeadInputs.uuid(v,"triggeringContactResultId"),"triggeringContactResultHash",v.get("triggeringContactResultHash")):
                Map.<String,Object>of("leadAssignmentId",LeadInputs.uuid(v,"leadAssignmentId"),"leadAssignmentRevision",LeadInputs.revision(v,"leadAssignmentRevision"));
            return new Context(CommandScope.task(tenant,type(),task.selector().id(),task.lead(),bindings),request,evidenceBinding);
        }
        public void lockRoots(Connection c,CommandEnvelope e,Context ctx)throws SQLException{leads.lock(c,e.actor().tenantId(),ctx.authorization().subject().id());tasks.lock(c,e.actor().tenantId(),ctx.scope().taskId());}
        public void recoveryEligibility(Connection c,CommandEnvelope e,Context ctx){}
        public void validateBeforeWork(Connection c,CommandEnvelope e,Context ctx)throws SQLException{
            UUID tenant=e.actor().tenantId();var task=tasks.read(c,tenant,ctx.scope().taskId());require(task!=null,"NOT_FOUND");require(!"DONE".equals(task.state()),"TASK_ALREADY_COMPLETED");require("OPEN".equals(task.state()),"TASK_NOT_OPEN");
            require(e.taskPrecondition().ifMatch().equals(R1ResourceTags.task(e.actor(),task.selector(),task.state())),"STALE_TASK");var lead=leads.read(c,tenant,task.lead().id());require(lead!=null&&lead.selector().equals(task.lead()),"STALE_SUBJECT");
            CommandHandler.nextRevision(task.selector().revision());check(c,ctx.authorization(),task.selector());check(c,ctx.authorization(),lead.selector());
            var v=values(e);drafts.validate(c,tenant,task,confirmation(e),v);
            if(review()){
                var trigger=ContactCausality.trigger(c,tenant,lead.selector().id(),task.createdAt(),CurrentLeadReader.databaseBacked(protection));
                require(trigger!=null&&trigger.selector().equals(new Subject("lead.lead_contact_result",LeadInputs.uuid(v,"triggeringContactResultId"),null,(String)v.get("triggeringContactResultHash"))),"STALE_SUBJECT");
                if("REOPEN_CONTACT".equals(v.get("decisionCode")))reviewAssignment(c,tenant,lead.selector(),lead.assignment(),policy(lead.source()));
                return;
            }
            contacts.nextNumber(c,tenant,lead.selector().id());var a=assignment(c,tenant,task,lead);
            require(a.selector().id().equals(LeadInputs.uuid(v,"leadAssignmentId"))&&a.selector().revision()==LeadInputs.revision(v,"leadAssignmentRevision"),"STALE_SUBJECT");
            require("PHONE".equals(v.get("contactChannelCode"))?lead.phone()!=null||lead.ingressPhone()!=null:lead.email()!=null||lead.ingressEmail()!=null,"VALIDATION_FAILED");
            var wait=EventResponsibilityReader.databaseBacked().latestWait(c,tenant,task.selector().id());
            if(wait!=null&&"CONTACT_RETRY_V1".equals(wait.profile())){
                var previous=contacts.latest(c,tenant,lead.selector().id());require(previous!=null,"STALE_SUBJECT");
                require(RetryPolicy.channel(previous.channel(),lead.phone()!=null||lead.ingressPhone()!=null,lead.email()!=null||lead.ingressEmail()!=null).equals(v.get("contactChannelCode")),"VALIDATION_FAILED");
            }
        }
        public Result execute(Connection c,CommandEnvelope e,Context ctx)throws SQLException{
            if(review())return executeReview(c,e,ctx);
            UUID tenant=e.actor().tenantId();var task=tasks.read(c,tenant,ctx.scope().taskId());var lead=leads.read(c,tenant,task.lead().id());var a=assignment(c,tenant,task,lead);var v=values(e);Instant at=now(c);var source=policy(lead.source());var zone=ZoneId.of(source.businessTimezone());
            long number=contacts.nextNumber(c,tenant,lead.selector().id());String result=(String)v.get("resultCode");UUID supervisor=null;
            if("SUSPECT_INVALID".equals(result)||"NOT_CONNECTED".equals(result)&&number>=3)supervisor=assignmentPolicy.unique(c,tenant,lead.selector(),source,TaskFactory.Type.REVIEW_LEAD_VALIDITY);
            drafts.confirm(c,tenant,task,confirmation(e),v,represented(e.actor()),at);
            UUID evidence=ctx.binding() instanceof CommandAuthorizationBinding.Contact b?b.submission().id():null;
            var fact=contacts.append(c,tenant,lead.selector().id(),a.selector().id(),task.selector().id(),number,(String)v.get("contactChannelCode"),result,(String)v.get("resultSummary"),evidence,at);
            tasks.complete(c,tenant,task,fact,at);
            if("CONNECTED_VALID".equals(result)){
                String need=(String)v.get("legalNeed");var opportunity=OpportunityOpeningService.databaseBacked().open(c,tenant,lead.selector(),a.selector(),fact,a.owner(),protection.encrypt(tenant,LeadProtection.Field.OPPORTUNITY_LEGAL_NEED,need),CanonicalJson.digest(need),at);
                return new Result(CommandOutcome.Status.SUCCEEDED,fact,List.of(new Notification(Event.LeadContactResultRecordedV1,fact),new Notification(Event.OpportunityOpened,opportunity)));
            }
            if(supervisor!=null){tasks.create(c,tenant,TaskFactory.Type.REVIEW_LEAD_VALIDITY,supervisor,lead.selector(),zone,at);return Result.succeeded(fact,"NOT_CONNECTED".equals(result)?Event.LeadContactRetryExhaustedV1:Event.LeadContactResultRecordedV1);}
            var resume=RetryPolicy.resumeAt(number,at,zone);var retry=tasks.createContactRetry(c,tenant,task.owner(),lead.selector(),zone,at,resume);tasks.waitUntil(c,tenant,retry,represented(e.actor()),resume,at);
            return Result.succeeded(fact,Event.LeadContactResultRecordedV1);
        }
        public void validateBeforeCommit(Connection c,CommandEnvelope e,Context ctx,Result result)throws SQLException{
            UUID tenant=e.actor().tenantId();var task=tasks.read(c,tenant,ctx.scope().taskId());var lead=leads.header(c,tenant,task.lead().id());require(lead!=null&&lead.selector().equals(task.lead()),"STALE_SUBJECT");
            check(c,ctx.authorization(),task.selector());check(c,ctx.authorization(),lead.selector());
            if(ctx.binding() instanceof CommandAuthorizationBinding.Contact b){
                var qualified=EvidenceReferenceReader.databaseBacked().qualify(c,ctx.authorization(),task.selector(),task.lead(),b.submission().id());
                require(qualified!=null&&qualified.reference().submission().equals(b.submission())&&qualified.reference().binding().equals(b.binding()),"NOT_FOUND");
            }
            if(review()){
                var trigger=ContactCausality.trigger(c,tenant,lead.selector().id(),task.createdAt(),CurrentLeadReader.databaseBacked(protection));
                require(trigger!=null&&trigger.selector().id().equals(LeadInputs.uuid(values(e),"triggeringContactResultId"))&&trigger.selector().hash().equals(values(e).get("triggeringContactResultHash")),"STALE_SUBJECT");
                require(result.fact().equals(task.completion())&&"DONE".equals(task.state()),"STALE_TASK");
                var active=tasks.activeForLead(c,tenant,lead.selector());boolean reopen="REOPEN_CONTACT".equals(values(e).get("decisionCode"));require(active.size()==(reopen?1:0),"STALE_TASK");
                if(reopen){var assigned=reviewAssignment(c,tenant,lead.selector(),lead.assignment(),policy(lead.source()));var next=active.getFirst();require(next.type()==TaskFactory.Type.CONTACT_LEAD&&next.owner().equals(assigned.owner())&&"OPEN".equals(next.state())&&next.selector().revision()==0,"STALE_TASK");}
                return;
            }
            var a=leads.assignment(c,tenant,lead.assignment());
            require(a!=null&&a.owner().equals(task.owner())&&a.lead().equals(lead.selector().id())&&"OPEN".equals(a.state())&&a.selector().id().equals(LeadInputs.uuid(values(e),"leadAssignmentId"))&&a.selector().revision()==LeadInputs.revision(values(e),"leadAssignmentRevision"),"STALE_SUBJECT");
            require("DONE".equals(task.state())&&result.fact().equals(task.completion()),"STALE_TASK");var active=tasks.activeForLead(c,tenant,lead.selector());String code=(String)values(e).get("resultCode");require(active.size()==("CONNECTED_VALID".equals(code)?0:1),"STALE_TASK");
            for(var successor:active){if(successor.type()==TaskFactory.Type.CONTACT_LEAD)require(successor.owner().equals(task.owner()),"STALE_SUBJECT");else require(assignmentPolicy.unique(c,tenant,lead.selector(),policy(lead.source()),TaskFactory.Type.REVIEW_LEAD_VALIDITY).equals(successor.owner()),"STALE_SUBJECT");}
        }
        private LeadIngressService.Assignment reviewAssignment(Connection c,UUID tenant,Subject lead,UUID assignmentId,R1SourcePolicyRegistry.SourcePolicy source)throws SQLException{
            var a=assignmentId==null?null:leads.assignment(c,tenant,assignmentId);
            require(a!=null&&a.lead().equals(lead.id())&&"OPEN".equals(a.state()),"STALE_SUBJECT");
            require(assignmentPolicy.sales(c,tenant,lead,source).stream().anyMatch(candidate->candidate.appointmentId().equals(a.owner())),"STALE_SUBJECT");return a;
        }
        private Result executeReview(Connection c,CommandEnvelope e,Context ctx)throws SQLException{
            UUID tenant=e.actor().tenantId();var task=tasks.read(c,tenant,ctx.scope().taskId());var lead=leads.read(c,tenant,task.lead().id());var v=values(e);Instant at=now(c);String decision=(String)v.get("decisionCode");var source=policy(lead.source());
            var a="REOPEN_CONTACT".equals(decision)?reviewAssignment(c,tenant,lead.selector(),lead.assignment(),source):null;
            var digest=new TreeMap<String,Object>();digest.put("tenantId",tenant.toString());digest.put("subject",Map.of("type",task.lead().type(),"id",task.lead().id().toString(),"revision",task.lead().revision()));digest.put("authoritySlot",task.type().slot);digest.put("decisionCode",decision);digest.put("rationaleSummary",v.get("rationaleSummary"));digest.put("triggeringContactResultId",v.get("triggeringContactResultId"));digest.put("triggeringContactResultHash",v.get("triggeringContactResultHash"));
            drafts.confirm(c,tenant,task,confirmation(e),v,represented(e.actor()),at);
            var fact=tasks.decision(c,tenant,task,represented(e.actor()),"LEAD_VALIDITY_REVIEW",decision,(String)v.get("rationaleSummary"),digest,at);tasks.complete(c,tenant,task,fact,at);
            if(a!=null)tasks.create(c,tenant,TaskFactory.Type.CONTACT_LEAD,a.owner(),lead.selector(),ZoneId.of(source.businessTimezone()),at);
            return Result.succeeded(fact,Event.LeadValidityReviewedV1);
        }
    }
}
