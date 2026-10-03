package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.contract.OpportunityContractReader;
import io.github.windyzhu3.ontologylaw.execution.CanonicalJson;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import java.lang.reflect.*;
import java.sql.Connection;
import java.util.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import io.github.windyzhu3.ontologylaw.party.CustomerPartyProfiles;

/** Seeds pre-V960 provenance with the pre-contract-preparation port, never used by current-schema tests. */
final class V950QuoteWorkflowFixture {
    private V950QuoteWorkflowFixture() {}
    /** Historical seed uses domain persistence; current command authorization requires post-V950 sources. */
    static void confirmCustomer(Connection c, UUID tenant, UUID owner, Subject opportunity,
                                OpportunityProgressProtection protection, Map<String,Object> document) throws java.sql.SQLException {
        CustomerRequirementDocument.validate(document,true);
        var basis=OpportunityResponsibilityReader.databaseBacked().current(c,tenant,opportunity).basis();
        var opening=EventOpportunityReader.databaseBacked().byId(c,tenant,opportunity.id());
        var lead=io.github.windyzhu3.ontologylaw.lead.OpportunityLedgerLeadReader.metadataOnly(c,tenant,opening.leadId()).selector();
        var service=R2CustomerRequirementsServices.create(protection);
        var draft=service.save(c,tenant,new OpportunityCustomerRequirementsService.Input(opportunity,basis,owner,null,null,document),lead,List.of());
        var parties=CustomerPartyProfiles.databaseBacked();
        var profile=parties.create(c,tenant,"ORGANIZATION","同名客户");
        var snapshot=parties.snapshot(c,tenant,profile,owner);
        var canonical=new LinkedHashMap<String,Object>(document);
        canonical.put("participants",List.of(Map.of("party",R2CustomerRequirementsServices.selector(R2CustomerRequirementsServices.partySubject(profile)),"role","CLIENT")));
        service.confirm(c,tenant,new OpportunityCustomerRequirementsService.Input(opportunity,basis,owner,draft.selector(),null,document),canonical,
            List.of(new OpportunityCustomerRequirementsService.Participant(new Subject("party.profile_version",snapshot.selector().id(),snapshot.selector().revision(),null),R2CustomerRequirementsServices.snapshot(snapshot.party()),"CLIENT")),lead);
    }
    static QuoteWorkflowService create(OpportunityProgressProtection protection) {
        var current=new QuoteWorkflowPorts(protection);
        var ports=(QuoteWorkflowService.Ports)Proxy.newProxyInstance(QuoteWorkflowService.Ports.class.getClassLoader(),
            new Class<?>[]{QuoteWorkflowService.Ports.class},(proxy,method,args)->{
                if(method.getName().equals("contractTakenOver"))
                    return !OpportunityContractReader.databaseBacked().forOpportunity((Connection)args[0],(UUID)args[1],(UUID)args[2]).isEmpty();
                if(method.getName().equals("sourceFacts"))
                    return historicalSources((Connection)args[0],(UUID)args[1],(UUID)args[2]);
                try{return method.invoke(current,args);}catch(InvocationTargetException failure){throw failure.getCause();}
            });
        return QuoteWorkflowService.databaseBacked(protection,new QuoteDraftService.Codec(){
            public String encode(Map<String,Object> value){return CanonicalJson.encode(value);}
            @SuppressWarnings("unchecked") public Map<String,Object> decode(String value){return (Map<String,Object>)tools.jackson.databind.json.JsonMapper.builder().build().readValue(value,Map.class);}
        },ports);
    }
    private static List<Subject> historicalSources(Connection c,UUID tenant,UUID id) throws java.sql.SQLException {
        var opening=EventOpportunityReader.databaseBacked().byId(c,tenant,id);
        var effective=OpportunityResponsibilityReader.databaseBacked().current(c,tenant,opening.selector());
        var owner=io.github.windyzhu3.ontologylaw.identity.WorkcardOwnerReader.databaseBacked().read(c,tenant,effective.appointmentId());
        var sources=R2OpportunityOwnerExceptionAssembly.sources().read(c,tenant,opening.assignmentId(),opening.contactId());
        var lead=io.github.windyzhu3.ontologylaw.lead.OpportunityLedgerLeadReader.metadataOnly(c,tenant,opening.leadId());
        var facts=new LinkedHashSet<Subject>(List.of(opening.selector(),effective.basis(),lead.selector(),owner.appointment().selector(),owner.principal().selector(),owner.organization().selector(),sources.assignment().selector(),sources.contact().selector(),sources.task().selector(),sources.task().subject()));
        if(lead.partyId()!=null){var party=io.github.windyzhu3.ontologylaw.party.R1PartyReader.databaseBacked().active(c,tenant,lead.partyId());facts.add(new Subject("party.party",party.id(),party.revision(),null));}
        for(var task:io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.databaseBacked().activeForLead(c,tenant,opening.selector())){
            facts.add(task.selector());
            var detail=io.github.windyzhu3.ontologylaw.responsibility.AuthorizationTaskReader.databaseBacked().read(c,tenant,task.selector().id());
            if(detail.draft()!=null)facts.add(detail.draft().selector());
            if("WAITING".equals(task.state()))facts.add(io.github.windyzhu3.ontologylaw.responsibility.EventResponsibilityReader.databaseBacked().latestWait(c,tenant,task.selector().id()).selector());
        }
        return List.copyOf(facts);
    }
}
