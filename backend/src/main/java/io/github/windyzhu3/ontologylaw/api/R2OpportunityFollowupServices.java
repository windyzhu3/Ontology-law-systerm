package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.responsibility.TaskFactory;

import io.github.windyzhu3.ontologylaw.lead.R1EventReaders;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Internal business composition. Caller must supply service entry authorization, fence, audit,
 * receipt and commit; this is deliberately not an HTTP endpoint or background scheduler. */
public final class R2OpportunityFollowupServices {
    private final OpportunityCommandReader opportunities;
    private R2OpportunityFollowupServices(OpportunityProgressProtection protection){opportunities=OpportunityCommandReader.databaseBacked(Objects.requireNonNull(protection));}
    public static R2OpportunityFollowupServices create(OpportunityProgressProtection protection){return new R2OpportunityFollowupServices(protection);}
    public Subject reopen(Connection c,UUID tenant,Subject opportunity,Subject task,Subject wait,Subject progress,Instant due)throws SQLException {
        if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)
            throw new SQLException("Opportunity recovery requires READ COMMITTED transaction","25001");
        if(opportunity==null||!"opportunity.opportunity".equals(opportunity.type())||opportunity.revision()==null
                ||progress==null||!Set.of("opportunity.opportunity_progress","opportunity.quote_response",FollowupAttemptService.FACT).contains(progress.type())||progress.hash()==null||due==null)
            throw new IllegalArgumentException("Exact Opportunity recovery required");
        opportunities.lock(c,tenant,opportunity.id());var header=opportunities.header(c,tenant,opportunity.id());
        if(header==null||header.closed()||!header.selector().equals(opportunity))throw blocked("STALE_SUBJECT");
        var authorization=AuthorizationService.databaseBacked();authorization.lockForEvaluation(c,tenant);
        var effective=OpportunityResponsibilityReader.databaseBacked().current(c,tenant,opportunity);
        var tasks=TaskFactory.databaseBacked();var identity=AuthorizationIdentityReader.databaseBacked();
        var owner=identity.owner(c,tenant,effective.appointmentId(),tasks.now(c));var registration=identity.registration(c,tenant,effective.appointmentId());
        if(owner==null||!owner.active()||registration==null||registration.principalKind()!=PrincipalKind.HUMAN)throw blocked("FORBIDDEN");
        var actor=new Actor(tenant,owner.principalId(),owner.appointmentId(),null,null,PrincipalKind.HUMAN);
        UUID organization=OpportunityOwnerExceptionAuthorityReader.databaseBacked().historicalOrganization(c,tenant,header.owner());
        if(organization==null)throw blocked("FORBIDDEN");
        var request=R1AuthorityReader.databaseBacked().select(c,actor,opportunity,organization,"OPPORTUNITY_OWNER",(TaskFactory.databaseBacked().read(c,tenant,task.id()).type()==TaskFactory.Type.RECORD_QUOTE_REPLY)?"QUOTE_RESPONSE":"SALES_OPPORTUNITY_OWNER");
        if(request==null)throw blocked("FORBIDDEN");
        var opening=EventOpportunityReader.databaseBacked().byId(c,tenant,opportunity.id());
        var lead=opening==null?null:R1EventReaders.databaseBacked().lead(c,tenant,opening.leadId());
        if(lead==null)throw blocked("STALE_SUBJECT");
        var subjects=List.of(opportunity,effective.basis(),task,wait,progress,lead);
        for(var subject:subjects)if(!authorization.evaluate(c,new Request(actor,subject,organization,request.requirement()),true).allowed())throw blocked("FORBIDDEN");
        if(FollowupAttemptService.FACT.equals(progress.type())) {
            var detail=io.github.windyzhu3.ontologylaw.responsibility.CurrentTaskReader.databaseBacked().read(c,tenant,task.id());
            if(detail==null||!detail.responsibilityBasis().equals(effective.basis()))throw blocked("STALE_TASK");
            var result=FollowupAttemptRecovery.reopen(c,tenant,task,opportunity,owner.appointmentId(),wait,progress,due);
            for(var subject:subjects)if(!authorization.evaluate(c,new Request(actor,subject,organization,request.requirement()),true).allowed())throw blocked("FORBIDDEN");
            return result;
        }
        if("opportunity.quote_response".equals(progress.type())) {
            var source=QuoteFollowupRecovery.source(c,tenant,task.id());
            if(!source.basis().type().equals(effective.basis().type())||!source.basis().id().equals(effective.basis().id())
                ||!"opportunity.opportunity".equals(source.basis().type())&&!source.basis().revision().equals(effective.basis().revision()))throw blocked("STALE_TASK");
            var result=QuoteFollowupRecovery.reopen(c,tenant,task,opportunity,owner.appointmentId(),wait,progress,due);
            for(var subject:subjects)if(!authorization.evaluate(c,new Request(actor,subject,organization,request.requirement()),true).allowed())throw blocked("FORBIDDEN");
            return result;
        }
        var fact=opportunities.progress(c,tenant,progress.id());
        if(fact==null||!fact.selector().equals(progress)||!fact.opportunity().equals(opportunity.id()))throw blocked("STALE_PROGRESS");
        var body=tools.jackson.databind.json.JsonMapper.builder().build().readTree(fact.canonicalBody());
        if(!OpportunityProgressInput.CONTRACT.equals(body.path("profile").asString())
                ||!tenant.toString().equals(body.path("tenantId").asString())
                ||!opportunity.id().toString().equals(body.path("opportunityId").asString())
                ||!progress.id().toString().equals(body.path("progressId").asString())
                ||!due.equals(Instant.parse(body.path("values").path("nextCheckAt").asString())))throw blocked("STALE_PROGRESS");
        var result=tasks.reopenOpportunityFollowup(c,tenant,task,opportunity,owner.appointmentId(),wait,progress,due);
        for(var subject:subjects)if(!authorization.evaluate(c,new Request(actor,subject,organization,request.requirement()),true).allowed())throw blocked("FORBIDDEN");
        return result.selector();
    }
    private static OpportunityProgressService.Blocked blocked(String code){return new OpportunityProgressService.Blocked(code);}
}
