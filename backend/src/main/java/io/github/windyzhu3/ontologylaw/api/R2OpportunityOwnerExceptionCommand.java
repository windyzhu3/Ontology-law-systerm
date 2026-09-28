package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.audit.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.lead.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.sql.*;
import java.time.*;
import java.util.*;
/** Exact T01 commands share the existing runtime fence, root lock, idempotency and disclosure policy. */
final class R2OpportunityOwnerExceptionCommand implements OpportunityOwnerObservationCommand {
 private final CommandEnvelope.Type type;private final R1SourcePolicyRegistry policies;private final LeadIngressService leads;private final OpportunityCommandReader opportunities;
 private R2OpportunityOwnerExceptionCommand(CommandEnvelope.Type type,R1SourcePolicyRegistry policies,LeadProtection leads,OpportunityProgressProtection protection){this.type=type;this.policies=policies;this.leads=LeadIngressService.databaseBacked(leads);this.opportunities=OpportunityCommandReader.databaseBacked(protection);}
 static List<CommandHandler> handlers(R1SourcePolicyRegistry p,LeadProtection l,OpportunityProgressProtection o){return List.of(new R2OpportunityOwnerExceptionCommand(CommandEnvelope.Type.OBSERVE_OPPORTUNITY_OWNER_EXCEPTION,p,l,o),new R2OpportunityOwnerExceptionCommand(CommandEnvelope.Type.TRANSFER_OPPORTUNITY_RESPONSIBILITY,p,l,o),new R2OpportunityOwnerExceptionCommand(CommandEnvelope.Type.RECORD_OPPORTUNITY_OWNER_COORDINATION,p,l,o));}
 public CommandEnvelope.Type type(){return type;}
 public Context resolve(Connection c,CommandEnvelope e)throws SQLException {
  var in=R2OpportunityOwnerExceptionInput.parse(e);var organization=R2OpportunityOwnerExceptionAssembly.organization(c,e.actor().tenantId(),in.opportunity());require(organization!=null,"NOT_FOUND");
  var request=R1AuthorityReader.databaseBacked().select(c,e.actor(),in.opportunity(),organization,"OPPORTUNITY_OWNER",type==CommandEnvelope.Type.OBSERVE_OPPORTUNITY_OWNER_EXCEPTION?"OPPORTUNITY_OWNER_EXCEPTION_DISCOVER":"OPPORTUNITY_OWNER_EXCEPTION_RESOLVE");require(request!=null,"NOT_AUTHORIZED");
  return new Context(CommandScope.ownerException(e,in.opportunity(),in.exception(),in.basis(),in.task(),in.waitReceipt()),request,new CommandAuthorizationBinding.OwnerException(in.opportunity(),in.exception(),in.basis(),in.task(),in.waitReceipt()));
 }
 public void lockRoots(Connection c,CommandEnvelope e,Context ctx)throws SQLException {opportunities.lock(c,e.actor().tenantId(),R2OpportunityOwnerExceptionInput.parse(e).opportunity().id());}
 public void recoveryEligibility(Connection c,CommandEnvelope e,Context ctx)throws SQLException {if(type==CommandEnvelope.Type.OBSERVE_OPPORTUNITY_OWNER_EXCEPTION)validateBeforeWork(c,e,ctx);}
 public void validateBeforeWork(Connection c,CommandEnvelope e,Context ctx)throws SQLException {var in=R2OpportunityOwnerExceptionInput.parse(e);var opening=opportunities.header(c,e.actor().tenantId(),in.opportunity().id());require(opening!=null&&opening.selector().equals(in.opportunity()),"STALE_SUBJECT");if(type!=CommandEnvelope.Type.OBSERVE_OPPORTUNITY_OWNER_EXCEPTION)require(!opening.closed(),"STALE_SUBJECT");}
 public AuditAppender.OwnerValidationEntry prepareObservation(Connection c,CommandEnvelope e,Context ctx,AuthorizationSnapshot authorization)throws SQLException {
  require(type==CommandEnvelope.Type.OBSERVE_OPPORTUNITY_OWNER_EXCEPTION,"VALIDATION_FAILED");var in=R2OpportunityOwnerExceptionInput.parse(e);var tenant=e.actor().tenantId();var responsibility=OpportunityResponsibilityReader.databaseBacked().current(c,tenant,in.opportunity());var now=TaskFactory.databaseBacked().now(c);
  var scope=R2OpportunityServiceScopeReader.databaseBacked().ownerExceptionScope(c,e.actor(),now);require(scope.authorized()&&scope.organizations().contains(ctx.authorization().scopeOrganizationId()),"NOT_AUTHORIZED");
  var opening=opportunities.header(c,tenant,in.opportunity().id());
  var reasons=opening.closed()?Set.<OpportunityOwnerExceptionService.Reason>of():R2OpportunityOwnerExceptionAssembly.checks().inspect(c,tenant,in.opportunity(),responsibility,now).reasons();
  var assessment=OpportunityOwnerExceptionAuthorityReader.databaseBacked().receiver(c,tenant,responsibility.appointmentId(),ctx.authorization().scopeOrganizationId(),R2OpportunityOwnerExceptionAssembly.protectedFacts(c,tenant,in.opportunity()),now,R2OpportunityOwnerExceptionAssembly.taskState(c,tenant,in.opportunity(),responsibility).authorityCode());
  return new AuditAppender.OwnerValidationEntry(OpportunityOwnerValidationReader.id(tenant,e.commandId()),e.correlationId(),authorization,in.opportunity(),responsibility.basis(),responsibility.appointmentId(),now,!opening.closed()&&reasons.isEmpty()&&assessment.active()&&assessment.authorized()&&!assessment.denied(),reasons.stream().sorted().map(Enum::name).toList(),assessment.evidence().stream().map(AuthorizationSnapshot::evidence).toList());
 }
 public Result execute(Connection c,CommandEnvelope e,Context ctx)throws SQLException {require(type!=CommandEnvelope.Type.OBSERVE_OPPORTUNITY_OWNER_EXCEPTION,"VALIDATION_FAILED");return executeObserved(c,e,ctx,null);}
 public Result executeObserved(Connection c,CommandEnvelope e,Context ctx,AuditAppender.OwnerValidationEntry evidence)throws SQLException {
  var in=R2OpportunityOwnerExceptionInput.parse(e);var tenant=e.actor().tenantId();
  var checks=R2OpportunityOwnerExceptionAssembly.checks((connection,t,o,r,a,n)->evidence!=null&&evidence.validated()&&evidence.id().equals(OpportunityOwnerValidationReader.id(t,e.commandId()))&&evidence.opportunity().equals(o)&&evidence.basis().equals(r.basis())&&evidence.owner().equals(r.appointmentId())&&!evidence.observedAt().isAfter(n)?evidence.selector():null);
  var service=R2OpportunityOwnerExceptionAssembly.service(checks);
  try {
   if(type==CommandEnvelope.Type.OBSERVE_OPPORTUNITY_OWNER_EXCEPTION){var result=service.observe(c,tenant,in.opportunity());if(result.isPresent())return Result.succeeded(result.get().selector(),Event.OpportunityOwnerExceptionObservedV1);require(evidence!=null,"VALIDATION_FAILED");return Result.noChange(evidence.selector());}
   var decision=new OpportunityOwnerExceptionService.Decision(in.exception(),in.opportunity(),in.basis(),in.task(),in.waitReceipt(),e.actor().appointmentId(),in.reason());
   if(type==CommandEnvelope.Type.RECORD_OPPORTUNITY_OWNER_COORDINATION)return Result.succeeded(service.coordinate(c,tenant,decision,in.reviewDueAt()).selector(),Event.OpportunityOwnerCoordinationRecordedV1);
   var opening=EventOpportunityReader.databaseBacked().byId(c,tenant,in.opportunity().id());var lead=leads.header(c,tenant,opening.leadId());var policy=lead==null?null:policies.find(lead.source());require(policy!=null,"VALIDATION_FAILED");
   var transfer=service.transfer(c,tenant,decision,in.receiver(),ZoneId.of(policy.businessTimezone()));return transfer.changed()?Result.succeeded(transfer.exception().selector(),Event.OpportunityResponsibilityTransferredV1):Result.noChange(transfer.exception().selector());
  }catch(SQLException failure){if("40001".equals(failure.getSQLState()))throw new Rejected("STALE_SUBJECT");if("42501".equals(failure.getSQLState()))throw new Rejected("NOT_AUTHORIZED");throw failure;}
 }
 public void validateBeforeCommit(Connection c,CommandEnvelope e,Context ctx,Result result)throws SQLException {
  var in=R2OpportunityOwnerExceptionInput.parse(e);var tenant=e.actor().tenantId();var authority=OpportunityOwnerExceptionAuthorityReader.databaseBacked();var facts=new LinkedHashSet<Subject>(R2OpportunityOwnerExceptionAssembly.protectedFacts(c,tenant,in.opportunity()));facts.add(result.fact());
  if("opportunity.owner_exception".equals(result.fact().type())){var s=R2OpportunityOwnerExceptionAssembly.service().read(c,tenant,result.fact());require(s!=null&&(s.opportunity().equals(in.opportunity())||type==CommandEnvelope.Type.OBSERVE_OPPORTUNITY_OWNER_EXCEPTION&&s.state()==OpportunityOwnerExceptionService.State.NO_LONGER_APPLICABLE&&in.opportunity().equals(s.resolution())),"STALE_SUBJECT");facts.add(s.responsibility().basis());if(s.task()!=null)facts.add(s.task());if(s.waitReceipt()!=null)facts.add(s.waitReceipt());if(s.resolution()!=null)facts.add(s.resolution());if(s.lastDispositionId()!=null)facts.add(new Subject("opportunity.owner_exception_disposition",s.lastDispositionId(),0L,null));}
  else require(type==CommandEnvelope.Type.OBSERVE_OPPORTUNITY_OWNER_EXCEPTION&&result.status()==CommandOutcome.Status.NO_CHANGE&&result.fact().equals(OpportunityOwnerValidationReader.read(c,tenant,OpportunityOwnerValidationReader.id(tenant,e.commandId()),in.opportunity(),null,false)),"VALIDATION_FAILED");
  for(String code:type==CommandEnvelope.Type.OBSERVE_OPPORTUNITY_OWNER_EXCEPTION?List.of("OPPORTUNITY_OWNER_EXCEPTION_DISCOVER"):List.of("OPPORTUNITY_OWNER_EXCEPTION_READ","OPPORTUNITY_OWNER_EXCEPTION_RESOLVE"))require(authority.permitted(c,e.actor(),ctx.authorization().scopeOrganizationId(),List.copyOf(facts),code),"NOT_AUTHORIZED");
 }
 private static void require(boolean ok,String code){if(!ok)throw new Rejected(code);}
}
