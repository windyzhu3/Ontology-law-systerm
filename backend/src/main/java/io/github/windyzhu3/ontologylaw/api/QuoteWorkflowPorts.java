package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import io.github.windyzhu3.ontologylaw.evidence.*;
import java.sql.*;import java.time.*;import java.util.*;
/** Trusted composition of distinct Owner ports on the caller's one fenced transaction. */
public final class QuoteWorkflowPorts implements QuoteWorkflowService.Ports {
 private final TaskFactory tasks=TaskFactory.databaseBacked();private final OpportunityProgressProtection protection;
 public QuoteWorkflowPorts(OpportunityProgressProtection protection){this.protection=Objects.requireNonNull(protection);}
 public boolean contractTakenOver(Connection c,UUID tenant,UUID opportunity)throws SQLException{return R2SalesStageGuards.contractTakenOver(c,tenant,opportunity);}
 private static QuoteWorkflowService.Task neutral(TaskFactory.Task t){return t==null?null:new QuoteWorkflowService.Task(t.selector(),t.owner(),t.type().name(),t.subject(),t.state(),t.createdAt(),t.completion());}
 private static TaskFactory.Task owned(QuoteWorkflowService.Task t){return new TaskFactory.Task(t.selector(),t.owner(),TaskFactory.Type.valueOf(t.type()),t.subject(),t.state(),t.createdAt(),t.completion());}
 public Instant now(Connection c)throws SQLException{return tasks.now(c);}
 public QuoteWorkflowService.Task read(Connection c,UUID tenant,UUID id)throws SQLException{return neutral(R2LedgerSourceFacts.currentTask(c,tenant,id));}
 public List<QuoteWorkflowService.Task> activeForLead(Connection c,UUID tenant,Subject opportunity)throws SQLException{return tasks.activeForLead(c,tenant,opportunity).stream().map(QuoteWorkflowPorts::neutral).toList();}
 public QuoteWorkflowService.Task create(Connection c,UUID tenant,String type,UUID owner,Subject opportunity,ZoneId zone,Instant now)throws SQLException{return neutral(tasks.create(c,tenant,TaskFactory.Type.valueOf(type),owner,opportunity,zone,now));}
 public void cancelForQuote(Connection c,UUID tenant,QuoteWorkflowService.Task task,String reason,Instant now)throws SQLException{tasks.cancelForQuote(c,tenant,owned(task),reason,now);}
 public Subject waitReceipt(Connection c,UUID tenant,QuoteWorkflowService.Task task)throws SQLException{if(!"WAITING".equals(task.state()))return null;var wait=EventResponsibilityReader.databaseBacked().latestWait(c,tenant,task.selector().id());if(wait==null||wait.taskRevision()!=task.selector().revision())throw new QuoteWorkflowService.Blocked("STALE_TASK");return wait.selector();}
 public void cancelForTermination(Connection c,UUID tenant,QuoteWorkflowService.Task task,Subject fact,Instant now)throws SQLException{tasks.cancelForQuoteTermination(c,tenant,owned(task),fact,now);}
 public void complete(Connection c,UUID tenant,QuoteWorkflowService.Task task,Subject fact,Instant now)throws SQLException{tasks.complete(c,tenant,owned(task),fact,now);}
 public void waitUntil(Connection c,UUID tenant,QuoteWorkflowService.Task task,UUID actor,Instant due,Instant now,Subject response)throws SQLException{tasks.waitForQuoteReply(c,tenant,owned(task),actor,due,now,response);}
 public UUID confirmAction(Connection c,UUID tenant,QuoteWorkflowService.Task task,Map<String,Object> values,UUID actor,Instant now)throws SQLException{var service=ActionDraftService.databaseBacked();var draft=service.save(c,tenant,owned(task),null,values,actor,now).draft();service.confirm(c,tenant,owned(task),new ActionDraftService.Confirmation(draft.selector().id(),draft.selector().revision(),draft.digest()),values,actor,now);return draft.selector().id();}
 public Map<String,Object> materialBody(Connection c,UUID tenant,UUID upload)throws SQLException{var evidence=R2MaterialsServices.evidence(protection);var basis=evidence.basis(c,tenant,upload);if(basis==null)throw new QuoteWorkflowService.Blocked("STALE_EVIDENCE");return evidence.body(c,tenant,basis);}
 public List<Subject> materialFacts(Connection c,UUID tenant,OpportunityMaterials.Version version)throws SQLException{var chain=R2LedgerSourceFacts.evidenceReference(c,tenant,version.submission());if(chain==null||!chain.binding().id().equals(version.binding()))throw new QuoteWorkflowService.Blocked("STALE_EVIDENCE");var out=new ArrayList<Subject>(List.of(chain.submission(),chain.binding(),chain.target()));var basis=R2LedgerSourceFacts.materialBasis(c,tenant,version.upload());if(basis==null)throw new QuoteWorkflowService.Blocked("STALE_EVIDENCE");out.add(basis.opportunity());out.add(basis.responsibility());if(basis.confirmation()!=null)out.add(basis.confirmation());return out;}
 public List<Subject> sourceFacts(Connection c,UUID tenant,UUID id)throws SQLException{var source=EventOpportunityReader.databaseBacked().byId(c,tenant,id);if(source==null)throw new QuoteWorkflowService.Blocked("NOT_FOUND");return R2CustomerRequirementsServices.sourceFacts(c,tenant,source.selector(),null);}
}
