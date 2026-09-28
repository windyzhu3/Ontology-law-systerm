package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.responsibility.OpportunityTaskClosure;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Trusted assembly of named Owner ports; Opportunity never accesses another Owner's storage. */
public final class R2OpportunityClosureServices {
    private R2OpportunityClosureServices(){}
    public static List<Subject> protectedFacts(Connection c,UUID tenant,Subject opportunity)throws SQLException{
        return R2LedgerSourceFacts.closure(c,tenant,opportunity,()->loadProtectedFacts(c,tenant,opportunity));
    }
    private static List<Subject> loadProtectedFacts(Connection c,UUID tenant,Subject opportunity)throws SQLException{
        var opening=EventOpportunityReader.databaseBacked().byId(c,tenant,opportunity.id());if(opening==null)return null;
        var effective=OpportunityResponsibilityReader.databaseBacked().current(c,tenant,opening.selector());
        var owner=io.github.windyzhu3.ontologylaw.identity.WorkcardOwnerReader.databaseBacked().read(c,tenant,effective.appointmentId());if(owner==null)return null;
        var metadata=io.github.windyzhu3.ontologylaw.lead.OpportunityLedgerLeadReader.metadataOnly(c,tenant,opening.leadId());if(metadata==null)return null;
        var facts=new LinkedHashSet<Subject>(R2OpportunityOwnerExceptionAssembly.protectedFacts(c,tenant,opportunity));facts.add(effective.basis());facts.add(metadata.selector());facts.add(owner.appointment().selector());facts.add(owner.principal().selector());facts.add(owner.organization().selector());
        if(metadata.partyId()!=null){var party=io.github.windyzhu3.ontologylaw.party.R1PartyReader.databaseBacked().active(c,tenant,metadata.partyId());if(party==null)return null;facts.add(new Subject("party.party",party.id(),party.revision(),null));}
        var state=R2OpportunityOwnerExceptionAssembly.taskState(c,tenant,opening.selector(),effective);facts.addAll(state.protectedSources());if(state.task()!=null){facts.add(state.task());var task=io.github.windyzhu3.ontologylaw.responsibility.AuthorizationTaskReader.databaseBacked().read(c,tenant,state.task().id());if(task==null)return null;if(task.draft()!=null)facts.add(task.draft().selector());}if(state.waitReceipt()!=null)facts.add(state.waitReceipt());return List.copyOf(facts);
    }
    public static OpportunityClosureService create(OpportunityProgressProtection protection){
        var tasks=OpportunityTaskClosure.databaseBacked();
        return OpportunityClosureService.databaseBacked(protection,new OpportunityClosureService.Tasks(){
            public OpportunityClosureService.ActiveTask active(Connection c,UUID tenant,Subject opportunity)throws SQLException{var active=tasks.active(c,tenant,opportunity);return active==null?null:new OpportunityClosureService.ActiveTask(active.task().selector(),active.task().responsibilityBasis(),active.task().owner(),active.waitReceipt(),active.task().state());}
            public void cancel(Connection c,UUID tenant,Subject opportunity,Subject basis,UUID owner,Subject task,Subject wait,Subject closure,Instant at)throws SQLException{tasks.cancel(c,tenant,opportunity,basis,owner,task,wait,closure,at);}
        },(c,tenant,opportunity)->{var facts=new ArrayList<Subject>(io.github.windyzhu3.ontologylaw.contract.OpportunityContractReader.databaseBacked().takeoverFacts(c,tenant,opportunity));facts.addAll(io.github.windyzhu3.ontologylaw.transfer.OpportunityTransferReader.databaseBacked().forOpportunity(c,tenant,opportunity));return List.copyOf(facts);});
    }
}
