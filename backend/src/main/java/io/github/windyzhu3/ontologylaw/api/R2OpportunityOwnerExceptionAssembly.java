package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import io.github.windyzhu3.ontologylaw.lead.R1EventReaders;
import java.sql.*;
import java.util.*;
/** Named production composition. Each Owner reads its own exact facts; no role switching or hidden grants. */
public final class R2OpportunityOwnerExceptionAssembly {
 private R2OpportunityOwnerExceptionAssembly(){}
 public static OpportunityTaskActivationService.OpeningSourceReader sources(){return (c,tenant,assignmentId,contactId)->{
  var r=R1EventReaders.databaseBacked();var contact=r.contact(c,tenant,contactId);var assignment=r.assignment(c,tenant,assignmentId);var task=contact==null?null:r.task(c,tenant,contact.taskId());
  return new OpportunityTaskActivationService.OpeningSources(contact==null?null:new OpportunityTaskActivationService.Contact(contact.selector(),contact.leadId(),contact.assignmentId(),contact.taskId(),contact.code()),assignment==null?null:new OpportunityTaskActivationService.Assignment(assignment.selector(),assignment.leadId(),assignment.owner()),task==null?null:new OpportunityTaskActivationService.ContactTask(task.selector(),task.lead(),task.owner(),task.purpose(),task.primaryCommand(),task.state(),task.completion()));
 };}
 public static OpportunityOwnerExceptionChecks checks(){return checks((c,t,o,r,a,n)->null);}
 public static OpportunityOwnerExceptionChecks checks(OpportunityOwnerExceptionChecks.ValidationEvidenceReader evidence){return OpportunityOwnerExceptionChecks.databaseBacked(sources(),R2OpportunityOwnerExceptionAssembly::taskState,evidence);}
 public static OpportunityOwnerExceptionService service(){return service(checks());}
 public static OpportunityOwnerExceptionService service(OpportunityOwnerExceptionChecks checks){return OpportunityOwnerExceptionService.databaseBacked(checks,checks,(c,t,h,o,r,task,wait,receiver,id,actor,zone)->{if(task==null&&!R2SalesStageGuards.initialFollowupAllowed(c,t,o.id()))throw new SQLException("Ordinary responsibility has already progressed","40001");var result=TaskFactory.databaseBacked().handoffOpportunityTask(c,t,h,o,r.basis(),task,wait,receiver,id,actor,zone);return new OpportunityOwnerExceptionService.TaskHandoffResult(result.task().selector(),result.originalDueAt(),result.originalWait(),result.newWait());});}
 public static UUID organization(Connection c,UUID tenant,Subject opportunity)throws SQLException {return R2LedgerSourceFacts.organization(c,tenant,opportunity,()->{var o=EventOpportunityReader.databaseBacked().byId(c,tenant,opportunity.id());return o==null?null:OpportunityOwnerExceptionAuthorityReader.databaseBacked().historicalOrganization(c,tenant,o.owner());});}
 public static List<Subject> protectedFacts(Connection c,UUID tenant,Subject opportunity)throws SQLException {
  var result=new LinkedHashSet<Subject>();result.add(opportunity);var opening=EventOpportunityReader.databaseBacked().byId(c,tenant,opportunity.id());if(opening==null)return List.copyOf(result);result.add(opening.selector());
  var r=R1EventReaders.databaseBacked();var lead=r.lead(c,tenant,opening.leadId());if(lead!=null)result.add(lead);
  var sources=sources().read(c,tenant,opening.assignmentId(),opening.contactId());
  if(sources!=null){if(sources.assignment()!=null)result.add(sources.assignment().selector());if(sources.contact()!=null)result.add(sources.contact().selector());if(sources.task()!=null){result.add(sources.task().selector());result.add(sources.task().subject());}}
  var responsibility=OpportunityResponsibilityReader.databaseBacked().current(c,tenant,opening.selector());result.add(responsibility.basis());var task=taskState(c,tenant,opening.selector(),responsibility);if(task.task()!=null)result.add(task.task());if(task.waitReceipt()!=null)result.add(task.waitReceipt());result.addAll(task.protectedSources());
  return List.copyOf(result);
 }
 public static OpportunityOwnerExceptionChecks.TaskState taskState(Connection c,UUID tenant,Subject opportunity,OpportunityResponsibilityReader.Responsibility responsibility)throws SQLException {
  var factory=TaskFactory.databaseBacked();var active=factory.activeForLead(c,tenant,opportunity);var protectedFacts=new ArrayList<Subject>();
  // Contract/finance/intake responsibilities have their own lineage and owners. Exact
  // takeover proves why the ordinary task is absent; it does not assert downstream health.
  var takeover=io.github.windyzhu3.ontologylaw.contract.OpportunityContractReader.databaseBacked().takeoverFacts(c,tenant,opportunity.id());
  if(!takeover.isEmpty()) {
   protectedFacts.addAll(takeover);protectedFacts.addAll(active.stream().map(TaskFactory.Task::selector).toList());
   boolean downstreamOnly=active.stream().allMatch(t->t.type().isContract()||t.type().isTransfer());
   return new OpportunityOwnerExceptionChecks.TaskState(null,null,null,null,protectedFacts,downstreamOnly);
  }
  if(active.stream().anyMatch(t->t.type()==TaskFactory.Type.APPROVE_QUOTE)) {
   var approval=QuoteApprovalResponsibilityReader.databaseBacked().current(c,tenant,opportunity);
   protectedFacts.addAll(active.stream().map(TaskFactory.Task::selector).toList());
   boolean valid=approval!=null;var pending=new HashSet<UUID>();
   if(approval!=null){protectedFacts.addAll(approval.facts());for(var member:approval.members()){
    var task=factory.read(c,tenant,member.taskId());
    if(task==null){valid=false;continue;}protectedFacts.add(task.selector());
    valid&=task.type()==TaskFactory.Type.APPROVE_QUOTE&&opportunity.equals(task.subject())&&member.appointment().equals(task.owner());
    if(member.decision()==null){valid&="OPEN".equals(task.state())&&task.completion()==null;pending.add(member.taskId());}
    else valid&="DONE".equals(task.state())&&member.decision().equals(task.completion());
   }}
   valid&=!pending.isEmpty()&&pending.equals(active.stream().map(t->t.selector().id()).collect(java.util.stream.Collectors.toSet()));
   return new OpportunityOwnerExceptionChecks.TaskState(null,null,null,null,protectedFacts,valid);
  }
  if(active.isEmpty())return new OpportunityOwnerExceptionChecks.TaskState(null,null,null,null,List.of(),!OpportunityMaintenanceTasks.databaseBacked().initialExists(c,tenant,opportunity.id()));
  if(active.size()!=1)return new OpportunityOwnerExceptionChecks.TaskState(null,null,null,null,active.stream().map(TaskFactory.Task::selector).toList(),false);
  var task=active.getFirst();var detail=CurrentTaskReader.databaseBacked().read(c,tenant,task.selector().id());var wait="WAITING".equals(task.state())?EventResponsibilityReader.databaseBacked().latestWait(c,tenant,task.selector().id()):null;
  var predecessor=OpportunityMaintenanceTasks.databaseBacked().predecessor(c,tenant,task.selector().id());if(predecessor!=null){protectedFacts.add(predecessor.selector());if(predecessor.completion()!=null)protectedFacts.add(predecessor.completion());}
  boolean lineage=detail!=null&&Set.of(TaskFactory.Type.PROGRESS_OPPORTUNITY,TaskFactory.Type.PREPARE_QUOTE,TaskFactory.Type.SUBMIT_QUOTE_APPROVAL,TaskFactory.Type.DELIVER_QUOTE,TaskFactory.Type.RECORD_QUOTE_REPLY).contains(task.type())&&opportunity.equals(task.subject())&&responsibility.basis().equals(detail.responsibilityBasis());
  if(lineage&&wait!=null){
   try{var source=FollowupAttemptRecovery.source(c,tenant,task.selector().id());protectedFacts.add(source);}
   catch(io.github.windyzhu3.ontologylaw.execution.CommandHandler.Rejected stale){lineage=false;}
  }
  var code=switch(task.type()){case PREPARE_QUOTE,SUBMIT_QUOTE_APPROVAL->"QUOTE_PREPARE";case DELIVER_QUOTE->"QUOTE_DELIVER";case RECORD_QUOTE_REPLY->"QUOTE_RESPONSE";default->"SALES_OPPORTUNITY_OWNER";};
  return new OpportunityOwnerExceptionChecks.TaskState(task.selector(),task.owner(),task.state(),wait==null?null:wait.selector(),protectedFacts,lineage,code);
 }
}
