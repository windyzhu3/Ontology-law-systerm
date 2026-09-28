package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.execution.*;import io.github.windyzhu3.ontologylaw.identity.*;import io.github.windyzhu3.ontologylaw.opportunity.*;
import java.sql.*;import java.util.*;
final class R2QuoteCommand implements CommandHandler {
 private final CommandEnvelope.Type type;private final OpportunityProgressProtection protection;private final QuoteWorkflowService service;
 R2QuoteCommand(CommandEnvelope.Type type,OpportunityProgressProtection protection){this.type=type;this.protection=protection;service=R2QuoteServices.create(protection);}
 public CommandEnvelope.Type type(){return type;}
 public Context resolve(Connection c,CommandEnvelope e)throws SQLException{var b=R2QuoteInput.parse(e).binding();var org=R2OpportunityOwnerExceptionAssembly.organization(c,e.actor().tenantId(),b.opportunity());require(org!=null,"NOT_FOUND");var r=R1AuthorityReader.databaseBacked().select(c,e.actor(),b.opportunity(),org,"OPPORTUNITY_OWNER",R1CommandPolicy.quoteAuthority(type));require(r!=null,"NOT_AUTHORIZED");return new Context(CommandScope.quotes(e,b),r,b);}
 public void recoveryEligibility(Connection c,CommandEnvelope e,Context ctx){}
 public void lockRoots(Connection c,CommandEnvelope e,Context ctx)throws SQLException{OpportunityCommandReader.databaseBacked(protection).lock(c,e.actor().tenantId(),R2QuoteInput.parse(e).binding().opportunity().id());}
 public void validateBeforeWork(Connection c,CommandEnvelope e,Context ctx)throws SQLException{var b=R2QuoteInput.parse(e).binding();var h=OpportunityCommandReader.databaseBacked(protection).header(c,e.actor().tenantId(),b.opportunity().id());require(h!=null&&h.selector().equals(b.opportunity()),"STALE_SUBJECT");require(!h.closed(),"OPPORTUNITY_CLOSED");R2OpportunityClosureCommand.validateSource(c,e.actor().tenantId(),b.opportunity());}
 @SuppressWarnings("unchecked") public Result execute(Connection c,CommandEnvelope e,Context ctx)throws SQLException{try{var result=service.execute(c,type.name(),e.actor(),(Map<String,Object>)e.payload());require(R1CommandPolicy.quoteResultType(type).equals(result.type()),"VALIDATION_FAILED");return Result.succeeded(result,R1EventPolicy.quoteEvent(type));}catch(QuoteWorkflowService.Blocked blocked){throw new Rejected(blocked.code());}catch(IllegalArgumentException invalid){throw new Rejected("VALIDATION_FAILED");}}
 public void validateBeforeCommit(Connection c,CommandEnvelope e,Context ctx,Result result)throws SQLException{var b=R2QuoteInput.parse(e).binding();var fs=R2QuoteServices.facts(c,e.actor().tenantId(),b.opportunity().id());require(fs.contains(result.fact()),"STALE_SUBJECT");R2CustomerRequirementsServices.requireAuthority(c,e.actor(),ctx.authorization().scopeOrganizationId(),fs,R1CommandPolicy.quoteAuthority(type));}
 private static void require(boolean v,String code){if(!v)throw new Rejected(code);}
}
