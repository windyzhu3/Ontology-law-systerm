package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.lead.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.evidence.EvidenceReferenceReader;
import io.github.windyzhu3.ontologylaw.query.CurrentWorkCardQuery;
import io.github.windyzhu3.ontologylaw.query.OpportunityWorkCardQuery;
import io.github.windyzhu3.ontologylaw.opportunity.*;
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
    private final OpportunityCommandReader opportunities;
    private final EventOpportunityReader opportunityOrigins=EventOpportunityReader.databaseBacked();
    private record OpportunityPath(OpportunityCommandReader.Header header,OpportunityResponsibilityReader.Responsibility responsibility,
            EventOpportunityReader.Opportunity origin,Subject lead,UUID scope) {}
    private record Candidate(CurrentWorkCardQuery.CardData leadCard,OpportunityWorkCardQuery.Data opportunityCard) {
        CurrentTaskReader.Task task(){return leadCard!=null?leadCard.task():opportunityCard.task();}
        CurrentLeadReader.Lead lead(){return leadCard!=null?leadCard.lead():opportunityCard.lead();}
    }
    CurrentWorkCardSources(AuthorizationService auth,LeadProtection protection,R1SourcePolicyRegistry policies) {this(auth,protection,policies,null);}
    CurrentWorkCardSources(AuthorizationService auth,LeadProtection protection,R1SourcePolicyRegistry policies,OpportunityProgressProtection opportunityProtection) {
        this.auth=auth;this.leads=CurrentLeadReader.databaseBacked(protection);this.policies=policies;
        this.opportunities=opportunityProtection==null?null:OpportunityCommandReader.databaseBacked(opportunityProtection);
    }
    SensitiveReadRuntime.Prepared read(Connection c,Actor actor,Instant now,UUID selectedTaskId)throws SQLException {
        UUID ownerId=actor.onBehalfAppointmentId()==null?actor.appointmentId():actor.onBehalfAppointmentId();
        var registration=identities.registration(c,actor.tenantId(),actor.appointmentId());
        var owner=owners.read(c,actor.tenantId(),ownerId);
        if(registration==null||registration.principalKind()!=PrincipalKind.HUMAN||!registration.principalId().equals(actor.principalId())||owner==null
            ||!owner.principal().selector().id().equals(actor.onBehalfPrincipalId()==null?actor.principalId():actor.onBehalfPrincipalId()))throw denied();
        var dependencies=new ArrayList<AuthorizationSnapshot>();boolean workbench=false;
        for(var type:TaskFactory.Type.values()) {
            if(type.subjectType().equals("opportunity.opportunity")&&(opportunities==null||actor.onBehalfAppointmentId()!=null))continue;
            var snapshot=authorized(c,actor,owner.organization().selector(),owner.organization().selector().id(),type);
            if(snapshot!=null){workbench=true;dependencies.add(snapshot);}
        }
        var all=CurrentWorkCardQuery.ordered(tasks.ownedTasks(c,actor.tenantId(),ownerId),now);
        var visible=new ArrayList<CurrentTaskReader.Task>();var bindings=new LinkedHashMap<Subject,DisclosurePlan.Entry>();
        var opportunityPaths=new HashMap<UUID,OpportunityPath>();
        int waiting=0;
        for(var task:all) {
            if(!Set.of("OPEN","WAITING").contains(task.state()))continue;
            if(task.type().subjectType().equals("opportunity.opportunity")) {
                var opportunityBindings=new LinkedHashMap<Subject,DisclosurePlan.Entry>();
                var path=opportunityPath(c,actor,task,owner,opportunityBindings);if(path==null)continue;
                opportunityPaths.put(task.selector().id(),path);
                workbench=true;
                opportunityBindings.values().forEach(e->dependencies.add(e.authorization()));bindings.putAll(opportunityBindings);
                if("WAITING".equals(task.state()))waiting++;else visible.add(task);
                continue;
            }
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
        if(!workbench)throw denied();
        Candidate data=null,recommendedData=null;UUID recommended=null;
        var chosenBindings=new LinkedHashMap<Subject,DisclosurePlan.Entry>();
        var recommendedBindings=new LinkedHashMap<Subject,DisclosurePlan.Entry>();
        var eligible=new ArrayList<CurrentTaskReader.Task>();
        var summarySubjects=new LinkedHashMap<UUID,io.github.windyzhu3.ontologylaw.lead.CurrentLeadReader.Lead>();
        var summaryReferences=new LinkedHashMap<UUID,Subject>();
        for(var task:visible) {
            var candidateBindings=new LinkedHashMap<Subject,DisclosurePlan.Entry>();
            var candidate=candidate(c,actor,task,owner,candidateBindings,dependencies,opportunityPaths.get(task.selector().id()));
            if(candidate==null)continue;
            eligible.add(task);
            summarySubjects.put(task.selector().id(),candidate.lead());
            if(candidate.opportunityCard()!=null)summaryReferences.put(task.selector().id(),candidate.opportunityCard().opportunity().selector());
            bindings.putAll(candidateBindings);
            if(recommended==null){recommended=task.selector().id();recommendedData=candidate;recommendedBindings.putAll(candidateBindings);}
            if(selectedTaskId!=null&&selectedTaskId.equals(task.selector().id())){data=candidate;chosenBindings.putAll(candidateBindings);}
        }
        boolean unavailable=selectedTaskId!=null&&data==null;
        if(data==null){data=recommendedData;chosenBindings.putAll(recommendedBindings);}
        UUID chosen=data==null?null:data.task().selector().id();
        var next=eligible.stream().filter(t->!t.selector().id().equals(chosen)).limit(2).toList();
        var query=new CurrentWorkCardQuery((a,s)->io.github.windyzhu3.ontologylaw.execution.PublicFactReferences.reference(a,s.type(),s.id()));
        var projection=data!=null&&data.opportunityCard()!=null
            ?query.projectOpportunity(actor,now,data.opportunityCard(),next,waiting,eligible,recommended,summarySubjects,summaryReferences)
            :query.project(actor,now,data==null?null:data.leadCard(),next,waiting,eligible,recommended,summarySubjects,summaryReferences);
        var body=new LinkedHashMap<String,Object>(projection.envelope().values());
        if(unavailable)body.put("selectionNotice","所选事项已不可处理，已返回当前可处理事项。请从我的待办重新选择。");
        var entries=new ArrayList<DisclosurePlan.Entry>();
        for(var source:projection.sources()) {
            var entry=chosenBindings.get(source);if(entry==null)entry=bindings.get(source);
            if(entry==null)throw new IllegalArgumentException("Missing actual source authorization");entries.add(entry);
        }
        for(var entry:chosenBindings.values())if(entry.disclosedSource().type().startsWith("evidence."))entries.add(entry);
        return new SensitiveReadRuntime.Prepared(body,new DisclosurePlan(entries,dependencies));
    }
    private Candidate candidate(Connection c,Actor actor,CurrentTaskReader.Task task,WorkcardOwnerReader.Owner owner,
            Map<Subject,DisclosurePlan.Entry> entries,List<AuthorizationSnapshot> dependencies,OpportunityPath path)throws SQLException {
        if(!task.type().subjectType().equals("opportunity.opportunity")) {
            var legacy=card(c,actor,task,owner,entries,dependencies);return legacy==null?null:new Candidate(legacy,null);
        }
        if(path==null)return null;
        var opportunity=path.header();var origin=path.origin();UUID scope=path.scope();
        // Business facts are stable under the tenant fence; authorization time is evaluated afresh.
        if(!bind(c,actor,path.responsibility().basis(),path.responsibility().basis(),scope,task.type(),entries)
            ||!bind(c,actor,task.selector(),task.selector(),scope,task.type(),entries)
            ||!bind(c,actor,opportunity.selector(),opportunity.selector(),scope,task.type(),entries)
            ||!bind(c,actor,path.lead(),path.lead(),scope,task.type(),entries))return null;
        var lead=leads.read(c,actor.tenantId(),origin.leadId());
        if(lead==null||!entries.containsKey(lead.selector()))return null;
        if(!bindOwner(c,actor,owner,task.selector(),scope,task.type(),entries))return null;
        var draft=task.type().isContract()||task.type().isTransfer()?null:drafts.read(c,actor.tenantId(),task.selector().id());
        if(draft!=null) {
            if(!draft.taskId().equals(task.selector().id())||!task.type().command.equals(draft.actionCode())||!task.type().schema.equals(draft.schemaCode())||draft.schemaVersion()!=1
                ||!Set.of("DRAFT","CONFIRMED").contains(draft.state()))throw new IllegalArgumentException("Invalid Opportunity Draft schema");
            var validated=R2OpportunityCommands.candidate(draft.values());
            if(!validated.equals(draft.values())||!Base64.getUrlEncoder().withoutPadding().encodeToString(CanonicalJson.digest(CanonicalJson.encode(validated))).equals(draft.digest()))throw new IllegalArgumentException("Invalid Opportunity Draft payload");
            // Check the Draft itself as well as its current Task, including object-specific DENY.
            if(!bind(c,actor,draft.selector(),draft.selector(),scope,task.type(),entries))return null;
        }
        return new Candidate(null,new OpportunityWorkCardQuery.Data(task,opportunity,lead,owner,draft,path.responsibility()));
    }
    private OpportunityPath opportunityPath(Connection c,Actor actor,CurrentTaskReader.Task task,WorkcardOwnerReader.Owner owner,Map<Subject,DisclosurePlan.Entry> entries)throws SQLException {
        if(opportunities==null||actor.onBehalfAppointmentId()!=null||!actor.appointmentId().equals(task.owner()))return null;
        var opportunity=opportunities.header(c,actor.tenantId(),task.subject().id());
        if(opportunity==null||opportunity.closed()&&!Set.of("CHECK_CONTRACT_RECEIPT","SUPPLEMENT_CONTRACT_RECEIPT").contains(task.type().name())||!opportunity.selector().equals(task.subject()))return null;
        var effective=io.github.windyzhu3.ontologylaw.opportunity.OpportunityResponsibilityReader.databaseBacked().current(c,actor.tenantId(),opportunity.selector());
        if((!task.type().independentDecisionOwner()&&!effective.appointmentId().equals(task.owner()))||!effective.basis().equals(task.responsibilityBasis()))return null;
        UUID scope=OpportunityOwnerExceptionAuthorityReader.databaseBacked().historicalOrganization(c,actor.tenantId(),opportunity.owner());if(scope==null)return null;
        if(task.type().isTransfer()){
            var transfer=io.github.windyzhu3.ontologylaw.transfer.TransferWorkflowReader.databaseBacked().forTask(c,actor.tenantId(),task.selector().id());
            if(transfer==null||!transfer.opportunity().equals(opportunity.selector())||!actor.appointmentId().equals(transfer.owner()))return null;
            scope=Set.of("PREPARE","SUPPLEMENT").contains(transfer.stage())?transfer.fromOrganization():transfer.toOrganization();
            if(!bind(c,actor,transfer.workflow(),transfer.workflow(),scope,task.type(),entries)||!bind(c,actor,transfer.request(),transfer.request(),scope,task.type(),entries))return null;
        }
        if(!bind(c,actor,effective.basis(),effective.basis(),scope,task.type(),entries))return null;
        if(!bind(c,actor,task.selector(),task.selector(),scope,task.type(),entries)||!bind(c,actor,opportunity.selector(),opportunity.selector(),scope,task.type(),entries))return null;
        var origin=opportunityOrigins.byId(c,actor.tenantId(),opportunity.selector().id());
        if(origin==null||!origin.selector().equals(opportunity.selector())||!origin.owner().equals(opportunity.owner()))return null;
        var lead=leads.selector(c,actor.tenantId(),origin.leadId());
        if(lead==null||!bind(c,actor,lead,lead,scope,task.type(),entries))return null;
        return new OpportunityPath(opportunity,effective,origin,lead,scope);
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
            case ASSIGN_LEAD,RESOLVE_SOURCE_REQUEST -> {
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
                if(options.isEmpty()&&task.type()==TaskFactory.Type.ASSIGN_LEAD)return null;
                if(task.type()==TaskFactory.Type.RESOLVE_SOURCE_REQUEST){decision=tasks.causalSourceRequest(c,tenant,task);if(decision==null||!bind(c,actor,decision.selector(),decision.selector(),scope,task.type(),entries))return null;}
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
                result=ContactCausality.trigger(c,tenant,lead.selector().id(),task.createdAt(),leads);
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
            if(task.type()==TaskFactory.Type.CONTACT_LEAD&&v.containsKey("evidenceSubmissionId")){
                if(!selector.equals(task.lead()))return null;
                var path=entries.get(task.selector()).authorization().request();
                var qualified=EvidenceReferenceReader.databaseBacked().qualify(c,path,task.selector(),task.lead(),UUID.fromString((String)v.get("evidenceSubmissionId")));
                if(qualified==null)return null;
                dependencies.addAll(qualified.authorization());
                for(var snapshot:qualified.authorization().subList(2,4)){
                    var subject=snapshot.request().subject();entries.put(subject,new DisclosurePlan.Entry(subject,subject,snapshot));
                }
            }
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
        var result=authorities.authorize(c,actor,subject,scope,type.slot,type.authority);if(result==null)return null;
        var request=result.request();
        if(type.subjectType().equals("opportunity.opportunity")&&request.requirement().path()!=Path.DIRECT)return null;
        if(request.requirement().path()!=Path.DIRECT&&request.requirement().path()!=Path.DELEGATED)return null;
        return result.allowed()?result:null;
    }
    private static SensitiveReadRuntime.Failure denied(){return new SensitiveReadRuntime.Failure(403,"NOT_AUTHORIZED");}
}
