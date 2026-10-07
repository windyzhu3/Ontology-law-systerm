package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.transfer.*;
import io.github.windyzhu3.ontologylaw.transfer.TransferWorkflowService.*;
import io.github.windyzhu3.ontologylaw.contract.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.opportunity.OpportunityProgressProtection;
import io.github.windyzhu3.ontologylaw.evidence.MaterialObjectStore;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.sql.*;import java.time.*;import java.util.*;import java.util.function.Function;
/** Trusted composition. Destination comes from deployment, not sales input. */
public final class TransferWorkflowPorts implements TransferWorkflowService.Ports,TransferSubmissionService.Ports {
 private final ContractWorkflowPorts contracts;private final ContractProtection protection;private final OpportunityProgressProtection materials;
 private final TaskFactory tasks=TaskFactory.databaseBacked();private final Function<UUID,UUID> destinations;
 public TransferWorkflowPorts(ContractProtection protection,OpportunityProgressProtection materials,MaterialObjectStore objects,Function<UUID,UUID> destinations){this(protection,materials,objects,destinations,new BusinessResponsibilityRouting(List.of()));}
 public TransferWorkflowPorts(ContractProtection protection,OpportunityProgressProtection materials,MaterialObjectStore objects,Function<UUID,UUID> destinations,BusinessResponsibilityRouting routing){this.protection=Objects.requireNonNull(protection);this.materials=Objects.requireNonNull(materials);this.contracts=new ContractWorkflowPorts(materials,objects,routing);this.destinations=Objects.requireNonNull(destinations);}
 public void lockAuthority(Connection c,Actor a)throws SQLException{AuthorizationService.databaseBacked().lockForEvaluation(c,a.tenantId());}
 public Instant now(Connection c)throws SQLException{return tasks.now(c);}
 public Instant due(Instant trigger){return contracts.signatureDue(trigger,ZoneId.of("Asia/Shanghai"));}
 private record Request(Subject fact,UUID from,UUID to){}
 private static final Set<String> FACTS=Set.of("transfer_request","workflow","submission","review","review_return_item","intake","classification");
 private static PreparedStatement prepare(Connection c,String sql,Object...values)throws SQLException{var p=c.prepareStatement(sql);for(int i=0;i<values.length;i++)p.setObject(i+1,values[i]);return p;}
 private Request request(Connection c,UUID tenant,Subject opportunity,UUID id)throws SQLException{
  try(var p=prepare(c,"select revision,from_organization_unit_id,to_organization_unit_id from transfer.transfer_request where tenant_id=? and transfer_request_id=? and opportunity_id=?",tenant,id,opportunity.id());var r=p.executeQuery()){if(!r.next())throw new Blocked("STALE_SUBJECT");return new Request(new Subject("transfer.transfer_request",id,r.getLong(1),null),r.getObject(2,UUID.class),r.getObject(3,UUID.class));}
 }
 private Request selectedRequest(Connection c,UUID tenant,Subject opportunity,List<Subject> selected)throws SQLException{
  UUID found=null;for(var fact:selected){String name=fact.type().startsWith("transfer.")?fact.type().substring(9):"";if(!FACTS.contains(name))throw new Blocked("STALE_SUBJECT");
   try(var p=prepare(c,"select transfer_request_id from transfer."+name+" where tenant_id=? and "+name+"_id=? and opportunity_id=?",tenant,fact.id(),opportunity.id());var r=p.executeQuery()){if(!r.next())throw new Blocked("STALE_SUBJECT");UUID id=r.getObject(1,UUID.class);if(found!=null&&!found.equals(id))throw new Blocked("STALE_SUBJECT");found=id;}
  }
  if(found==null)throw new Blocked("STALE_SUBJECT");return request(c,tenant,opportunity,found);
 }
 private List<Subject> facts(Connection c,UUID tenant,Subject opportunity,List<Subject> selected)throws SQLException{var all=new LinkedHashSet<>(R2ContractServices.facts(c,tenant,opportunity.id()));all.add(opportunity);all.addAll(selected);return List.copyOf(all);}
 public void authorize(Connection c,Actor actor,Subject opportunity,String authority,List<Subject> selected)throws SQLException{
  if(!Set.of("TRANSFER_TASK_RECOVER","TRANSFER_SUBMIT","TRANSFER_REVIEW","TRANSFER_ACCEPT","MATTER_CLASSIFY","TRANSFER_READ").contains(authority))throw new Blocked("NOT_AUTHORIZED");
  var req=selectedRequest(c,actor.tenantId(),opportunity,selected);var fs=facts(c,actor.tenantId(),opportunity,selected);
  if(authority.equals("TRANSFER_TASK_RECOVER")){if(actor.principalKind()!=PrincipalKind.SERVICE||!contracts.permitted(c,actor,req.from(),fs,"CONTRACT_TASK_RECOVER"))throw new Blocked("NOT_AUTHORIZED");return;}
  if(actor.principalKind()!=PrincipalKind.HUMAN)throw new Blocked("NOT_AUTHORIZED");
  UUID org=authority.equals("TRANSFER_SUBMIT")?req.from():req.to();
  if(authority.equals("TRANSFER_READ")){if(!contracts.permitted(c,actor,req.from(),fs,"CONTRACT_READ")&&!contracts.permitted(c,actor,req.to(),fs,"CONTRACT_READ"))throw new Blocked("NOT_AUTHORIZED");return;}
  if(!contracts.permitted(c,actor,org,fs,authority)||!contracts.permitted(c,actor,org,fs,"CONTRACT_READ"))throw new Blocked("NOT_AUTHORIZED");
 }
 public void authorizePreparation(Connection c,Actor actor,Subject opportunity)throws SQLException{
  var sales=contracts.responsibility(c,actor.tenantId(),opportunity);if(sales==null||actor.principalKind()!=PrincipalKind.SERVICE||!contracts.permitted(c,actor,sales.organization(),facts(c,actor.tenantId(),opportunity,List.of()),"CONTRACT_TASK_RECOVER"))throw new TransferSubmissionService.Blocked("NOT_AUTHORIZED");
 }
 public TransferSubmissionService.Source source(Connection c,UUID tenant,UUID contract)throws SQLException{
  try(var p=prepare(c,"select contract_id from contract.contract where tenant_id=? and contract_id=? for update",tenant,contract);var r=p.executeQuery()){if(!r.next())return null;}
  return ContractTransferSourceReader.databaseBacked().find(c,tenant,contract).map(s->new TransferSubmissionService.Source(s.opportunityId(),s.contractId(),s.executionId(),s.activatedAt(),s.activationDigest())).orElse(null);
 }
 boolean configured(UUID tenant){return destinations.apply(tenant)!=null;}
 public TransferSubmissionService.Route route(Connection c,Actor actor,Subject opportunity)throws SQLException{
  var sales=contracts.responsibility(c,actor.tenantId(),opportunity);UUID destination=destinations.apply(actor.tenantId());if(sales==null||destination==null||destination.equals(sales.organization()))return null;
  try(var p=prepare(c,"select count(*) from identity.organization_unit where tenant_id=? and organization_unit_id in (?,?) and state='ACTIVE'",actor.tenantId(),sales.organization(),destination);var r=p.executeQuery()){if(!r.next()||r.getInt(1)!=2)return null;}
  return new TransferSubmissionService.Route(sales.organization(),destination);
 }
 private boolean affiliated(Connection c,UUID tenant,UUID appointment,UUID destination)throws SQLException{
  try(var p=prepare(c,"with recursive ancestors as (select o.organization_unit_id,o.parent_organization_unit_id from identity.appointment a join identity.organization_unit o on o.tenant_id=a.tenant_id and o.organization_unit_id=a.organization_unit_id where a.tenant_id=? and a.appointment_id=? union select o.organization_unit_id,o.parent_organization_unit_id from identity.organization_unit o join ancestors p on p.parent_organization_unit_id=o.organization_unit_id where o.tenant_id=?) select 1 from ancestors where organization_unit_id=?",tenant,appointment,tenant,destination);var r=p.executeQuery()){return r.next();}
 }
 public UUID owner(Connection c,Actor actor,Subject opportunity,UUID requestId,String stage,UUID submitter,UUID incumbent)throws SQLException{
  var req=request(c,actor.tenantId(),opportunity,requestId);boolean salesStage=Set.of("PREPARE","SUPPLEMENT").contains(stage);String code=switch(stage){case "PREPARE","SUPPLEMENT"->"TRANSFER_SUBMIT";case "REVIEW_TRANSFER"->"TRANSFER_REVIEW";case "INTAKE"->"TRANSFER_ACCEPT";case "CLASSIFY"->"MATTER_CLASSIFY";default->throw new IllegalArgumentException("Transfer responsibility stage required");};
  UUID org=salesStage?req.from():req.to();var fs=facts(c,actor.tenantId(),opportunity,List.of(req.fact()));var candidates=new LinkedHashSet<>(contracts.eligible(c,actor.tenantId(),org,fs,code));candidates.retainAll(contracts.eligible(c,actor.tenantId(),org,fs,"CONTRACT_READ"));
  var identity=AuthorizationIdentityReader.databaseBacked();var submitted=submitter==null?null:identity.owner(c,actor.tenantId(),submitter,now(c));
  var qualified=new LinkedHashSet<UUID>();for(var candidate:candidates){var person=identity.owner(c,actor.tenantId(),candidate,now(c));if(person==null||!person.active())continue;if(!salesStage&&!affiliated(c,actor.tenantId(),candidate,req.to()))continue;if(submitted!=null&&Set.of("REVIEW_TRANSFER","INTAKE").contains(stage)&&submitted.principalId().equals(person.principalId()))continue;qualified.add(candidate);}
  if(salesStage){var sales=contracts.responsibility(c,actor.tenantId(),opportunity);return sales!=null&&qualified.contains(sales.owner())?sales.owner():null;}
  if(incumbent!=null&&qualified.contains(incumbent))return incumbent;
  if(contracts.routingEnabled(actor.tenantId()))return contracts.routingTarget(actor.tenantId(),req.from(),stage).filter(qualified::contains).orElse(null);
  return qualified.size()==1?qualified.iterator().next():null;
 }
 private static Task neutral(TaskFactory.Task task){return task==null?null:new Task(task.selector(),task.owner(),task.state());}
 public Task task(Connection c,UUID tenant,UUID id)throws SQLException{return neutral(tasks.read(c,tenant,id));}
 public Task create(Connection c,UUID tenant,Subject opportunity,UUID owner,String stage,Instant now,Instant due)throws SQLException{var type=switch(stage){case "PREPARE"->TaskFactory.Type.PREPARE_TRANSFER;case "SUPPLEMENT"->TaskFactory.Type.SUPPLEMENT_TRANSFER;case "REVIEW_TRANSFER"->TaskFactory.Type.REVIEW_TRANSFER;case "INTAKE"->TaskFactory.Type.ACCEPT_TRANSFER;case "CLASSIFY"->TaskFactory.Type.CLASSIFY_MATTER;default->throw new IllegalArgumentException("Transfer responsibility stage required");};return neutral(tasks.createTransferTask(c,tenant,type,owner,opportunity,ZoneId.of("Asia/Shanghai"),now,due));}
 public void complete(Connection c,UUID tenant,Task task,Subject fact,Instant now)throws SQLException{tasks.complete(c,tenant,tasks.read(c,tenant,task.selector().id()),fact,now);}
 public void cancel(Connection c,UUID tenant,Task task,Instant now)throws SQLException{tasks.cancelTransferTask(c,tenant,tasks.read(c,tenant,task.selector().id()),now);}
 public void validateCorrectionMaterial(Connection c,Actor actor,Subject opportunity,TransferSubmissionInput.Material material)throws SQLException{
  if(!contracts.documentUsable(c,actor.tenantId(),material.versionId(),material.sha256()))throw new Blocked("STALE_EVIDENCE");
  try(var p=prepare(c,"select 1 from opportunity.material_version where tenant_id=? and material_version_id=? and opportunity_id=? and evidence_submission_id is not null",actor.tenantId(),material.versionId(),opportunity.id());var r=p.executeQuery()){if(!r.next())throw new Blocked("STALE_EVIDENCE");}
 }
 private ContractPreparationRepository.Codec codec(){return new ContractPreparationRepository.Codec(){public String encode(Map<String,Object> value){return ContractCanonicalJson.encode(value);}@SuppressWarnings("unchecked") public Map<String,Object> decode(String value){return tools.jackson.databind.json.JsonMapper.builder().build().readValue(value,Map.class);}};}
 public Basis validate(Connection c,Actor actor,Subject opportunity,UUID contract,TransferSubmissionInput input)throws SQLException{
  validateCorrectionMaterial(c,actor,opportunity,input.clientIdentity());validateCorrectionMaterial(c,actor,opportunity,input.signatureArchive());
  var source=ContractTransferSourceReader.databaseBacked().find(c,actor.tenantId(),contract).filter(s->s.opportunityId().equals(opportunity.id())).orElseThrow(()->new Blocked("STALE_SUBJECT"));
  try(var p=prepare(c,"select c.customer_requirement_confirmation_id,o.legal_need_digest from opportunity.customer_requirement_confirmation c join opportunity.opportunity o on o.tenant_id=c.tenant_id and o.opportunity_id=c.opportunity_id where c.tenant_id=? and c.opportunity_id=? and not exists(select 1 from opportunity.customer_requirement_confirmation n where n.tenant_id=c.tenant_id and n.previous_confirmation_id=c.customer_requirement_confirmation_id)",actor.tenantId(),opportunity.id());var r=p.executeQuery()){
   if(!r.next())throw new Blocked("CUSTOMER_CONFIRMATION_REQUIRED");UUID confirmation=r.getObject(1,UUID.class);
   try{ContractCustomerSnapshotReader.databaseBacked(protection,R2ContractPreparationSources.create(materials),codec()).digest(c,actor.tenantId(),confirmation);}catch(ContractWorkflowService.Blocked failure){throw new Blocked("STALE_CUSTOMER_BASIS");}
   return new Basis(source.revisionId(),confirmation,source.activationDigest(),HexFormat.of().formatHex(r.getBytes(2)));
  }
 }
 public Draft confirmDraft(Connection c,Actor actor,Task task,String digest,Instant now)throws SQLException{
  var owner=ActionDraftService.databaseBacked();var exact=tasks.read(c,actor.tenantId(),task.selector().id());var values=Map.<String,Object>of("submissionDigest",digest);var saved=owner.save(c,actor.tenantId(),exact,null,values,actor.appointmentId(),now).draft();
  owner.confirm(c,actor.tenantId(),exact,new ActionDraftService.Confirmation(saved.selector().id(),saved.selector().revision(),saved.digest()),values,actor.appointmentId(),now);return new Draft(saved.selector().id(),HexFormat.of().formatHex(Base64.getUrlDecoder().decode(saved.digest())));
 }
 private TransferConflictReviewService reviews(){return TransferConflictReviewService.databaseBacked(contracts::reviewScopeComplete,ContractCanonicalJson::encode,(t,o,id,body)->protection.seal(t,o,id,ContractProtection.Kind.TRANSFER,body),ContractConflictDecisions.databaseBackedForTransfer()::block);}
 public Subject review(Connection c,Actor a,Subject workflow,TransferConflictReviewInput input,Draft draft)throws SQLException{return reviews().record(c,a.tenantId(),workflow.id(),a.appointmentId(),input,draft);}
 public TransferConflictReviewRepository.Scan reviewPreview(Connection c,UUID tenant,UUID submission)throws SQLException{return reviews().inspect(c,tenant,submission);}
 @SuppressWarnings("unchecked") public void validateAcceptance(Connection c,Actor actor,Subject workflow)throws SQLException{
  try(var p=prepare(c,"select s.submission_id,s.opportunity_id,s.body_ciphertext,rv.scope_digest,rv.corpus_digest from transfer.workflow w join transfer.submission s on s.tenant_id=w.tenant_id and s.submission_id=w.submission_id join transfer.review rv on rv.tenant_id=w.tenant_id and rv.review_id=w.review_id where w.tenant_id=? and w.workflow_id=?",actor.tenantId(),workflow.id());var r=p.executeQuery()){
   if(!r.next())throw new Blocked("STALE_SUBJECT");UUID submission=r.getObject(1,UUID.class),opportunity=r.getObject(2,UUID.class);var scan=reviews().inspect(c,actor.tenantId(),submission);
   if(!scan.permittedOutcomes().contains("CLEAR")||!scan.scopeDigest().equals(HexFormat.of().formatHex(r.getBytes(4)))||!scan.corpusDigest().equals(HexFormat.of().formatHex(r.getBytes(5))))throw new Blocked("STALE_REVIEW");
   var body=codec().decode(protection.open(actor.tenantId(),opportunity,submission,ContractProtection.Kind.TRANSFER,r.getBytes(3)));var subject=new Subject("opportunity.opportunity",opportunity,0L,null);
   for(String key:List.of("clientIdentity","signatureArchive")){var material=(Map<String,Object>)body.get(key);validateCorrectionMaterial(c,actor,subject,new TransferSubmissionInput.Material(UUID.fromString((String)material.get("id")),(String)material.get("sha256")));}
   for(var item:(List<Map<String,Object>>)body.getOrDefault("corrections",List.of()))if(item.containsKey("materialId"))validateCorrectionMaterial(c,actor,subject,new TransferSubmissionInput.Material(UUID.fromString((String)item.get("materialId")),(String)item.get("materialSha256")));
  }
 }
 public Subject intakeDecision(Connection c,Actor actor,Task task,Subject snapshot,TransferIntakeInput input,Instant now)throws SQLException{return TransferIntakeDecisions.databaseBacked().record(c,actor.tenantId(),task.selector().id(),actor.appointmentId(),snapshot,input.decision(),input.explanation());}
 public void validateRecipient(Connection c,Actor actor,Subject opportunity,UUID requestId,TransferClassificationInput input)throws SQLException{
  var req=request(c,actor.tenantId(),opportunity,requestId);var fs=facts(c,actor.tenantId(),opportunity,List.of(req.fact()));
  if(!affiliated(c,actor.tenantId(),input.recipient(),req.to())||!contracts.eligible(c,actor.tenantId(),req.to(),fs,"MATTER_RECEIVE").contains(input.recipient())||!contracts.eligible(c,actor.tenantId(),req.to(),fs,"CONTRACT_READ").contains(input.recipient()))throw new Blocked("RECIPIENT_UNAVAILABLE");
 }
 public String encode(Map<String,Object> value){return ContractCanonicalJson.encode(value);}
 public byte[] seal(UUID tenant,UUID opportunity,UUID fact,String body){return protection.seal(tenant,opportunity,fact,ContractProtection.Kind.TRANSFER,body);}
}
