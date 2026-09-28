package io.github.windyzhu3.ontologylaw.execution;

import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class OpportunityBusinessScopePolicyTest {
    @Test void crossOrganizationReceiverUsesFrozenBusinessScope()throws Exception {assertTrue(authorize(true,false).allowed());}
    @Test void receiverOrganizationCannotReplaceBusinessScope()throws Exception {assertFalse(authorize(false,false).allowed());}
    @Test void missingBusinessScopeFailsClosed()throws Exception {assertFalse(authorize(true,true).allowed());}
    private AuthorizationSnapshot authorize(boolean useBusinessScope,boolean missing)throws Exception {
        UUID tenant=UUID.randomUUID(),principal=UUID.randomUUID(),appointment=UUID.randomUUID(),businessOrg=UUID.randomUUID(),receiverOrg=UUID.randomUUID(),taskId=UUID.randomUUID();
        var actor=new Actor(tenant,principal,appointment,null,null);
        var opportunity=new Subject("opportunity.opportunity",UUID.randomUUID(),0L,null);
        var selector=new Subject("responsibility.task_occurrence",taskId,0L,null);
        var task=new R1AuthorizationFacts.Task(selector,appointment,"PROGRESS_OPPORTUNITY","RECORD_OPPORTUNITY_PROGRESS",opportunity,opportunity,null,new R1AuthorizationFacts.Owner(appointment,principal,receiverOrg,true,"fixture"));
        var facts=new R1AuthorizationFacts() {
            public Capture capture(Connection c,UUID t,String account,String digest){return null;}
            public Task task(Connection c,UUID t,UUID id,Instant at){return tenant.equals(t)&&taskId.equals(id)?task:null;}
            public UUID opportunityOrganization(Connection c,UUID t,UUID id){return missing?null:businessOrg;}
        };
        var authority=new AuthorizationService() {
            public AuthorizationSnapshot evaluate(Connection c,Request request,boolean finalCheck){return new AuthorizationSnapshot(request,Instant.EPOCH,true,null,null,"fixture",new byte[32]);}
            public void lockForEvaluation(Connection c,UUID t){}
            public void lockForMutation(Connection c,UUID t){}
        };
        var request=new Request(actor,opportunity,useBusinessScope?businessOrg:receiverOrg,new Requirement("SALES_OPPORTUNITY_OWNER","OPPORTUNITY_OWNER",Path.DIRECT,UUID.randomUUID()));
        var envelope=new CommandEnvelope(CommandEnvelope.Type.RECORD_OPPORTUNITY_PROGRESS,UUID.randomUUID(),UUID.randomUUID(),actor,Map.of());
        return new R1CommandPolicy(authority,facts).authorize(null,envelope,new CommandHandler.Context(CommandScope.opportunity(tenant,taskId,opportunity),request),true);
    }
}
