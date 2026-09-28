package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import java.sql.*;

final class R2FollowupAttemptCommand implements CommandHandler {
    private final R2OpportunityCommands.BusinessZone zone;private final CommandEnvelope.Type type;private final FollowupAttemptService service;private final OpportunityCommandReader opportunities;
    R2FollowupAttemptCommand(CommandEnvelope.Type type,OpportunityProgressProtection protection,R2OpportunityCommands.BusinessZone zone){this.zone=zone;this.type=type;service=R2FollowupAttemptServices.create(protection);opportunities=OpportunityCommandReader.databaseBacked(protection);}
    public CommandEnvelope.Type type(){return type;}
    public Context resolve(Connection c,CommandEnvelope e)throws SQLException{
        var b=R2FollowupAttemptInput.parse(e).binding();var org=R2OpportunityOwnerExceptionAssembly.organization(c,e.actor().tenantId(),b.opportunity());require(org!=null,"NOT_FOUND");
        var request=R1AuthorityReader.databaseBacked().select(c,e.actor(),b.opportunity(),org,"OPPORTUNITY_OWNER",R1CommandPolicy.attemptAuthority(type));require(request!=null,"NOT_AUTHORIZED");
        return new Context(CommandScope.followupAttempt(e,b),request,b);
    }
    public void recoveryEligibility(Connection c,CommandEnvelope e,Context ctx){}
    public void lockRoots(Connection c,CommandEnvelope e,Context ctx)throws SQLException{opportunities.lock(c,e.actor().tenantId(),R2FollowupAttemptInput.parse(e).binding().opportunity().id());}
    public void validateBeforeWork(Connection c,CommandEnvelope e,Context ctx)throws SQLException{var b=R2FollowupAttemptInput.parse(e).binding();R2OpportunityClosureCommand.validateSource(c,e.actor().tenantId(),b.opportunity());}
    public Result execute(Connection c,CommandEnvelope e,Context ctx)throws SQLException{
        try{var input=R2FollowupAttemptInput.parse(e);return Result.succeeded(service.record(c,e.actor(),R2FollowupAttemptInput.basis(input.binding()),input.values(),type==CommandEnvelope.Type.RECORD_QUOTE_FOLLOWUP_ATTEMPT,zone.resolve(c,e.actor(),input.binding().opportunity())),Event.SalesFollowupAttemptRecordedV1);}
        catch(FollowupAttemptService.Blocked blocked){throw new Rejected(blocked.code());}catch(IllegalArgumentException invalid){throw new Rejected("VALIDATION_FAILED");}
    }
    public void validateBeforeCommit(Connection c,CommandEnvelope e,Context ctx,Result result)throws SQLException{
        var b=R2FollowupAttemptInput.parse(e).binding();var m=service.metadata(c,e.actor().tenantId(),result.fact());require(m!=null&&m.createdInTransaction()&&m.actor().equals(e.actor().appointmentId())&&m.basis().equals(R2FollowupAttemptInput.basis(b)),"STALE_TASK");
        var current=R2FollowupAttemptServices.authorization(c,e.actor().tenantId(),b,result.fact());require(current!=null,"STALE_TASK");R2CustomerRequirementsServices.requireAuthority(c,e.actor(),current.organization(),current.protectedFacts(),R1CommandPolicy.attemptAuthority(type));
    }
    private static void require(boolean valid,String code){if(!valid)throw new Rejected(code);}
}
