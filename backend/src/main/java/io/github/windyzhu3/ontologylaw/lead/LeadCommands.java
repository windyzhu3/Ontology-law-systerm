package io.github.windyzhu3.ontologylaw.lead;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.sql.*;import java.time.*;import java.util.*;
import static io.github.windyzhu3.ontologylaw.execution.CommandHandler.Event.*;
public final class LeadCommands {
    private final R1SourcePolicyRegistry sources;private final LeadProtection protection;private final LeadIngressService leads;
    private final TaskFactory tasks=TaskFactory.databaseBacked();private final ActionDraftService drafts=ActionDraftService.databaseBacked();
    private final AuthorizationIdentityReader identity=AuthorizationIdentityReader.databaseBacked();private final R1AuthorityReader authorities=R1AuthorityReader.databaseBacked();
    private final AuthorizationService authorization=AuthorizationService.databaseBacked();private final AssignmentPolicy assignmentPolicy=new AssignmentPolicy();
    public LeadCommands(R1SourcePolicyRegistry sources,LeadProtection protection) {this.sources=Objects.requireNonNull(sources);this.protection=Objects.requireNonNull(protection);this.leads=LeadIngressService.databaseBacked(protection);}
    public List<CommandHandler> handlers(){return List.of(new Handler(CommandEnvelope.Type.CAPTURE_LEAD),new Handler(CommandEnvelope.Type.RESOLVE_DUPLICATE_LEAD),new Handler(CommandEnvelope.Type.COMPLETE_LEAD_INGRESS),new Handler(CommandEnvelope.Type.ASSIGN_LEAD),new Handler(CommandEnvelope.Type.RECORD_ROUTING_DISPOSITION),new Handler(CommandEnvelope.Type.ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST));}
    public static Map<String,Object> candidate(CommandEnvelope.Type type,Map<String,Object> values){try{return LeadInputs.candidate(type,values);}catch(IllegalArgumentException ex){throw new CommandHandler.Rejected("VALIDATION_FAILED");}}
    private static void require(boolean ok,String code){if(!ok)throw new CommandHandler.Rejected(code);}
    private static UUID represented(Actor actor){return actor.onBehalfAppointmentId()==null?actor.appointmentId():actor.onBehalfAppointmentId();}
    private R1SourcePolicyRegistry.SourcePolicy policy(String source){var p=sources.find(source);require(p!=null,"VALIDATION_FAILED");return p;}
    private byte[] sourceKey(CommandEnvelope e,Map<String,Object> p){return protection.hmac(e.actor().tenantId(),LeadProtection.Purpose.SOURCE_RECORD_KEY,(String)p.get("sourceAccountCode"),(String)p.get("sourceRecordKey"));}
    private static String base64(byte[] b){return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    private Map<String,Object> capture(CommandEnvelope e){try{var p=LeadInputs.capture(e.payload());policy((String)p.get("sourceAccountCode"));return p;}catch(IllegalArgumentException|java.time.DateTimeException ex){throw new CommandHandler.Rejected("VALIDATION_FAILED");}}
    private Map<String,Object> values(CommandEnvelope e){var p=LeadInputs.object(e.payload());p.remove("draftId");p.remove("expectedDraftRevision");p.remove("draftDigest");return candidate(e.type(),p);}
    private ActionDraftService.Confirmation confirmation(CommandEnvelope e){try{var p=LeadInputs.object(e.payload());return new ActionDraftService.Confirmation(LeadInputs.uuid(p,"draftId"),LeadInputs.revision(p,"expectedDraftRevision"),LeadInputs.hash(p,"draftDigest"));}catch(IllegalArgumentException ex){throw new CommandHandler.Rejected("VALIDATION_FAILED");}}
    private Map<String,Object> bindings(CommandEnvelope e,Map<String,Object> p){var b=new TreeMap<String,Object>();switch(e.type()){
        case ASSIGN_LEAD -> b.put("selectedOwnerAppointmentId",LeadInputs.uuid(p,"ownerAppointmentId"));
        case RESOLVE_DUPLICATE_LEAD -> {for(String key:List.of("candidateLeadId","partyId"))b.put(key,LeadInputs.uuid(p,key));for(String key:List.of("candidateLeadRevision","partyRevision"))b.put(key,LeadInputs.revision(p,key));}
        case ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST -> {b.put("causalDecisionId",LeadInputs.uuid(p,"causalDecisionId"));b.put("causalDecisionHash",p.get("causalDecisionHash"));}
        default -> {}
    }return b;}
    private record Plan(TaskFactory.Type type,UUID owner,boolean assignment,boolean waiting) {}
    private Plan next(Connection c,UUID tenant,LeadIngressService.Lead lead,boolean hasContact,boolean checkDuplicate,Instant now)throws SQLException{
        var p=policy(lead.source());TaskFactory.Type type;
        if(checkDuplicate&&leads.duplicate(c,tenant,lead,now)!=null)type=TaskFactory.Type.RESOLVE_LEAD_DUPLICATE;
        else if(!hasContact&&lead.missingContact()&&lead.ingressEmpty())type=TaskFactory.Type.COMPLETE_LEAD_INGRESS;
        else if(p.assignmentMode()==R1SourcePolicyRegistry.AssignmentMode.MANUAL)type=TaskFactory.Type.ASSIGN_LEAD;
        else {var candidates=assignmentPolicy.sales(c,tenant,lead.selector(),p);if(!candidates.isEmpty())return new Plan(TaskFactory.Type.CONTACT_LEAD,candidates.getFirst().appointmentId(),true,false);type=TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP;}
        return new Plan(type,assignmentPolicy.unique(c,tenant,lead.selector(),p,type),false,false);
    }
    private final class Handler implements CommandHandler {
        private final CommandEnvelope.Type type;Handler(CommandEnvelope.Type type){this.type=type;}public CommandEnvelope.Type type(){return type;}
        public Context resolve(Connection c,CommandEnvelope e)throws SQLException{
            UUID tenant=e.actor().tenantId();
            if(type==CommandEnvelope.Type.CAPTURE_LEAD){var p=capture(e);String account=(String)p.get("sourceAccountCode");var org=identity.organization(c,tenant,policy(account).sourceIntakeRootCode());require(org!=null,"NOT_AUTHORIZED");
                var request=authorities.select(c,e.actor(),org,org.id(),"SOURCE_INTAKE_OWNER","LEAD_CAPTURE");require(request!=null,"NOT_AUTHORIZED");String hash=base64(sourceKey(e,p));return new Context(CommandScope.capture(tenant,account,hash),request,new CommandAuthorizationBinding.Capture(account,hash,org));}
            require(e.taskPrecondition()!=null&&e.taskPrecondition().ifMatch()!=null,"TASK_PRECONDITION_REQUIRED");require(e.taskPrecondition().ifMatch().matches("\"task\\.[A-Za-z0-9_-]{43}\""),"VALIDATION_FAILED");
            var value=values(e);var confirm=confirmation(e);var task=tasks.read(c,tenant,e.taskPrecondition().taskId());require(task!=null,"NOT_FOUND");require(task.type().command.equals(type.name()),"VALIDATION_FAILED");
            var lead=leads.header(c,tenant,task.lead().id());require(lead!=null,"NOT_FOUND");policy(lead.source());require(drafts.exists(c,tenant,task.selector().id(),confirm.draftId()),"NOT_FOUND");
            require(task.owner().equals(represented(e.actor())),"NOT_AUTHORIZED");var owner=identity.owner(c,tenant,task.owner(),leads.now(c));require(owner!=null&&owner.active(),"NOT_AUTHORIZED");
            var request=authorities.select(c,e.actor(),task.lead(),owner.organizationId(),task.type().slot,task.type().authority);require(request!=null,"NOT_AUTHORIZED");
            check(c,request,task.selector(),false);check(c,request,lead.selector(),false);
            return new Context(CommandScope.task(tenant,type,task.selector().id(),task.lead(),bindings(e,value)),request);
        }
        public void lockRoots(Connection c,CommandEnvelope e,Context ctx)throws SQLException{
            if(type==CommandEnvelope.Type.CAPTURE_LEAD){var p=capture(e);leads.lockNatural(c,e.actor().tenantId(),(String)p.get("sourceAccountCode"),sourceKey(e,p));var existing=leads.natural(c,e.actor().tenantId(),(String)p.get("sourceAccountCode"),sourceKey(e,p));if(existing!=null)leads.lock(c,e.actor().tenantId(),existing.selector().id());}
            else {leads.lock(c,e.actor().tenantId(),ctx.authorization().subject().id());tasks.lock(c,e.actor().tenantId(),ctx.scope().taskId());}
        }
        public void recoveryEligibility(Connection c,CommandEnvelope e,Context ctx){}
        public void validateBeforeWork(Connection c,CommandEnvelope e,Context ctx)throws SQLException{
            if(type==CommandEnvelope.Type.CAPTURE_LEAD)return;
            var task=tasks.read(c,e.actor().tenantId(),ctx.scope().taskId());require(task!=null,"NOT_FOUND");require(!task.state().equals("DONE"),"TASK_ALREADY_COMPLETED");require(task.state().equals("OPEN"),"TASK_NOT_OPEN");
            require(e.taskPrecondition().ifMatch().equals(R1ResourceTags.task(e.actor(),task.selector(),task.state())),"STALE_TASK");
            var lead=leads.read(c,e.actor().tenantId(),task.lead().id());require(lead!=null&&lead.selector().equals(task.lead()),"STALE_SUBJECT");CommandHandler.nextRevision(task.selector().revision());
            check(c,ctx.authorization(),task.selector(),false);check(c,ctx.authorization(),lead.selector(),false);require(task.owner().equals(represented(e.actor())),"NOT_AUTHORIZED");
            var value=values(e);drafts.validate(c,e.actor().tenantId(),task,confirmation(e),value);
            if(type==CommandEnvelope.Type.COMPLETE_LEAD_INGRESS){require(lead.missingContact()&&lead.ingressEmpty(),"INGRESS_COMPLETION_ALREADY_RECORDED");CommandHandler.nextRevision(lead.selector().revision());}
            if(type==CommandEnvelope.Type.ASSIGN_LEAD){CommandHandler.nextRevision(lead.selector().revision());require(lead.assignment()==null&&!leads.hasOpenAssignment(c,e.actor().tenantId(),lead.selector().id()),"STALE_SUBJECT");require(assignmentPolicy.sales(c,e.actor().tenantId(),lead.selector(),policy(lead.source())).stream().anyMatch(a->a.appointmentId().equals(LeadInputs.uuid(value,"ownerAppointmentId"))),"STALE_SUBJECT");}
            if(type==CommandEnvelope.Type.RESOLVE_DUPLICATE_LEAD){CommandHandler.nextRevision(lead.selector().revision());var duplicate=leads.duplicate(c,e.actor().tenantId(),lead,task.createdAt());require(duplicate!=null&&duplicate.lead().equals(new Subject("lead.lead",LeadInputs.uuid(value,"candidateLeadId"),LeadInputs.revision(value,"candidateLeadRevision"),null))&&duplicate.party().equals(new Subject("party.party",LeadInputs.uuid(value,"partyId"),LeadInputs.revision(value,"partyRevision"),null)),"STALE_SUBJECT");}
            if(type==CommandEnvelope.Type.ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST){var causal=tasks.causalStop(c,e.actor().tenantId(),task);require(causal!=null&&causal.equals(new Subject("responsibility.decision_record",LeadInputs.uuid(value,"causalDecisionId"),null,(String)value.get("causalDecisionHash"))),"STALE_SUBJECT");}
        }
        public Result execute(Connection c,CommandEnvelope e,Context ctx)throws SQLException{
            UUID tenant=e.actor().tenantId();Instant now=leads.now(c);
            if(type==CommandEnvelope.Type.CAPTURE_LEAD){var p=capture(e);byte[] key=sourceKey(e,p);var lead=leads.natural(c,tenant,(String)p.get("sourceAccountCode"),key);if(lead!=null)return Result.noChange(lead.selector());
                lead=leads.capture(c,tenant,p,key,now);var plan=next(c,tenant,lead,false,true,now);if(plan.assignment()){var a=leads.assign(c,tenant,lead,plan.owner(),"SOURCE_POLICY_AUTOMATIC",now);lead=leads.update(c,tenant,lead,null,null,null,represented(e.actor()),a.selector().id(),now);}
                tasks.create(c,tenant,plan.type(),plan.owner(),lead.selector(),ZoneId.of(policy(lead.source()).businessTimezone()),now);return Result.succeeded(lead.selector(),LeadCapturedV1);}
            var task=tasks.read(c,tenant,ctx.scope().taskId());var lead=leads.read(c,tenant,task.lead().id());var value=values(e);var source=policy(lead.source());UUID actor=represented(e.actor());
            Plan plan=null;String disposition=null;UUID linked=null;Map<String,Object> ingress=null;String contract=null;String decision=null;Event event;Subject fact=null;String assignmentReason="SOURCE_POLICY_AUTOMATIC";
            switch(type){
                case COMPLETE_LEAD_INGRESS -> {ingress=value;plan=next(c,tenant,lead,true,false,now);event=LeadIngressCompletedV1;}
                case ASSIGN_LEAD -> {plan=new Plan(TaskFactory.Type.CONTACT_LEAD,LeadInputs.uuid(value,"ownerAppointmentId"),true,false);assignmentReason="MANUAL_SELECTION";event=LeadAssignedV1;}
                case RESOLVE_DUPLICATE_LEAD -> {disposition=(String)value.get("decisionCode");if(disposition.equals("LINK_EXISTING_PARTY"))linked=LeadInputs.uuid(value,"partyId");plan=next(c,tenant,lead,false,false,now);contract="LEAD_DUPLICATE_RESOLUTION";decision=disposition;event=LeadDuplicateResolutionRecordedV1;}
                case RECORD_ROUTING_DISPOSITION -> {contract="LEAD_ROUTING_DISPOSITION";decision=(String)value.get("decisionCode");event=decision.equals("REQUEST_SOURCE_INTAKE_STOP")?SourceIntakeStopRequestedV1:LeadRoutingDispositionRecordedV1;
                    if(decision.equals("REQUEST_SOURCE_INTAKE_STOP"))plan=new Plan(TaskFactory.Type.ACK_SOURCE_INTAKE_STOP_REQUEST,assignmentPolicy.unique(c,tenant,lead.selector(),source,TaskFactory.Type.ACK_SOURCE_INTAKE_STOP_REQUEST),false,false);
                    else {UUID supervisor=assignmentPolicy.unique(c,tenant,lead.selector(),source,TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP);require(supervisor.equals(task.owner()),"STALE_SUBJECT");
                        if(decision.equals("SCHEDULE_ROUTING_REVIEW"))plan=new Plan(TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP,supervisor,false,true);
                        else {var candidates=assignmentPolicy.sales(c,tenant,lead.selector(),source);plan=candidates.isEmpty()?new Plan(TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP,supervisor,false,false):new Plan(TaskFactory.Type.CONTACT_LEAD,candidates.getFirst().appointmentId(),true,false);assignmentReason="ROUTING_RETRY";}}
                }
                case ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST -> {contract="SOURCE_INTAKE_STOP_REQUEST_ACKNOWLEDGED";decision=contract;event=SourceIntakeStopRequestAcknowledgedV1;}
                default -> throw new IllegalStateException("Unregistered handler");
            }
            boolean mutate=disposition!=null||ingress!=null||plan!=null&&plan.assignment();if(mutate)CommandHandler.nextRevision(lead.selector().revision());
            drafts.confirm(c,tenant,task,confirmation(e),value,actor,now);
            UUID assignment=null;if(plan!=null&&plan.assignment()){var a=leads.assign(c,tenant,lead,plan.owner(),assignmentReason,now);assignment=a.selector().id();if(type==CommandEnvelope.Type.ASSIGN_LEAD)fact=a.selector();}
            if(contract!=null){var digest=new TreeMap<String,Object>();digest.put("tenantId",tenant.toString());digest.put("subject",Map.of("type",task.lead().type(),"id",task.lead().id().toString(),"revision",task.lead().revision()));digest.put("authoritySlot",task.type().slot);digest.put("decisionCode",decision);digest.put("rationaleSummary",value.get("rationaleSummary"));
                if(type==CommandEnvelope.Type.RESOLVE_DUPLICATE_LEAD){for(String k:List.of("candidateLeadId","candidateLeadRevision","partyId","partyRevision"))digest.put(k,value.get(k));digest.put("newRevision",CommandHandler.nextRevision(lead.selector().revision()));var changed=new TreeMap<String,Object>();changed.put("disposition_code",disposition);if(linked!=null){changed.put("parsed_party_id",linked.toString());changed.put("party_resolution_code","RESOLVED");}digest.put("newValues",changed);}
                if(type==CommandEnvelope.Type.ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST){digest.put("causalDecisionId",value.get("causalDecisionId"));digest.put("causalDecisionHash",value.get("causalDecisionHash"));}
                fact=tasks.decision(c,tenant,task,actor,contract,decision,(String)value.get("rationaleSummary"),digest,now);}
            if(mutate)lead=leads.update(c,tenant,lead,disposition,linked,ingress,actor,assignment,now);if(type==CommandEnvelope.Type.COMPLETE_LEAD_INGRESS)fact=lead.selector();
            tasks.complete(c,tenant,task,fact,now);
            if(plan!=null){var successor=tasks.create(c,tenant,plan.type(),plan.owner(),lead.selector(),ZoneId.of(source.businessTimezone()),now);if(plan.waiting())tasks.waitUntil(c,tenant,successor,actor,R1BusinessTime.nextWindow(now,ZoneId.of(source.businessTimezone())),now);}
            return Result.succeeded(fact,event);
        }
        public void validateBeforeCommit(Connection c,CommandEnvelope e,Context ctx,Result result)throws SQLException{
            authorization.lockForEvaluation(c,e.actor().tenantId());UUID tenant=e.actor().tenantId();
            var lead=type==CommandEnvelope.Type.CAPTURE_LEAD?leads.header(c,tenant,result.fact().id()):leads.header(c,tenant,ctx.authorization().subject().id());require(lead!=null,"STALE_SUBJECT");
            check(c,ctx.authorization(),lead.selector(),false);
            if(type!=CommandEnvelope.Type.CAPTURE_LEAD){var done=tasks.read(c,tenant,ctx.scope().taskId());check(c,ctx.authorization(),done.selector(),false);require(result.fact().equals(done.completion()),"STALE_TASK");}
            if(result.status()==CommandOutcome.Status.NO_CHANGE)return;
            var active=tasks.activeForLead(c,tenant,lead.selector());require(active.size()==(type==CommandEnvelope.Type.ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST?0:1),"STALE_TASK");
            for(var task:active){var source=policy(lead.source());
                if(task.type()==TaskFactory.Type.CONTACT_LEAD){var candidates=assignmentPolicy.sales(c,tenant,lead.selector(),source);var chosen=candidates.stream().filter(a->a.appointmentId().equals(task.owner())).findFirst();require(chosen.isPresent(),"STALE_SUBJECT");
                    if(type!=CommandEnvelope.Type.ASSIGN_LEAD)require(candidates.getFirst().appointmentId().equals(task.owner()),"STALE_SUBJECT");
                    var a=leads.assignment(c,tenant,lead.assignment());require(a!=null&&a.lead().equals(lead.selector().id())&&a.owner().equals(task.owner())&&"OPEN".equals(a.state())&&a.selector().revision()==0,"STALE_SUBJECT");
                }else require(assignmentPolicy.unique(c,tenant,lead.selector(),source,task.type()).equals(task.owner()),"STALE_SUBJECT");
            }
        }
        private void check(Connection c,Request base,Subject subject,boolean last)throws SQLException {var check=authorization.evaluate(c,new Request(base.actor(),subject,base.scopeOrganizationId(),base.requirement()),last);require(check.allowed(),check.rejectionCode()==null?"NOT_AUTHORIZED":check.rejectionCode());}
    }
}
