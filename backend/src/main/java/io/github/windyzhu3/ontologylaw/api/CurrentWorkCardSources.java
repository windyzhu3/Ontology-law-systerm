package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.lead.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.query.CurrentWorkCardQuery;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** API orchestration of public Owner ports; every bound read uses the runtime's one connection. */
final class CurrentWorkCardSources {
    private final AuthorizationService auth;
    private final R1AuthorityReader authorities=R1AuthorityReader.databaseBacked();
    private final AuthorizationIdentityReader identities=AuthorizationIdentityReader.databaseBacked();
    private final WorkcardOwnerReader owners=WorkcardOwnerReader.databaseBacked();
    private final CurrentTaskReader tasks=CurrentTaskReader.databaseBacked();
    private final ActionDraftService drafts=ActionDraftService.databaseBacked();
    private final CurrentLeadReader leads;
    private final R1SourcePolicyRegistry policies;
    CurrentWorkCardSources(AuthorizationService auth,LeadProtection protection,R1SourcePolicyRegistry policies) {this.auth=auth;this.leads=CurrentLeadReader.databaseBacked(protection);this.policies=policies;}
    SensitiveReadRuntime.Prepared read(Connection c,Actor actor,Instant now)throws SQLException {
        UUID ownerId=actor.onBehalfAppointmentId()==null?actor.appointmentId():actor.onBehalfAppointmentId();
        var registration=identities.registration(c,actor.tenantId(),actor.appointmentId());
        var owner=owners.read(c,actor.tenantId(),ownerId);
        if(registration==null||registration.principalKind()!=PrincipalKind.HUMAN||!registration.principalId().equals(actor.principalId())||owner==null
            ||!owner.principal().selector().id().equals(actor.onBehalfPrincipalId()==null?actor.principalId():actor.onBehalfPrincipalId()))throw denied();
        var dependencies=new ArrayList<AuthorizationSnapshot>();boolean workbench=false;
        for(var type:TaskFactory.Type.values()) {
            var snapshot=authorized(c,actor,owner.organization().selector(),owner.organization().selector().id(),type);
            if(snapshot!=null){workbench=true;dependencies.add(snapshot);}
        }
        if(!workbench)throw denied();
        var all=CurrentWorkCardQuery.ordered(tasks.ownedTasks(c,actor.tenantId(),ownerId),now);
        var visible=new ArrayList<CurrentTaskReader.Task>();var bindings=new LinkedHashMap<Subject,DisclosurePlan.Entry>();
        int waiting=0;
        for(var task:all) {
            if(!Set.of("OPEN","WAITING").contains(task.state()))continue;
            var taskAuth=authorized(c,actor,task.selector(),owner.organization().selector().id(),task.type());
            var leadAuth=authorized(c,actor,task.lead(),owner.organization().selector().id(),task.type());
            if(taskAuth==null||leadAuth==null)continue;
            // The current Lead selector is separately authorized, including exact revision-specific DENY.
            var selector=leads.selector(c,actor.tenantId(),task.lead().id());if(selector==null)continue;
            var currentLeadAuth=authorized(c,actor,selector,owner.organization().selector().id(),task.type());if(currentLeadAuth==null)continue;
            dependencies.add(taskAuth);dependencies.add(leadAuth);dependencies.add(currentLeadAuth);
            bindings.put(task.selector(),new DisclosurePlan.Entry(task.selector(),task.selector(),taskAuth));
            if("WAITING".equals(task.state()))waiting++;else visible.add(task);
        }
        CurrentWorkCardQuery.CardData data=null;var chosenBindings=new LinkedHashMap<Subject,DisclosurePlan.Entry>();
        for(var task:visible) {
            var candidateBindings=new LinkedHashMap<Subject,DisclosurePlan.Entry>();
            data=card(c,actor,task,owner,candidateBindings,dependencies);
            if(data!=null){chosenBindings.putAll(candidateBindings);break;}
        }
        UUID chosen=data==null?null:data.task().selector().id();
        var next=visible.stream().filter(t->!t.selector().id().equals(chosen)).limit(2).toList();
        var projection=new CurrentWorkCardQuery().project(actor,now,data,next,waiting);
        var entries=new ArrayList<DisclosurePlan.Entry>();
        for(var source:projection.sources()) {
            var entry=chosenBindings.get(source);if(entry==null)entry=bindings.get(source);
            if(entry==null)throw new IllegalArgumentException("Missing actual source authorization");entries.add(entry);
        }
        return new SensitiveReadRuntime.Prepared(projection.envelope().values(),new DisclosurePlan(entries,dependencies));
    }
    private CurrentWorkCardQuery.CardData card(Connection c,Actor actor,CurrentTaskReader.Task task,WorkcardOwnerReader.Owner owner,
            Map<Subject,DisclosurePlan.Entry> entries,List<AuthorizationSnapshot> dependencies)throws SQLException {
        UUID tenant=actor.tenantId(),scope=owner.organization().selector().id();
        var selector=leads.selector(c,tenant,task.lead().id());if(selector==null)return null;
        if(!bind(c,actor,task.selector(),task.selector(),scope,task.type(),entries)||!bind(c,actor,selector,selector,scope,task.type(),entries))return null;
        var lead=leads.read(c,tenant,task.lead().id());if(lead==null||!selector.equals(lead.selector()))return null;
        if(!bindOwner(c,actor,owner,task.selector(),scope,task.type(),entries))return null;
        CurrentLeadReader.Duplicate duplicate=null;CurrentLeadReader.Party party=null;CurrentLeadReader.Assignment assignment=null;
        CurrentTaskReader.Decision decision=null;CurrentLeadReader.ContactResult result=null;CurrentLeadReader.EffectiveContact contact=null;
        var options=new ArrayList<WorkcardOwnerReader.Owner>();
        switch(task.type()) {
            case RESOLVE_LEAD_DUPLICATE -> {
                duplicate=leads.duplicate(c,tenant,lead.selector().id(),task.createdAt());
                if(duplicate==null||!bind(c,actor,duplicate.lead(),duplicate.lead(),scope,task.type(),entries)
                    ||!bind(c,actor,duplicate.party(),duplicate.party(),scope,task.type(),entries))return null;
                party=leads.namedParty(c,tenant,duplicate.party().id());
                if(party==null||!"ACTIVE".equals(party.status())||!party.selector().equals(duplicate.party()))return null;
            }
            case ASSIGN_LEAD -> {
                var policy=policies.find(lead.sourceAccount());if(policy==null)throw new IllegalArgumentException("Unknown source policy");
                // Each candidate is a complete policy-selected sales path; Actor separately authorizes every returned label.
                for(var candidate:new AssignmentPolicy().sales(c,tenant,lead.selector(),policy)) {
                    var candidateOwner=owners.read(c,tenant,candidate.appointmentId());if(candidateOwner==null)continue;
                    var authorized=auth.evaluate(c,candidate.authorization(),false);if(!authorized.allowed())continue;
                    var candidateEntries=new LinkedHashMap<Subject,DisclosurePlan.Entry>();
                    if(bindOwner(c,actor,candidateOwner,lead.selector(),scope,task.type(),candidateEntries)) {
                        options.add(candidateOwner);entries.putAll(candidateEntries);dependencies.add(authorized);
                    }
                }
                if(options.isEmpty())return null;
            }
            case ACK_SOURCE_INTAKE_STOP_REQUEST -> {
                decision=tasks.causalStop(c,tenant,task);
                if(decision==null||!bind(c,actor,decision.selector(),decision.selector(),scope,task.type(),entries))return null;
            }
            case CONTACT_LEAD -> {
                if(lead.currentAssignmentId()==null)return null;
                assignment=leads.assignment(c,tenant,lead.currentAssignmentId());
                if(assignment==null||!assignment.leadId().equals(lead.selector().id())||!assignment.owner().equals(task.owner())||!"OPEN".equals(assignment.state())
                    ||!bind(c,actor,assignment.selector(),assignment.selector(),scope,task.type(),entries))return null;
                contact=leads.effectiveContact(c,tenant,lead.selector().id());
                if(contact==null||!contact.selector().equals(lead.selector())||contact.phone()==null&&contact.email()==null)return null;
            }
            case REVIEW_LEAD_VALIDITY -> {
                for(var sourceTask:tasks.completedContactTasks(c,tenant,lead.selector().id())) {
                    var candidate=leads.contactResult(c,tenant,sourceTask.completion().id());
                    if(candidate==null||!candidate.selector().equals(sourceTask.completion())||!candidate.taskId().equals(sourceTask.selector().id())
                        ||!candidate.leadId().equals(lead.selector().id())||candidate.resultedAt().isAfter(task.createdAt())
                        ||!("SUSPECT_INVALID".equals(candidate.resultCode())||"NOT_CONNECTED".equals(candidate.resultCode())&&candidate.contactNo()==3))continue;
                    if(result==null||candidate.resultedAt().isAfter(result.resultedAt())||candidate.resultedAt().equals(result.resultedAt())&&CurrentWorkCardQuery.compareUuid(candidate.selector().id(),result.selector().id())>0)result=candidate;
                }
                if(result==null||!bind(c,actor,result.selector(),lead.selector(),scope,task.type(),entries))return null;
            }
            case COMPLETE_LEAD_INGRESS,RESOLVE_LEAD_ROUTING_GAP -> { }
        }
        var draft=drafts.read(c,tenant,task.selector().id());
        if(draft!=null) {
            if(!draft.taskId().equals(task.selector().id())||!task.type().command.equals(draft.actionCode())||!task.type().schema.equals(draft.schemaCode())||draft.schemaVersion()!=1
                ||!Set.of("DRAFT","CONFIRMED").contains(draft.state()))throw new IllegalArgumentException("Invalid stored Draft schema");
            var validated=CurrentLeadReader.validatedDraftValues(draft.actionCode(),draft.values());
            if(!validated.equals(draft.values())||!Base64.getUrlEncoder().withoutPadding().encodeToString(CanonicalJson.digest(CanonicalJson.encode(validated))).equals(draft.digest()))throw new IllegalArgumentException("Invalid stored Draft payload");
            if(!bind(c,actor,draft.selector(),task.selector(),scope,task.type(),entries))return null;
            // An old Draft may contain a now-hidden selector; it is ineligible until refreshed through the authorized write path.
            var v=draft.values();
            if(duplicate!=null&&(!duplicate.lead().id().toString().equals(v.get("candidateLeadId"))||!duplicate.lead().revision().equals(v.get("candidateLeadRevision"))
                ||!duplicate.party().id().toString().equals(v.get("partyId"))||!duplicate.party().revision().equals(v.get("partyRevision"))))return null;
            if(assignment!=null&&(!assignment.selector().id().toString().equals(v.get("leadAssignmentId"))||!assignment.selector().revision().equals(v.get("leadAssignmentRevision"))))return null;
            if(decision!=null&&(!decision.selector().id().toString().equals(v.get("causalDecisionId"))||!decision.selector().hash().equals(v.get("causalDecisionHash"))))return null;
            if(result!=null&&(!result.selector().id().toString().equals(v.get("triggeringContactResultId"))||!result.selector().hash().equals(v.get("triggeringContactResultHash"))))return null;
            if(task.type()==TaskFactory.Type.ASSIGN_LEAD&&options.stream().noneMatch(o->o.appointment().selector().id().toString().equals(v.get("ownerAppointmentId"))))return null;
        }
        return new CurrentWorkCardQuery.CardData(task,lead,owner,draft,contact,duplicate,party,assignment,decision,result,options);
    }
    private boolean bindOwner(Connection c,Actor actor,WorkcardOwnerReader.Owner owner,Subject anchor,UUID scope,TaskFactory.Type type,Map<Subject,DisclosurePlan.Entry> entries)throws SQLException {
        return bind(c,actor,owner.appointment().selector(),anchor,scope,type,entries)&&bind(c,actor,owner.principal().selector(),anchor,scope,type,entries)&&bind(c,actor,owner.organization().selector(),anchor,scope,type,entries);
    }
    private boolean bind(Connection c,Actor actor,Subject source,Subject anchor,UUID scope,TaskFactory.Type type,Map<Subject,DisclosurePlan.Entry> entries)throws SQLException {
        var snapshot=authorized(c,actor,anchor,scope,type);if(snapshot==null)return false;
        entries.putIfAbsent(source,new DisclosurePlan.Entry(source,anchor,snapshot));return true;
    }
    private AuthorizationSnapshot authorized(Connection c,Actor actor,Subject subject,UUID scope,TaskFactory.Type type)throws SQLException {
        var request=authorities.select(c,actor,subject,scope,type.slot,type.authority);if(request==null)return null;
        if(request.requirement().path()!=Path.DIRECT&&request.requirement().path()!=Path.DELEGATED)return null;
        var result=auth.evaluate(c,request,false);return result.allowed()?result:null;
    }
    private static SensitiveReadRuntime.Failure denied(){return new SensitiveReadRuntime.Failure(403,"NOT_AUTHORIZED");}
}
