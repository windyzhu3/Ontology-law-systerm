package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.contract.ContractWorkflowService;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import io.github.windyzhu3.ontologylaw.identity.OpportunityOwnerExceptionAuthorityReader;
import io.github.windyzhu3.ontologylaw.lead.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import io.github.windyzhu3.ontologylaw.transfer.TransferWorkflowReader;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Explicit Owner resolution. A new task type must choose its source path here. */
final class R2TeamTaskResolver {
 static final class HistoryCodec implements io.github.windyzhu3.ontologylaw.contract.ContractPreparationRepository.Codec,QuoteDraftService.Codec {
  public String encode(Map<String,Object> value){return io.github.windyzhu3.ontologylaw.execution.CanonicalJson.encode(value);}
  @SuppressWarnings("unchecked") public Map<String,Object> decode(String value){return tools.jackson.databind.json.JsonMapper.builder().build().readValue(value,Map.class);}
 }
 static final HistoryCodec HISTORY_CODEC=new HistoryCodec();
 enum Family {LEAD_DECISION,LEAD_COMPLETION,ASSIGNMENT,CONTACT,PROGRESS,QUOTE,CONTRACT,TRANSFER}
 record Basis(UUID organization,UUID lead,List<Subject> facts,TransferWorkflowReader.State transfer){}
 record Completion(UUID actor,Instant at,String reason,String outcome){}
 static boolean needsOriginalInput(CurrentTaskReader.Task task){return "DONE".equals(task.state())&&Set.of(Family.LEAD_COMPLETION,Family.ASSIGNMENT,Family.CONTACT).contains(family(task.type()));}
 static TaskConfirmationReader.Confirmation originalInput(Connection c,UUID tenant,CurrentTaskReader.Task task)throws SQLException{
  if(!needsOriginalInput(task))return null;
  var input=TaskConfirmationReader.databaseBacked().forTask(c,tenant,task.selector().id());
  if(input!=null&&(!input.taskId().equals(task.selector().id())||!input.actionCode().equals(task.type().command)||!input.schemaCode().equals(task.type().schema)||input.schemaVersion()!=1))throw invalid();
  return input;
 }
 /** Exact confirmed source has already passed the same independent management permission. */
 static Completion confirmedInput(Connection c,UUID tenant,CurrentTaskReader.Task task,TaskConfirmationReader.Confirmation expected,Completion original)throws SQLException{
  if(expected==null)return original;
  if(!needsOriginalInput(task)||!expected.equals(originalInput(c,tenant,task)))throw invalid();
  var draft=ActionDraftService.databaseBacked().read(c,tenant,task.selector().id());
  if(draft==null||!draft.selector().equals(expected.selector())||!draft.taskId().equals(task.selector().id())||!draft.actionCode().equals(expected.actionCode())||!draft.schemaCode().equals(expected.schemaCode())||draft.schemaVersion()!=expected.schemaVersion()||!"CONFIRMED".equals(draft.state())||!draft.digest().equals(expected.digest())||!java.security.MessageDigest.isEqual(io.github.windyzhu3.ontologylaw.execution.CanonicalJson.digest(io.github.windyzhu3.ontologylaw.execution.CanonicalJson.encode(draft.values())),Base64.getUrlDecoder().decode(expected.digest())))throw invalid();
  String reason=original.reason();
  if((reason==null||reason.isBlank())&&family(task.type())==Family.LEAD_COMPLETION){var value=draft.values().get("sourceSummary");if(!(value instanceof String text))throw invalid();reason=text;}
  // Legacy confirmed_by stores represented(actor), which cannot prove the actual
  // confirmer. Keep missing actor explicit; never substitute the task owner.
  return new Completion(original.actor(),original.at()==null?expected.confirmedAt():original.at(),reason,original.outcome());
 }
 static Family family(TaskFactory.Type type){return switch(type){
  case RESOLVE_LEAD_DUPLICATE,RESOLVE_SOURCE_REQUEST,RESOLVE_LEAD_ROUTING_GAP,ACK_SOURCE_INTAKE_STOP_REQUEST,REVIEW_LEAD_VALIDITY->Family.LEAD_DECISION;
  case COMPLETE_LEAD_INGRESS->Family.LEAD_COMPLETION;
  case ASSIGN_LEAD->Family.ASSIGNMENT;
  case CONTACT_LEAD->Family.CONTACT;
  case PROGRESS_OPPORTUNITY->Family.PROGRESS;
  case PREPARE_QUOTE,SUBMIT_QUOTE_APPROVAL,APPROVE_QUOTE,DELIVER_QUOTE,RECORD_QUOTE_REPLY,RESOLVE_QUOTE_AUTHORITY->Family.QUOTE;
  case REQUEST_CONTRACT_PREPARATION,DECIDE_CONTRACT_PREPARATION,PREPARE_CONTRACT,SUBMIT_CONTRACT_REVIEW,REVIEW_CONTRACT,SUBMIT_CONTRACT_APPROVAL,APPROVE_CONTRACT,SUPPLEMENT_CONTRACT_REVIEW,ARRANGE_CONTRACT_SIGNATURE,COLLECT_CONTRACT_SIGNATURE,VERIFY_CONTRACT_SIGNATURE,ARCHIVE_CONTRACT_SIGNATURE,CHECK_CONTRACT_EXECUTION,CHECK_CONTRACT_RECEIPT,SUPPLEMENT_CONTRACT_RECEIPT,REVIEW_CONTRACT_TERMINATION->Family.CONTRACT;
  case PREPARE_TRANSFER,SUPPLEMENT_TRANSFER,REVIEW_TRANSFER,ACCEPT_TRANSFER,CLASSIFY_MATTER->Family.TRANSFER;
 };}
 static Basis basis(Connection c,UUID tenant,CurrentTaskReader.Task task)throws SQLException{
  var family=family(task.type());
  if(!task.type().subjectType().equals(task.subject().type()))throw new SQLException("Unregistered team task subject","22000");
  var facts=new LinkedHashSet<Subject>();facts.add(task.selector());facts.add(task.subject());
  if(task.responsibilityBasis()!=null)facts.add(task.responsibilityBasis());
  if(task.completion()!=null)facts.add(task.completion());
  UUID lead=task.subject().id();
  UUID organization=OpportunityOwnerExceptionAuthorityReader.databaseBacked().historicalOrganization(c,tenant,task.owner());
  if(task.type().subjectType().equals("opportunity.opportunity")){
   var opening=EventOpportunityReader.databaseBacked().byId(c,tenant,task.subject().id());if(opening==null)return null;
   facts.add(opening.selector());lead=opening.leadId();
   // Sales facts stay in the originating business organization. An independent
   // reviewer appointment does not move the opportunity into their own organization.
   organization=OpportunityOwnerExceptionAuthorityReader.databaseBacked().historicalOrganization(c,tenant,opening.owner());
  }
  TransferWorkflowReader.State transfer=null;
  if(family==Family.TRANSFER){
   var reader=TransferWorkflowReader.databaseBacked();
   if(Set.of("OPEN","WAITING").contains(task.state()))transfer=reader.forTask(c,tenant,task.selector().id());
   else if("CANCELLED".equals(task.state()))transfer=reader.lastForTask(c,tenant,task.selector().id());
   else if(task.completion()!=null){
    var result=reader.result(c,tenant,task.completion());
    if(result!=null&&result.opportunity().equals(task.subject().id())){
     // The result points to the new workflow; responsibility belonged to its predecessor.
     if(result.previousWorkflow()!=null)transfer=reader.workflow(c,tenant,result.previousWorkflow());
     if(transfer==null||!task.selector().id().equals(transfer.taskId()))transfer=reader.workflow(c,tenant,result.workflow());
    }
   }
   if(transfer==null||!task.selector().id().equals(transfer.taskId())){
    throw new SQLException("Team transfer lineage unavailable","22000");
   }else{
    organization=Set.of("PREPARE","SUPPLEMENT").contains(transfer.stage())?transfer.fromOrganization():transfer.toOrganization();
    facts.add(transfer.request());facts.add(transfer.workflow());
   }
  }
  var cancellation=CurrentTaskReader.databaseBacked().cancellation(c,tenant,task.selector().id());if(cancellation!=null)facts.add(cancellation);
  return organization==null?null:new Basis(organization,lead,List.copyOf(facts),transfer);
 }
 /** Called only after the exact completion selector and its task sources are authorized. */
 static Completion completion(Connection c,UUID tenant,CurrentTaskReader.Task task,CurrentLeadReader leads,OpportunityProgressProtection protection,io.github.windyzhu3.ontologylaw.contract.ContractProtection contractProtection)throws SQLException{
  var exact=task.completion();if(exact==null)return new Completion(null,null,null,null);
  if(!task.type().completionType.equals(exact.type()))throw new SQLException("Unexpected team completion type","22000");
  return switch(family(task.type())){
   case LEAD_DECISION->{var d=CurrentTaskReader.databaseBacked().decision(c,tenant,exact.id());if(d==null||!d.selector().equals(exact)||!d.taskId().equals(task.selector().id()))throw invalid();
    // These original commands also stored represented(actor), not the actual actor.
    yield new Completion(null,d.decidedAt(),d.rationale(),d.code());}
   case LEAD_COMPLETION->new Completion(null,null,null,null);
   case ASSIGNMENT->{var a=leads.assignment(c,tenant,exact.id());if(a==null||!a.leadId().equals(task.subject().id()))throw invalid();yield new Completion(null,a.assignedAt(),null,null);}
   case CONTACT->{var r=leads.contactResult(c,tenant,exact.id());if(r==null||!r.selector().equals(exact)||!r.taskId().equals(task.selector().id()))throw invalid();yield new Completion(null,r.resultedAt(),r.summary(),r.resultCode());}
   case PROGRESS->{
    if(protection==null)throw new SQLException("Progress history protection unavailable","55000");
    var progress=OpportunityCommandReader.databaseBacked(protection).progress(c,tenant,exact.id());
    if(progress==null||!progress.selector().equals(exact)||!progress.opportunity().equals(task.subject().id()))throw invalid();
    try{
     var body=tools.jackson.databind.json.JsonMapper.builder().build().readValue(progress.canonicalBody(),Map.class);
     if(!tenant.toString().equals(body.get("tenantId"))||!task.subject().id().toString().equals(body.get("opportunityId"))||!exact.id().toString().equals(body.get("progressId"))||!task.selector().id().toString().equals(body.get("taskId")))throw invalid();
     var values=(Map<?,?>)body.get("values");
     yield new Completion(UUID.fromString((String)body.get("recordedBy")),Instant.parse((String)body.get("recordedAt")),(String)values.get("summary"),(String)values.get("type"));
    }catch(IllegalArgumentException|ClassCastException malformed){throw invalid();}
   }
   case QUOTE->{var m=QuoteWorkflowService.metadata(c,tenant,exact);if(m==null||!m.opportunityId().equals(task.subject().id()))throw invalid();yield new Completion(m.actorAppointmentId(),m.createdAt(),QuoteWorkflowService.confirmedReason(c,tenant,task.subject().id(),exact,protection,HISTORY_CODEC),null);}
   case CONTRACT->{
    if("contract.payment_review".equals(exact.type())){
     var review=io.github.windyzhu3.ontologylaw.payment.PaymentLedgerReader.databaseBacked().review(c,tenant,exact);
     if(review==null||!review.opportunity().equals(task.subject().id()))throw invalid();
     if(contractProtection==null)throw new SQLException("Payment history protection unavailable","55000");
     String clear=contractProtection.open(tenant,review.opportunity(),exact.id(),io.github.windyzhu3.ontologylaw.contract.ContractProtection.Kind.PAYMENT,review.ciphertext());
     if(!java.security.MessageDigest.isEqual(io.github.windyzhu3.ontologylaw.contract.ContractCanonicalJson.digest(clear),review.digest()))throw invalid();
     if(!(HISTORY_CODEC.decode(clear).get("explanation") instanceof String reason))throw invalid();
     yield new Completion(review.actor(),review.occurredAt(),reason,review.outcome());
    }
    var m=ContractWorkflowService.metadata(c,tenant,exact);if(m==null||!m.opportunityId().equals(task.subject().id()))throw invalid();yield new Completion(m.actorAppointmentId(),m.createdAt(),ContractWorkflowService.confirmedReason(c,tenant,task.subject().id(),exact,contractProtection,HISTORY_CODEC),null);
   }
   case TRANSFER->{var reader=TransferWorkflowReader.databaseBacked();var r=reader.result(c,tenant,exact);if(r==null||!r.opportunity().equals(task.subject().id()))throw invalid();var h=reader.history(c,tenant,r.request()).stream().filter(v->v.selector().equals(exact)).findFirst().orElseThrow(R2TeamTaskResolver::invalid);var protectedFact=reader.body(c,tenant,exact);
    if(protectedFact==null||!protectedFact.selector().equals(exact)||!protectedFact.opportunity().equals(task.subject().id()))throw invalid();
    if(contractProtection==null)throw new SQLException("Transfer history protection unavailable","55000");
    String clear=contractProtection.open(tenant,protectedFact.opportunity(),exact.id(),io.github.windyzhu3.ontologylaw.contract.ContractProtection.Kind.TRANSFER,protectedFact.ciphertext());
    if(!java.security.MessageDigest.isEqual(io.github.windyzhu3.ontologylaw.contract.ContractCanonicalJson.digest(clear),protectedFact.digest()))throw invalid();
    var body=tools.jackson.databind.json.JsonMapper.builder().build().readValue(clear,Map.class);
    if(!(body.get("explanation") instanceof String explanation))throw invalid();
    yield new Completion(r.actor(),h.occurredAt(),explanation,h.outcome());}
  };
 }
 private static SQLException invalid(){return new SQLException("Team completion source does not match task","22000");}
}
