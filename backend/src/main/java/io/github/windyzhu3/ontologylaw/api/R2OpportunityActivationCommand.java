package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.lead.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.sql.*;
import java.time.*;
import java.util.*;
/** Exact internal SERVICE activation. Caller runtime owns the transaction and final disclosure. */
final class R2OpportunityActivationCommand implements CommandHandler {
 private final OpportunityCommandReader opportunities;
 private final TaskFactory tasks=TaskFactory.databaseBacked();
 private final OpportunityMaintenanceTasks maintenance=OpportunityMaintenanceTasks.databaseBacked();
 private final R1SourcePolicyRegistry policies;
 private final LeadIngressService leads;
 private final OpportunityTaskActivationService activation;
 R2OpportunityActivationCommand(R1SourcePolicyRegistry policies,LeadProtection leadProtection,OpportunityProgressProtection protection){
  this.policies=policies;this.leads=LeadIngressService.databaseBacked(leadProtection);this.opportunities=OpportunityCommandReader.databaseBacked(protection);
  this.activation=OpportunityTaskActivationService.databaseBacked((c,tenant,assignmentId,contactId)->{
   var reader=R1EventReaders.databaseBacked();var contact=reader.contact(c,tenant,contactId);var assignment=reader.assignment(c,tenant,assignmentId);var task=contact==null?null:reader.task(c,tenant,contact.taskId());
   return new OpportunityTaskActivationService.OpeningSources(
    contact==null?null:new OpportunityTaskActivationService.Contact(contact.selector(),contact.leadId(),contact.assignmentId(),contact.taskId(),contact.code()),
    assignment==null?null:new OpportunityTaskActivationService.Assignment(assignment.selector(),assignment.leadId(),assignment.owner()),
    task==null?null:new OpportunityTaskActivationService.ContactTask(task.selector(),task.lead(),task.owner(),task.purpose(),task.primaryCommand(),task.state(),task.completion()));
  },new OpportunityTaskActivationService.InitialResponsibility(){
   public Subject ensure(Connection c,UUID tenant,UUID owner,Subject subject,ZoneId zone,Instant now)throws SQLException{require(R2SalesStageGuards.initialFollowupAllowed(c,tenant,subject.id()),"STALE_SUBJECT");return tasks.createInitialOpportunity(c,tenant,owner,subject,zone,now).selector();}
   public Subject ensureCurrent(Connection c,UUID tenant,UUID owner,Subject subject,Subject basis,ZoneId zone,Instant now)throws SQLException {
    if(basis.equals(subject))return ensure(c,tenant,owner,subject,zone,now);
    var current=OpportunityResponsibilityReader.databaseBacked().current(c,tenant,subject);
    require(current.basis().equals(basis)&&current.appointmentId().equals(owner),"STALE_TASK");
    return currentHandoffTask(c,tenant,subject,current).selector();
   }
  });
 }
 public CommandEnvelope.Type type(){return CommandEnvelope.Type.ACTIVATE_INITIAL_OPPORTUNITY_TASK;}
 private static void require(boolean ok,String code){if(!ok)throw new Rejected(code);}
 static Subject input(CommandEnvelope e){
  try{
   require(e.taskPrecondition()==null&&e.draftPrecondition()==null&&e.identityPrecondition()==null,"VALIDATION_FAILED");
   require(e.payload() instanceof Map<?,?>,"VALIDATION_FAILED");var p=(Map<?,?>)e.payload();require(p.keySet().equals(Set.of("opportunityId","expectedOpportunityRevision")),"VALIDATION_FAILED");
   var id=UUID.fromString((String)p.get("opportunityId"));require(id.toString().equals(p.get("opportunityId")),"VALIDATION_FAILED");
   var n=p.get("expectedOpportunityRevision");require(n instanceof Long||n instanceof Integer,"VALIDATION_FAILED");
   return new Subject("opportunity.opportunity",id,((Number)n).longValue(),null);
  }catch(IllegalArgumentException|ClassCastException|NullPointerException invalid){throw new Rejected("VALIDATION_FAILED");}
 }
 public Context resolve(Connection c,CommandEnvelope e)throws SQLException {
  var subject=input(e);var header=opportunities.header(c,e.actor().tenantId(),subject.id());require(header!=null,"NOT_FOUND");
  var organization=OpportunityOwnerExceptionAuthorityReader.databaseBacked().historicalOrganization(c,e.actor().tenantId(),header.owner());require(organization!=null,"NOT_AUTHORIZED");
  var request=R1AuthorityReader.databaseBacked().select(c,e.actor(),subject,organization,"R2_OPPORTUNITY_SYSTEM","OPPORTUNITY_TASK_ACTIVATE");require(request!=null,"NOT_AUTHORIZED");
  return new Context(CommandScope.opportunityActivation(e.actor().tenantId(),subject),request,new CommandAuthorizationBinding.OpportunityActivation(subject));
 }
 public void lockRoots(Connection c,CommandEnvelope e,Context ctx)throws SQLException{opportunities.lock(c,e.actor().tenantId(),input(e).id());}
 public void recoveryEligibility(Connection c,CommandEnvelope e,Context ctx)throws SQLException{validateBeforeWork(c,e,ctx);}
 public void validateBeforeWork(Connection c,CommandEnvelope e,Context ctx)throws SQLException {
  var subject=input(e);var header=opportunities.header(c,e.actor().tenantId(),subject.id());
  require(header!=null&&!header.closed()&&header.selector().equals(subject)&&R2SalesStageGuards.initialFollowupAllowed(c,e.actor().tenantId(),subject.id()),"STALE_SUBJECT");
 }
 public Result execute(Connection c,CommandEnvelope e,Context ctx)throws SQLException {
  var subject=input(e);var tenant=e.actor().tenantId();var existing=maintenance.initial(c,tenant,subject.id());
  var opening=EventOpportunityReader.databaseBacked().byId(c,tenant,subject.id());var lead=opening==null?null:leads.header(c,tenant,opening.leadId());
  var policy=lead==null?null:policies.find(lead.source());require(policy!=null,"VALIDATION_FAILED");
  try{
   var task=activation.activate(c,tenant,subject,ZoneId.of(policy.businessTimezone()),tasks.now(c));
   return existing==null?Result.succeeded(task,Event.OpportunityInitialTaskActivatedV1):Result.noChange(task);
  }catch(OpportunityTaskActivationService.Blocked blocked){throw new Rejected("OPPORTUNITY_OWNER_UNAVAILABLE".equals(blocked.code())?"NOT_AUTHORIZED":blocked.code());}
 }
 public void validateBeforeCommit(Connection c,CommandEnvelope e,Context ctx,Result result)throws SQLException {
  var subject=input(e);require(R2SalesStageGuards.initialFollowupAllowed(c,e.actor().tenantId(),subject.id()),"STALE_SUBJECT");var effective=OpportunityResponsibilityReader.databaseBacked().current(c,e.actor().tenantId(),subject);
  var initial=effective.basis().equals(subject)?maintenance.initial(c,e.actor().tenantId(),subject.id()):currentHandoffTask(c,e.actor().tenantId(),subject,effective);require(initial!=null&&initial.selector().equals(result.fact()),"STALE_TASK");
  if(result.status()==CommandOutcome.Status.SUCCEEDED)require("OPEN".equals(initial.state())&&initial.selector().revision()==0&&initial.subject().equals(input(e)),"STALE_TASK");
 }
 private TaskFactory.Task currentHandoffTask(Connection c,UUID tenant,Subject opportunity,OpportunityResponsibilityReader.Responsibility current)throws SQLException {
  var active=tasks.activeForLead(c,tenant,opportunity);require(active.size()==1,"STALE_TASK");var task=active.getFirst();
  require(task.type()==TaskFactory.Type.PROGRESS_OPPORTUNITY&&task.subject().equals(opportunity)&&task.owner().equals(current.appointmentId())&&("OPEN".equals(task.state())||"WAITING".equals(task.state())),"STALE_TASK");
  require(current.basis().equals(CurrentTaskReader.databaseBacked().read(c,tenant,task.selector().id()).responsibilityBasis()),"STALE_TASK");
  return task;
 }
}
