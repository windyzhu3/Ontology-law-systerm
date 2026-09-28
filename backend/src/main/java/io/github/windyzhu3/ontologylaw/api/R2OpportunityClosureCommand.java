package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.sql.*;
import java.util.*;

/** One explicit terminal command sharing Opportunity root, identity fence and command receipt locks. */
final class R2OpportunityClosureCommand implements CommandHandler {
    private final OpportunityClosureService service;private final OpportunityCommandReader reader;
    R2OpportunityClosureCommand(OpportunityProgressProtection protection){service=io.github.windyzhu3.ontologylaw.api.R2OpportunityClosureServices.create(protection);reader=OpportunityCommandReader.databaseBacked(protection);}
    public CommandEnvelope.Type type(){return CommandEnvelope.Type.CLOSE_OPPORTUNITY;}
    public Context resolve(Connection c,CommandEnvelope e)throws SQLException{
        var in=R2OpportunityClosureInput.parse(e);var org=R2OpportunityOwnerExceptionAssembly.organization(c,e.actor().tenantId(),in.opportunity());require(org!=null,"NOT_FOUND");
        var request=R1AuthorityReader.databaseBacked().select(c,e.actor(),in.opportunity(),org,"OPPORTUNITY_OWNER","OPPORTUNITY_CLOSE");require(request!=null,"NOT_AUTHORIZED");
        return new Context(CommandScope.opportunityClosure(e,in.opportunity(),in.responsibility(),in.task(),in.waitReceipt()),request,new CommandAuthorizationBinding.OpportunityClosure(in.opportunity(),in.responsibility(),in.task(),in.waitReceipt()));
    }
    public void lockRoots(Connection c,CommandEnvelope e,Context ctx)throws SQLException{var in=R2OpportunityClosureInput.parse(e);reader.lock(c,e.actor().tenantId(),in.opportunity().id());if(in.task()!=null)TaskFactory.databaseBacked().lock(c,e.actor().tenantId(),in.task().id());}
    public void recoveryEligibility(Connection c,CommandEnvelope e,Context ctx){}
    public void validateBeforeWork(Connection c,CommandEnvelope e,Context ctx)throws SQLException{
        var in=R2OpportunityClosureInput.parse(e);var s=service.inspect(c,e.actor().tenantId(),in.opportunity().id());require(s!=null,"NOT_FOUND");require(s.opportunity().equals(in.opportunity()),"STALE_SUBJECT");require(!s.closed(),"OPPORTUNITY_CLOSED");require(!s.downstream(),"OPPORTUNITY_HAS_DOWNSTREAM_FACTS");require(s.responsibility().basis().equals(in.responsibility()),"STALE_SUBJECT");require(Objects.equals(s.task(),in.task())&&Objects.equals(s.waitReceipt(),in.waitReceipt()),"STALE_TASK");validateSource(c,e.actor().tenantId(),in.opportunity());
    }
    public Result execute(Connection c,CommandEnvelope e,Context ctx)throws SQLException{try{var in=R2OpportunityClosureInput.parse(e);var result=service.close(c,e.actor().tenantId(),new OpportunityClosureService.Input(in.opportunity(),in.responsibility(),in.task(),in.waitReceipt(),e.actor().appointmentId(),in.reasonCode(),in.summary()));return Result.succeeded(result.selector(),Event.OpportunityClosedV1);}catch(OpportunityClosureService.Blocked blocked){throw new Rejected(blocked.code());}}
    public void validateBeforeCommit(Connection c,CommandEnvelope e,Context ctx,Result result)throws SQLException{
        var in=R2OpportunityClosureInput.parse(e);var s=service.inspect(c,e.actor().tenantId(),in.opportunity().id());var closure=service.read(c,e.actor().tenantId(),result.fact());require(s!=null&&s.closed()&&s.opportunity().revision()==CommandHandler.nextRevision(in.opportunity().revision())&&s.task()==null&&!s.downstream()&&closure!=null&&closure.opportunity().equals(in.opportunity())&&closure.responsibility().equals(in.responsibility())&&Objects.equals(closure.task(),in.task())&&Objects.equals(closure.waitReceipt(),in.waitReceipt())&&closure.actor().equals(e.actor().appointmentId())&&closure.reasonCode().equals(in.reasonCode())&&service.summary(c,e.actor().tenantId(),result.fact()).equals(in.summary()),"STALE_SUBJECT");
        var facts=new LinkedHashSet<Subject>(R2OpportunityClosureServices.protectedFacts(c,e.actor().tenantId(),s.opportunity()));facts.add(in.opportunity());facts.add(in.responsibility());facts.add(result.fact());if(in.task()!=null){facts.add(in.task());facts.add(CurrentTaskReader.databaseBacked().read(c,e.actor().tenantId(),in.task().id()).selector());}if(in.waitReceipt()!=null)facts.add(in.waitReceipt());
        require(OpportunityOwnerExceptionAuthorityReader.databaseBacked().permitted(c,e.actor(),ctx.authorization().scopeOrganizationId(),List.copyOf(facts),"OPPORTUNITY_CLOSE"),"NOT_AUTHORIZED");
    }
    static void validateSource(Connection c,UUID tenant,Subject subject)throws SQLException{
        var o=EventOpportunityReader.databaseBacked().byId(c,tenant,subject.id());require(o!=null,"STALE_SUBJECT");var s=R2OpportunityOwnerExceptionAssembly.sources().read(c,tenant,o.assignmentId(),o.contactId());
        require(s!=null&&s.contact()!=null&&s.assignment()!=null&&s.task()!=null,"STALE_SUBJECT");var a=s.assignment();var contact=s.contact();var task=s.task();
        require(contact.selector().id().equals(o.contactId())&&contact.selector().hash()!=null&&a.selector().id().equals(o.assignmentId())&&a.selector().revision()!=null&&task.selector().id().equals(contact.taskId())&&task.selector().revision()!=null&&"lead.lead".equals(task.subject().type())&&task.subject().id().equals(o.leadId())&&contact.leadId().equals(o.leadId())&&contact.assignmentId().equals(o.assignmentId())&&a.leadId().equals(o.leadId())&&a.owner().equals(o.owner())&&task.owner().equals(o.owner())&&"CONNECTED_VALID".equals(contact.resultCode())&&"CONTACT_LEAD".equals(task.purpose())&&"RECORD_CONTACT_RESULT".equals(task.command())&&"DONE".equals(task.state())&&contact.selector().equals(task.completion()),"STALE_SUBJECT");
    }
    private static void require(boolean valid,String code){if(!valid)throw new Rejected(code);}
}
