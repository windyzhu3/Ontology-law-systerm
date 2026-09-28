package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.contract.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import io.github.windyzhu3.ontologylaw.evidence.*;
import java.sql.*;import java.time.*;import java.util.*;
/** Trusted composition; all Owner ports share the caller's fenced transaction. */
public class ContractWorkflowPorts implements ContractWorkflowService.Ports {
 private final TaskFactory tasks=TaskFactory.databaseBacked();
 private final OpportunityProgressProtection protection;private final MaterialObjectStore objects;
 public ContractWorkflowPorts(OpportunityProgressProtection protection,MaterialObjectStore objects){this.protection=Objects.requireNonNull(protection);this.objects=objects;}
 public ContractWorkflowService.DocumentObject documentObject(Connection c,UUID tenant,UUID materialVersion)throws SQLException {
  var v=OpportunityMaterials.databaseBacked().version(c,tenant,materialVersion);if(v==null)throw new ContractWorkflowService.Blocked("STALE_EVIDENCE");var b=R2LedgerSourceFacts.materialBasis(c,tenant,v.upload());
  if(b==null||!documentUsable(c,tenant,materialVersion,b.sha256()))throw new ContractWorkflowService.Blocked("STALE_EVIDENCE");return new ContractWorkflowService.DocumentObject(b.objectVersion(),b.sha256());
 }
 public String generationParties(Connection c,UUID tenant,UUID opportunity)throws SQLException {
  var svc=R2CustomerRequirementsServices.create(protection);var history=svc.history(c,tenant,opportunity);if(history.isEmpty())throw new ContractWorkflowService.Blocked("CUSTOMER_CONFIRMATION_REQUIRED");var v=svc.read(c,tenant,history.getFirst().selector());var lines=new ArrayList<String>();
  for(var raw:(List<?>)v.document().getOrDefault("participants",List.of())){var row=(Map<?,?>)raw;var party=(Map<?,?>)row.get("party");if(party==null)throw new ContractWorkflowService.Blocked("CUSTOMER_CONFIRMATION_REQUIRED");UUID id=UUID.fromString((String)party.get("id"));var snapshot=v.partySnapshots().stream().filter(s->s.selector().id().equals(id)).findFirst().orElseThrow(()->new ContractWorkflowService.Blocked("STALE_SUBJECT"));String role=switch(String.valueOf(row.get("role"))){case "CLIENT"->"委托方";case "OPPONENT"->"相对方";default->"关联方";};lines.add(role+"："+snapshot.name());}
  if(lines.isEmpty())throw new ContractWorkflowService.Blocked("CUSTOMER_CONFIRMATION_REQUIRED");return String.join("\n",lines);
 }
 private static ContractWorkflowService.Task neutral(TaskFactory.Task t){return t==null?null:new ContractWorkflowService.Task(t.selector(),t.owner(),t.type().name(),t.subject(),t.state(),t.createdAt(),t.completion());}
 public boolean signingPartyCurrent(Connection c,UUID tenant,UUID party,long revision)throws SQLException{var current=io.github.windyzhu3.ontologylaw.party.CustomerPartyProfiles.databaseBacked().current(c,tenant,party,false);return current!=null&&current.selector().revision()==revision;}
 public String signingPartyName(Connection c,UUID tenant,UUID profileVersion)throws SQLException {
  var profile=io.github.windyzhu3.ontologylaw.party.CustomerPartyProfiles.databaseBacked().version(c,tenant,new io.github.windyzhu3.ontologylaw.party.CustomerPartyProfiles.Selector(profileVersion,0));
  if(profile==null)throw new ContractWorkflowService.Blocked("STALE_SUBJECT");return profile.party().name();
 }
 public List<Map<String,Object>> signingParties(Connection c,Actor actor,UUID opportunity)throws SQLException {
  var service=R2CustomerRequirementsServices.create(protection);var history=service.history(c,actor.tenantId(),opportunity);
  if(history.isEmpty())return List.of();var confirmed=service.read(c,actor.tenantId(),history.getFirst().selector());
  return confirmed.partySnapshots().stream().map(p->Map.<String,Object>of("id",p.selector().id().toString(),"label",p.name())).toList();
 }
 private static TaskFactory.Task owned(ContractWorkflowService.Task t){return new TaskFactory.Task(t.selector(),t.owner(),TaskFactory.Type.valueOf(t.type()),t.subject(),t.state(),t.createdAt(),t.completion());}
 public Instant now(Connection c)throws SQLException{return tasks.now(c);}
 public Instant signatureDue(Instant readyAt,ZoneId zone){return R1BusinessTime.due(readyAt,14400,zone);}
 public ContractWorkflowService.Task read(Connection c,UUID tenant,UUID id)throws SQLException{return neutral(R2LedgerSourceFacts.exactTask(c,tenant,id));}
 public ContractWorkflowService.Task currentTask(Connection c,UUID tenant,UUID originalId)throws SQLException{return neutral(R2LedgerSourceFacts.currentTask(c,tenant,originalId));}
 public List<ContractWorkflowService.Task> active(Connection c,UUID tenant,Subject opportunity)throws SQLException{return tasks.activeForLead(c,tenant,opportunity).stream().map(ContractWorkflowPorts::neutral).toList();}
 public ContractWorkflowService.Task create(Connection c,UUID tenant,String type,UUID owner,Subject opportunity,ZoneId zone,Instant now)throws SQLException{return neutral(tasks.create(c,tenant,TaskFactory.Type.valueOf(type),owner,opportunity,zone,now));}
 public ContractWorkflowService.Task createSignatureTask(Connection c,UUID tenant,String type,UUID owner,Subject opportunity,ZoneId zone,Instant now,Instant dueAt)throws SQLException{return neutral(tasks.createSignatureTask(c,tenant,TaskFactory.Type.valueOf(type),owner,opportunity,zone,now,dueAt));}
 public ContractWorkflowService.Task createContractTakingOver(Connection c,UUID tenant,String type,UUID owner,Subject opportunity,ContractWorkflowService.Task prior,ZoneId zone,Instant now)throws SQLException{return neutral(tasks.createContractTakingOver(c,tenant,TaskFactory.Type.valueOf(type),owner,opportunity,owned(prior),zone,now));}
 public void cancelForContract(Connection c,UUID tenant,ContractWorkflowService.Task task,String reason,Instant now)throws SQLException{tasks.cancelForContract(c,tenant,owned(task),reason,now);}
 public Subject negotiationWait(Connection c,UUID tenant,ContractWorkflowService.Task task)throws SQLException{var wait=EventResponsibilityReader.databaseBacked().latestWait(c,tenant,task.selector().id());return wait==null?null:wait.selector();}
 public void cancelForNegotiation(Connection c,UUID tenant,ContractWorkflowService.Task task,Subject disposition,Instant now)throws SQLException{tasks.cancelForContractNegotiation(c,tenant,owned(task),disposition,now);}
 public ContractWorkflowService.Task createTerminationReview(Connection c,UUID tenant,UUID owner,Subject opportunity,Instant now,Instant due)throws SQLException{return neutral(tasks.createTerminationReview(c,tenant,owner,opportunity,now,due));}
 public ContractWorkflowService.Task resumeAfterNegotiation(Connection c,UUID tenant,ContractWorkflowService.Task prior,Subject disposition,UUID cancelledMember,Instant now)throws SQLException{return neutral(tasks.resumeAfterContractNegotiation(c,tenant,owned(prior),disposition,cancelledMember,now));}
 public void complete(Connection c,UUID tenant,ContractWorkflowService.Task task,Subject fact,Instant now)throws SQLException{tasks.complete(c,tenant,owned(task),fact,now);}
 public ContractWorkflowService.Task waitForReceipt(Connection c,UUID tenant,ContractWorkflowService.Task task,UUID actor,Instant now,Subject version)throws SQLException{return neutral(tasks.waitForContractReceipt(c,tenant,owned(task),actor,now,version));}
 public ContractWorkflowService.Task resumeReceipt(Connection c,UUID tenant,ContractWorkflowService.Task task,Subject version)throws SQLException{return neutral(tasks.resumeContractReceipt(c,tenant,owned(task),version));}
 public ContractWorkflowService.Responsibility responsibility(Connection c,UUID tenant,Subject opportunity)throws SQLException{return R2LedgerSourceFacts.responsibility(c,tenant,opportunity,()->{var owner=OpportunityResponsibilityReader.databaseBacked().current(c,tenant,opportunity);var org=R2OpportunityOwnerExceptionAssembly.organization(c,tenant,opportunity);return owner==null||org==null?null:new ContractWorkflowService.Responsibility(owner.basis(),owner.appointmentId(),org);});}
 public List<Subject> sourceFacts(Connection c,UUID tenant,UUID id)throws SQLException{return R2QuoteServices.facts(c,tenant,id);}
 public List<Subject> transferFacts(Connection c,UUID tenant,UUID id)throws SQLException{return io.github.windyzhu3.ontologylaw.transfer.OpportunityTransferReader.databaseBacked().reviewFactsForOpportunity(c,tenant,id);}
 public boolean permitted(Connection c,Actor actor,UUID org,List<Subject> facts,String authority)throws SQLException{return OpportunityLedgerAuthorityReader.databaseBacked().permitted(c,actor,org,facts,authority);}
 public List<UUID> eligible(Connection c,UUID tenant,UUID org,List<Subject> facts,String authority)throws SQLException{
  var result=new ArrayList<UUID>();var identity=AuthorizationIdentityReader.databaseBacked();var reader=ContractAuthorityCandidates.databaseBacked();UUID after=null;Instant at=now(c);
  for(;;){var page=reader.appointments(c,tenant,authority,after,100);for(var id:page){var owner=identity.owner(c,tenant,id,at);if(owner!=null&&owner.active()&&permitted(c,new Actor(tenant,owner.principalId(),id,null,null,PrincipalKind.HUMAN),org,facts,authority))result.add(id);}if(page.size()<100)break;after=page.getLast();}
  return List.copyOf(result);
 }
 public ContractWorkflowService.ApprovalPolicy approvalPolicy(Connection c,UUID tenant,UUID org,List<Subject> facts)throws SQLException{
  var policy=ContractWorkflowService.approvalPolicy(c,tenant,org);if(policy==null||policy.approvers().isEmpty())return null;var identity=AuthorizationIdentityReader.databaseBacked();Instant at=now(c);
  for(var id:policy.approvers()){var owner=identity.owner(c,tenant,id,at);if(owner==null||!owner.active()||!permitted(c,new Actor(tenant,owner.principalId(),id,null,null,PrincipalKind.HUMAN),org,facts,"CONTRACT_APPROVE"))return null;}return policy;
 }
 @SuppressWarnings("unchecked") public Map<String,Object> acceptedQuote(Connection c,UUID tenant,UUID opportunity)throws SQLException{
  var accepted=R2QuoteServices.create(protection).acceptedPreparation(c,tenant,opportunity);if(accepted==null)return null;var body=(Map<String,Object>)accepted.get("commercial");var lines=new ArrayList<ContractVersionInput.FeeLine>();for(var row:(List<?>)body.get("lines")){var line=(Map<String,Object>)row;lines.add(new ContractVersionInput.FeeLine((String)line.get("description"),((Number)line.get("amountMinor")).longValue(),(Boolean)line.get("discount")));}
  var fee=(Map<String,Object>)body.get("conditionalFee");var conditional=fee==null?null:new ContractVersionInput.ConditionalFee((String)fee.get("basis"),((Number)fee.get("rateBasisPoints")).intValue(),((Number)fee.get("capMinor")).longValue());var terms=new ContractVersionInput.CommercialTerms((String)body.get("currency"),(String)body.get("scope"),lines,conditional,(String)body.get("paymentTerms"));return Map.of("responseId",accepted.get("responseId"),"commercial",terms.canonical());
 }
 public Subject blockFinding(Connection c,UUID tenant,UUID opportunity,UUID actor,Subject finding,String reason)throws SQLException{return ContractConflictDecisions.databaseBacked().block(c,tenant,opportunity,actor,finding,reason);}
 public boolean reviewScopeComplete(Connection c,UUID tenant,UUID opportunity)throws SQLException{var svc=R2CustomerRequirementsServices.create(protection);var history=svc.history(c,tenant,opportunity);if(history.isEmpty())return false;var doc=svc.read(c,tenant,history.getFirst().selector()).document();if(Boolean.TRUE.equals(doc.get("unknownOpponent")))return false;var participants=(List<?>)doc.getOrDefault("participants",List.of());return !participants.isEmpty()&&participants.stream().allMatch(p->((Map<?,?>)p).get("party") instanceof Map<?,?>);}
 public String customerName(Connection c,UUID tenant,UUID opportunity)throws SQLException{
  var svc=R2CustomerRequirementsServices.create(protection);var history=svc.history(c,tenant,opportunity);if(history.isEmpty())return "未确认客户";var v=svc.read(c,tenant,history.getFirst().selector());var names=new ArrayList<String>();
  for(var row:(List<?>)v.document().getOrDefault("participants",List.of())){var participant=(Map<?,?>)row;if(!"CLIENT".equals(participant.get("role")))continue;var party=(Map<?,?>)participant.get("party");if(party!=null){var id=UUID.fromString((String)party.get("id"));v.partySnapshots().stream().filter(snapshot->snapshot.selector().id().equals(id)).findFirst().ifPresent(snapshot->names.add(snapshot.name()));}}
  return names.isEmpty()?"已确认客户":String.join("、",names);
 }
 public List<Map<String,Object>> documents(Connection c,Actor actor,UUID opportunity)throws SQLException{
  var reader=OpportunityMaterials.databaseBacked();var opening=EventOpportunityReader.databaseBacked().byId(c,actor.tenantId(),opportunity);if(opening==null)throw new ContractWorkflowService.Blocked("NOT_FOUND");var org=R2OpportunityOwnerExceptionAssembly.organization(c,actor.tenantId(),opening.selector());var out=new ArrayList<Map<String,Object>>();
  for(var v:reader.history(c,actor.tenantId(),opportunity)){if(!reader.current(c,actor.tenantId(),v.selector().id()))continue;var facts=R2MaterialsServices.facts(c,actor.tenantId(),opening.selector(),v.selector());if(facts==null||!permitted(c,actor,org,facts,"CONTRACT_READ"))continue;var basis=R2MaterialsServices.metadata().basis(c,actor.tenantId(),v.upload());if(basis==null||!documentUsable(c,actor.tenantId(),v.selector().id(),basis.sha256()))continue;var body=R2MaterialsServices.evidence(protection).body(c,actor.tenantId(),basis);out.add(Map.of("id",v.selector().id().toString(),"label",body.get("fileName"),"bodySha256",basis.sha256()));}return List.copyOf(out);
 }
 public List<Subject> documentFacts(Connection c,UUID tenant,UUID materialVersion)throws SQLException{return R2LedgerSourceFacts.document(c,tenant,materialVersion,()->{var v=OpportunityMaterials.databaseBacked().version(c,tenant,materialVersion);if(v==null)return List.of();var source=EventOpportunityReader.databaseBacked().byId(c,tenant,v.opportunity());if(source==null)return List.of();var facts=R2MaterialsServices.facts(c,tenant,source.selector(),v.selector());return facts==null?List.of():facts;});}
 public boolean documentUsable(Connection c,UUID tenant,UUID materialVersion,String expectedSha)throws SQLException{
  if(expectedSha==null||!expectedSha.matches("[0-9a-f]{64}"))return false;
  var v=OpportunityMaterials.databaseBacked().version(c,tenant,materialVersion);if(v==null)return false;
  var reference=R2LedgerSourceFacts.evidenceReference(c,tenant,v.submission());var basis=R2LedgerSourceFacts.materialBasis(c,tenant,v.upload());
  return reference!=null&&reference.active()&&reference.binding().id().equals(v.binding())&&basis!=null
    &&basis.opportunity().id().equals(v.opportunity())&&reference.target().equals(basis.opportunity())
    &&"PASSED".equals(basis.checkState())&&basis.receivedObject()!=null&&basis.objectVersion()!=null
    &&expectedSha.equals(basis.sha256());
 }
 public byte[] document(Connection c,Actor actor,UUID opportunity,UUID materialVersion)throws SQLException{
  var v=OpportunityMaterials.databaseBacked().version(c,actor.tenantId(),materialVersion);if(v==null||!v.opportunity().equals(opportunity))throw new ContractWorkflowService.Blocked("STALE_EVIDENCE");
  var ref=EvidenceReferenceReader.databaseBacked().read(c,actor.tenantId(),v.submission());var basis=R2MaterialsServices.metadata().basis(c,actor.tenantId(),v.upload());
  if(ref==null||!ref.active()||!ref.binding().id().equals(v.binding())||basis==null||!basis.opportunity().id().equals(opportunity)||basis.objectVersion()==null||objects==null)throw new ContractWorkflowService.Blocked("STALE_EVIDENCE");
  try{return objects.read(basis.objectVersion(),basis.sha256());}catch(java.io.IOException failure){throw new SQLException("Contract document unavailable","58000",failure);}
 }
}
