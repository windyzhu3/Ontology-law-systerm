package io.github.windyzhu3.ontologylaw.transfer.internal.persistence;
import io.github.windyzhu3.ontologylaw.transfer.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;import java.time.*;import java.util.*;import java.security.MessageDigest;import java.security.NoSuchAlgorithmException;import java.nio.charset.StandardCharsets;

public final class JdbcTransferWorkflowService implements TransferWorkflowService {
 private final Ports ports;
 public JdbcTransferWorkflowService(Ports ports){this.ports=Objects.requireNonNull(ports);}
 private static Blocked stale(){return new Blocked("STALE_SUBJECT");}
 private static UUID id(Object value){return value==null?null:value instanceof UUID u?u:UUID.fromString(value.toString());}
 private static Instant instant(Object value){return value instanceof OffsetDateTime t?t.toInstant():((Timestamp)value).toInstant();}
 private static Subject fact(String type,Object id){return new Subject("transfer."+type,id(id),0L,null);}
 private static void expect(Subject s,String type){if(s==null||!s.type().equals("transfer."+type)||!Long.valueOf(0).equals(s.revision()))throw stale();}
 private static void transaction(Connection c)throws SQLException{if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw new IllegalStateException("Transfer transaction required");}
 private static void bind(PreparedStatement p,Object...args)throws SQLException{for(int i=0;i<args.length;i++)p.setObject(i+1,args[i] instanceof Instant t?t.atOffset(ZoneOffset.UTC):args[i]);}
 private static Map<String,Object> row(Connection c,String sql,Object...args)throws SQLException{try(var p=c.prepareStatement(sql)){bind(p,args);try(var r=p.executeQuery()){if(!r.next())return null;var out=new LinkedHashMap<String,Object>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)out.put(r.getMetaData().getColumnLabel(i),r.getObject(i));return out;}}}
 private static void write(Connection c,String sql,Object...args)throws SQLException{try(var p=c.prepareStatement(sql)){bind(p,args);if(p.executeUpdate()!=1)throw stale();}}
 private static UUID fresh(Connection c)throws SQLException{return id(row(c,"select uuidv7() id").get("id"));}
 private static byte[] hash(String text){try{return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 private record Locked(Subject opportunity,Map<String,Object> request){}
 private Locked lock(Connection c,Actor actor,UUID request)throws SQLException{return lock(c,actor,request,false);}
 private Locked lock(Connection c,Actor actor,UUID request,boolean accepted)throws SQLException{
  ports.lockAuthority(c,actor);
  var initial=row(c,"select opportunity_id,contract_id from transfer.transfer_request where tenant_id=? and transfer_request_id=?",actor.tenantId(),request);if(initial==null)throw stale();
  var opportunity=row(c,"select revision,closed_at from opportunity.opportunity where tenant_id=? and opportunity_id=? for update",actor.tenantId(),initial.get("opportunity_id"));
  if(opportunity==null||opportunity.get("closed_at")!=null)throw stale();
  var source=row(c,"select r.* from transfer.transfer_request r join contract.contract k on k.tenant_id=r.tenant_id and k.contract_id=r.contract_id and k.opportunity_id=r.opportunity_id and k.contract_execution_id=r.contract_execution_id and k.activation_source_hash=r.deal_activation_digest and k.deal_activated_at=r.deal_activated_at and k.current_revision_id=k.approved_revision_id where r.tenant_id=? and r.transfer_request_id=? and (r.accepted_snapshot_id is null or (? and r.matter_id is not null)) and k.contract_termination_id is null for update of k,r",actor.tenantId(),request,accepted);
  if(source==null)throw stale();return new Locked(new Subject("opportunity.opportunity",id(initial.get("opportunity_id")),((Number)opportunity.get("revision")).longValue(),null),source);
 }
 private Map<String,Object> current(Connection c,Actor actor,Subject expected)throws SQLException{
  expect(expected,"workflow");var w=row(c,"select w.* from transfer.workflow w where w.tenant_id=? and w.workflow_id=? and not exists(select 1 from transfer.workflow n where n.tenant_id=w.tenant_id and n.previous_workflow_id=w.workflow_id)",actor.tenantId(),expected.id());if(w==null)throw stale();return w;
 }
 private UUID requestOf(Connection c,Actor a,Subject w)throws SQLException{expect(w,"workflow");var r=row(c,"select transfer_request_id from transfer.workflow where tenant_id=? and workflow_id=?",a.tenantId(),w.id());if(r==null)throw stale();return id(r.get("transfer_request_id"));}
 private UUID qualifiedOwner(Connection c,Actor a,Locked locked,String stage,UUID submitter,UUID incumbent)throws SQLException{
  UUID owner=ports.owner(c,a,locked.opportunity(),id(locked.request().get("transfer_request_id")),stage,submitter,incumbent);if(owner==null)return null;
  var selected=row(c,"select a.principal_id from identity.appointment a join identity.principal p on p.tenant_id=a.tenant_id and p.principal_id=a.principal_id where a.tenant_id=? and a.appointment_id=? and a.state='ACTIVE' and p.state='ACTIVE' and p.principal_kind='HUMAN' and a.effective_from<=clock_timestamp() and (a.effective_until is null or a.effective_until>clock_timestamp())",a.tenantId(),owner);
  if(selected==null)return null;
  if(submitter!=null&&Set.of("REVIEW_TRANSFER","INTAKE").contains(stage)){var submitted=row(c,"select principal_id from identity.appointment where tenant_id=? and appointment_id=?",a.tenantId(),submitter);if(submitted==null||selected.get("principal_id").equals(submitted.get("principal_id")))return null;}
  return owner;
 }
 private Subject advance(Connection c,Actor actor,Locked locked,UUID previous,UUID submission,UUID review,UUID intake,UUID classification,String target,UUID owner,Instant now,Instant due)throws SQLException{
  Task task=owner==null?null:ports.create(c,actor.tenantId(),locked.opportunity(),owner,target,now,due);UUID workflow=fresh(c);
  write(c,"insert into transfer.workflow(tenant_id,workflow_id,transfer_request_id,opportunity_id,previous_workflow_id,stage_code,target_stage_code,owner_appointment_id,task_id,submission_id,review_id,intake_id,classification_id,recorded_by,due_at,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",actor.tenantId(),workflow,locked.request().get("transfer_request_id"),locked.opportunity().id(),previous,target.equals("COMPLETE")?"COMPLETE":owner==null?"OWNER_EXCEPTION":target,target,owner,task==null?null:task.selector().id(),submission,review,intake,classification,actor.appointmentId(),due,now);
  return fact("workflow",workflow);
 }
 public Subject start(Connection c,Actor actor,Subject request)throws SQLException{
  transaction(c);expect(request,"transfer_request");if(actor.principalKind()!=PrincipalKind.SERVICE)throw new Blocked("NOT_AUTHORIZED");var locked=lock(c,actor,request.id());
  ports.authorize(c,actor,locked.opportunity(),"TRANSFER_TASK_RECOVER",List.of(request));
  if(row(c,"select workflow_id from transfer.workflow where tenant_id=? and transfer_request_id=?",actor.tenantId(),request.id())!=null)throw stale();
  return advance(c,actor,locked,null,null,null,null,null,"PREPARE",qualifiedOwner(c,actor,locked,"PREPARE",null,null),ports.now(c),ports.due(instant(locked.request().get("deal_activated_at"))));
 }
 public Subject submit(Connection c,Actor actor,Subject expected,TransferSubmissionInput input)throws SQLException{
  transaction(c);if(actor.principalKind()!=PrincipalKind.HUMAN)throw new Blocked("NOT_AUTHORIZED");Objects.requireNonNull(input);
  var locked=lock(c,actor,requestOf(c,actor,expected));var w=current(c,actor,expected);
  ports.authorize(c,actor,locked.opportunity(),"TRANSFER_SUBMIT",List.of(expected,fact("transfer_request",locked.request().get("transfer_request_id"))));
  if(!Set.of("PREPARE","SUPPLEMENT").contains(w.get("stage_code"))||!actor.appointmentId().equals(w.get("owner_appointment_id")))throw new Blocked("NOT_AUTHORIZED");
  var task=ports.task(c,actor.tenantId(),id(w.get("task_id")));if(task==null||!task.owner().equals(actor.appointmentId())||!"OPEN".equals(task.state()))throw stale();
  var required=new HashMap<UUID,String>();if("SUPPLEMENT".equals(w.get("stage_code"))){
   try(var p=c.prepareStatement(w.get("intake_id")==null?"select review_return_item_id,requirement_code from transfer.review_return_item where tenant_id=? and review_id=?":"select r.transfer_return_item_id,r.requirement_code from transfer.transfer_return_item r join transfer.intake i on i.tenant_id=r.tenant_id and i.decision_record_id=r.return_decision_record_id where i.tenant_id=? and i.intake_id=?")){bind(p,actor.tenantId(),w.get("intake_id")==null?w.get("review_id"):w.get("intake_id"));try(var r=p.executeQuery()){while(r.next())required.put(r.getObject(1,UUID.class),r.getString(2));}}
   if(required.isEmpty())throw stale();
  }
  input.requireReturnItems(required.keySet());for(var correction:input.corrections()){if(correction.material()==null&&!"HANDOVER_EXPLANATION".equals(required.get(correction.returnItemId())))throw new IllegalArgumentException("Correction evidence required");if(correction.material()!=null)ports.validateCorrectionMaterial(c,actor,locked.opportunity(),correction.material());}
  var basis=ports.validate(c,actor,locked.opportunity(),id(locked.request().get("contract_id")),input);
  var clear=new LinkedHashMap<String,Object>();clear.put("clientIdentity",Map.of("id",input.clientIdentity().versionId().toString(),"sha256",input.clientIdentity().sha256()));clear.put("signatureArchive",Map.of("id",input.signatureArchive().versionId().toString(),"sha256",input.signatureArchive().sha256()));clear.put("explanation",input.explanation());clear.put("consistencyChecked",true);clear.put("customerConfirmation",basis.confirmationId().toString());clear.put("contractRevision",basis.revisionId().toString());clear.put("contractContext",basis.contractDigest());clear.put("legalNeed",basis.legalNeedDigest());
  if(!required.isEmpty()){clear.put("previousSubmission",w.get("submission_id").toString());clear.put("previousReview",w.get("review_id").toString());if(w.get("intake_id")!=null)clear.put("previousIntake",w.get("intake_id").toString());clear.put("corrections",input.corrections().stream().sorted(Comparator.comparing(correction->correction.returnItemId().toString())).map(correction->{var value=new LinkedHashMap<String,Object>();value.put("returnItemId",correction.returnItemId().toString());value.put("response",correction.response());if(correction.material()!=null){value.put("materialId",correction.material().versionId().toString());value.put("materialSha256",correction.material().sha256());}return value;}).toList());}
  String body=ports.encode(clear);byte[] digest=hash(body);Instant now=ports.now(c);var draft=ports.confirmDraft(c,actor,task,HexFormat.of().formatHex(digest),now);UUID submission=fresh(c);
  var evidence=new TreeSet<String>();var materials=new ArrayList<TransferSubmissionInput.Material>(List.of(input.clientIdentity(),input.signatureArchive()));input.corrections().stream().filter(item->item.material()!=null).forEach(item->materials.add(item.material()));
  for(var material:materials){var accepted=row(c,"select evidence_submission_id from opportunity.material_version where tenant_id=? and material_version_id=? and opportunity_id=? and evidence_submission_id is not null",actor.tenantId(),material.versionId(),locked.opportunity().id());if(accepted==null)throw stale();evidence.add(accepted.get("evidence_submission_id").toString());}
  write(c,"insert into transfer.submission(tenant_id,submission_id,evidence_submission_ids,transfer_request_id,opportunity_id,workflow_id,previous_submission_id,previous_review_id,previous_intake_id,contract_revision_id,customer_confirmation_id,client_identity_material_id,signature_archive_material_id,confirmed_action_draft_id,action_draft_digest,contract_context_digest,legal_need_context_digest,recorded_by,body_ciphertext,body_digest,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",actor.tenantId(),submission,c.createArrayOf("uuid",evidence.stream().map(UUID::fromString).toArray()),locked.request().get("transfer_request_id"),locked.opportunity().id(),expected.id(),w.get("submission_id"),w.get("review_id"),w.get("intake_id"),basis.revisionId(),basis.confirmationId(),input.clientIdentity().versionId(),input.signatureArchive().versionId(),draft.id(),HexFormat.of().parseHex(draft.digest()),HexFormat.of().parseHex(basis.contractDigest()),HexFormat.of().parseHex(basis.legalNeedDigest()),actor.appointmentId(),ports.seal(actor.tenantId(),locked.opportunity().id(),submission,body),digest,now);
  var result=fact("submission",submission);ports.complete(c,actor.tenantId(),task,result,now);
  advance(c,actor,locked,expected.id(),submission,null,null,null,"REVIEW_TRANSFER",qualifiedOwner(c,actor,locked,"REVIEW_TRANSFER",actor.appointmentId(),null),now,instant(w.get("due_at")));
  return result;
 }
 public Subject review(Connection c,Actor actor,Subject expected,TransferConflictReviewInput input)throws SQLException{
  transaction(c);Objects.requireNonNull(input);if(actor.principalKind()!=PrincipalKind.HUMAN)throw new Blocked("NOT_AUTHORIZED");
  var locked=lock(c,actor,requestOf(c,actor,expected));var w=current(c,actor,expected);
  if(!"REVIEW_TRANSFER".equals(w.get("stage_code"))||w.get("submission_id")==null)throw stale();
  ports.authorize(c,actor,locked.opportunity(),"TRANSFER_REVIEW",List.of(expected,fact("submission",w.get("submission_id"))));
  if(!"REVIEW_TRANSFER".equals(w.get("stage_code"))||!actor.appointmentId().equals(w.get("owner_appointment_id")))throw new Blocked("NOT_AUTHORIZED");
  var task=ports.task(c,actor.tenantId(),id(w.get("task_id")));if(task==null||!"OPEN".equals(task.state())||!actor.appointmentId().equals(task.owner()))throw stale();
  UUID submitter=id(row(c,"select recorded_by from transfer.submission where tenant_id=? and submission_id=?",actor.tenantId(),w.get("submission_id")).get("recorded_by"));
  if(row(c,"select 1 from identity.appointment a join identity.appointment b on b.tenant_id=a.tenant_id and b.principal_id=a.principal_id where a.tenant_id=? and a.appointment_id=? and b.appointment_id=?",actor.tenantId(),actor.appointmentId(),submitter)!=null)throw new Blocked("NOT_AUTHORIZED");
  Instant now=ports.now(c);String digest=HexFormat.of().formatHex(hash(ports.encode(Map.of("submission",w.get("submission_id").toString(),"outcome",input.outcome(),"explanation",input.explanation(),"scopeChecked",true))));
  var draft=ports.confirmDraft(c,actor,task,digest,now);var result=ports.review(c,actor,expected,input,draft);ports.complete(c,actor.tenantId(),task,result,now);
  String target=input.outcome().equals("CLEAR")?"INTAKE":"SUPPLEMENT";
  advance(c,actor,locked,expected.id(),id(w.get("submission_id")),result.id(),null,null,target,qualifiedOwner(c,actor,locked,target,submitter,null),now,instant(w.get("due_at")));return result;
 }
 public Subject intake(Connection c,Actor actor,Subject expected,TransferIntakeInput input)throws SQLException{
  transaction(c);Objects.requireNonNull(input);if(actor.principalKind()!=PrincipalKind.HUMAN)throw new Blocked("NOT_AUTHORIZED");
  var locked=lock(c,actor,requestOf(c,actor,expected));var w=current(c,actor,expected);
  if(!"INTAKE".equals(w.get("stage_code"))||w.get("submission_id")==null||w.get("review_id")==null)throw stale();
  ports.authorize(c,actor,locked.opportunity(),"TRANSFER_ACCEPT",List.of(expected,fact("submission",w.get("submission_id")),fact("review",w.get("review_id"))));
  if(!"INTAKE".equals(w.get("stage_code"))||!actor.appointmentId().equals(w.get("owner_appointment_id")))throw new Blocked("NOT_AUTHORIZED");
  var task=ports.task(c,actor.tenantId(),id(w.get("task_id")));if(task==null||!"OPEN".equals(task.state())||!actor.appointmentId().equals(task.owner()))throw stale();
  var submission=row(c,"select s.*,w.task_id submission_task from transfer.submission s join transfer.workflow w on w.tenant_id=s.tenant_id and w.workflow_id=s.workflow_id where s.tenant_id=? and s.submission_id=?",actor.tenantId(),w.get("submission_id"));
  if(row(c,"select 1 from identity.appointment a join identity.appointment b on b.tenant_id=a.tenant_id and b.principal_id=a.principal_id where a.tenant_id=? and a.appointment_id=? and b.appointment_id=?",actor.tenantId(),actor.appointmentId(),submission.get("recorded_by"))!=null)throw new Blocked("NOT_AUTHORIZED");
  if(input.decision().equals("ACCEPT"))ports.validateAcceptance(c,actor,expected);
  var review=row(c,"select * from transfer.review where tenant_id=? and review_id=? and outcome_code='CLEAR' and submission_id=?",actor.tenantId(),w.get("review_id"),w.get("submission_id"));if(review==null)throw stale();
  Instant now=ports.now(c);String body=ports.encode(Map.of("decision",input.decision(),"explanation",input.explanation(),"acceptanceChecked",input.acceptanceChecked(),"requirement",Objects.toString(input.requirement(),""),"submission",w.get("submission_id").toString(),"review",w.get("review_id").toString()));
  var draft=ports.confirmDraft(c,actor,task,HexFormat.of().formatHex(hash(body)),now);
  var previous=row(c,"select s.transfer_snapshot_id,s.snapshot_no,i.decision_record_id from transfer.transfer_snapshot s left join transfer.intake i on i.tenant_id=s.tenant_id and i.snapshot_id=s.transfer_snapshot_id and i.outcome_code='RETURN' where s.tenant_id=? and s.transfer_request_id=? order by s.snapshot_no desc limit 1",actor.tenantId(),locked.request().get("transfer_request_id"));
  byte[] returnDigest=null;if(previous!=null){if(previous.get("decision_record_id")==null)throw stale();var items=new ArrayList<Map<String,Object>>();try(var p=c.prepareStatement("select transfer_return_item_id,item_digest from transfer.transfer_return_item where tenant_id=? and return_decision_record_id=? order by item_no")){bind(p,actor.tenantId(),previous.get("decision_record_id"));try(var r=p.executeQuery()){while(r.next())items.add(Map.of("id",r.getObject(1).toString(),"hash",HexFormat.of().formatHex(r.getBytes(2))));}}if(items.isEmpty())throw stale();returnDigest=hash(ports.encode(Map.of("items",items)));}

  UUID snapshot=fresh(c);Object[] evidences=(Object[])((java.sql.Array)submission.get("evidence_submission_ids")).getArray();
  byte[] evidenceDigest=hash(ports.encode(Map.of("profile","R2_TRANSFER_EVIDENCE_V1","evidenceIds",Arrays.stream(evidences).map(Object::toString).sorted().toList())));
  byte[] snapshotDigest=hash(ports.encode(Map.of("profile","R2_TRANSFER_SNAPSHOT_V1","submission",w.get("submission_id").toString(),"submissionDigest",HexFormat.of().formatHex((byte[])submission.get("body_digest")),"review",w.get("review_id").toString(),"scope",HexFormat.of().formatHex((byte[])review.get("scope_digest")),"corpus",HexFormat.of().formatHex((byte[])review.get("corpus_digest")),"evidenceDigest",HexFormat.of().formatHex(evidenceDigest),"previousSnapshot",previous==null?"":previous.get("transfer_snapshot_id").toString(),"returnItemsDigest",returnDigest==null?"":HexFormat.of().formatHex(returnDigest))));
  write(c,"insert into transfer.transfer_snapshot(tenant_id,transfer_snapshot_id,transfer_request_id,snapshot_no,predecessor_snapshot_id,previous_return_decision_record_id,previous_return_items_digest,submission_task_occurrence_id,confirmed_action_draft_id,action_draft_digest,contract_context_digest,legal_need_context_digest,material_contract_code,material_contract_version,evidence_submission_ids,evidence_set_digest,pre_transfer_review_id,pre_transfer_scope_hash,snapshot_digest,submitted_by_appointment_id,submitted_at) values(?,?,?,?,?,?,?,?,?,?,?,?,'R2_TRANSFER_MATERIALS_V1',1,?,?,?,?,?,?,?)",actor.tenantId(),snapshot,locked.request().get("transfer_request_id"),previous==null?1:((Number)previous.get("snapshot_no")).intValue()+1,previous==null?null:previous.get("transfer_snapshot_id"),previous==null?null:previous.get("decision_record_id"),returnDigest,submission.get("submission_task"),submission.get("confirmed_action_draft_id"),submission.get("action_draft_digest"),submission.get("contract_context_digest"),submission.get("legal_need_context_digest"),c.createArrayOf("uuid",evidences),evidenceDigest,review.get("conflict_review_id"),review.get("scope_digest"),snapshotDigest,submission.get("recorded_by"),now);
  var snapshotRef=new Subject("transfer.transfer_snapshot",snapshot,null,Base64.getUrlEncoder().withoutPadding().encodeToString(snapshotDigest));var decision=ports.intakeDecision(c,actor,task,snapshotRef,input,now);
  UUID intake=fresh(c),matter=input.decision().equals("ACCEPT")?fresh(c):null;String number=matter==null?null:"R2-"+matter;
  write(c,"insert into transfer.intake(tenant_id,intake_id,transfer_request_id,opportunity_id,workflow_id,submission_id,review_id,snapshot_id,decision_record_id,outcome_code,requirement_code,matter_id,matter_no,confirmed_action_draft_id,action_draft_digest,recorded_by,body_ciphertext,body_digest,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",actor.tenantId(),intake,locked.request().get("transfer_request_id"),locked.opportunity().id(),expected.id(),w.get("submission_id"),w.get("review_id"),snapshot,decision.id(),input.decision(),input.requirement(),matter,number,draft.id(),HexFormat.of().parseHex(draft.digest()),actor.appointmentId(),ports.seal(actor.tenantId(),locked.opportunity().id(),intake,body),hash(body),now);
  if(input.decision().equals("ACCEPT"))write(c,"update transfer.transfer_request set accepted_snapshot_id=?,accept_decision_record_id=?,matter_id=?,matter_no=?,matter_type_code=proposed_matter_type_code,matter_capability_pack_code=proposed_capability_pack_code,matter_capability_pack_version=proposed_capability_pack_version,matter_created_at=?,revision=revision+1,changed_at=? where tenant_id=? and transfer_request_id=? and accepted_snapshot_id is null",snapshot,decision.id(),matter,number,now,now,actor.tenantId(),locked.request().get("transfer_request_id"));
  else{
   UUID item=fresh(c);byte[] itemDigest=hash(ports.encode(Map.of("snapshot",snapshot.toString(),"decision",decision.id().toString(),"requirement",input.requirement(),"intake",intake.toString(),"reasonDigest",HexFormat.of().formatHex(hash(body)))));
   write(c,"insert into transfer.transfer_return_item(tenant_id,transfer_return_item_id,transfer_request_id,reviewed_snapshot_id,return_decision_record_id,item_no,requirement_code,requirement_contract_version,reason_code,correction_instruction,required_evidence_purpose_code,item_digest,created_at,required_target_type,required_target_id,required_target_hash) values(?,?,?,?,?,1,?,1,'INTAKE_MATERIAL_INCOMPLETE','Read the protected intake explanation and respond to this exact item',?,?,?,'transfer.transfer_snapshot',?,?)",actor.tenantId(),item,locked.request().get("transfer_request_id"),snapshot,decision.id(),input.requirement(),input.requirement(),itemDigest,now,snapshot,snapshotDigest);
  }
  var result=fact("intake",intake);ports.complete(c,actor.tenantId(),task,result,now);
  advance(c,actor,locked,expected.id(),id(w.get("submission_id")),id(w.get("review_id")),intake,null,input.decision().equals("ACCEPT")?"CLASSIFY":"SUPPLEMENT",qualifiedOwner(c,actor,locked,input.decision().equals("ACCEPT")?"CLASSIFY":"SUPPLEMENT",id(submission.get("recorded_by")),null),now,instant(w.get("due_at")));return result;
 }
 public Subject classify(Connection c,Actor actor,Subject expected,TransferClassificationInput input)throws SQLException{
  transaction(c);Objects.requireNonNull(input);if(actor.principalKind()!=PrincipalKind.HUMAN)throw new Blocked("NOT_AUTHORIZED");
  var locked=lock(c,actor,requestOf(c,actor,expected),true);var w=current(c,actor,expected);
  if(!Set.of("CLASSIFY","COMPLETE").contains(w.get("stage_code"))||w.get("intake_id")==null||locked.request().get("matter_id")==null)throw stale();
  ports.authorize(c,actor,locked.opportunity(),"MATTER_CLASSIFY",List.of(expected,fact("intake",w.get("intake_id"))));
  if("COMPLETE".equals(w.get("stage_code"))){
   UUID owner=qualifiedOwner(c,actor,locked,"CLASSIFY",null,null);if(!actor.appointmentId().equals(owner))throw new Blocked("NOT_AUTHORIZED");
   ports.validateRecipient(c,actor,locked.opportunity(),id(locked.request().get("transfer_request_id")),input);
   expected=advance(c,actor,locked,expected.id(),id(w.get("submission_id")),id(w.get("review_id")),id(w.get("intake_id")),id(w.get("classification_id")),"CLASSIFY",owner,ports.now(c),instant(w.get("due_at")));w=current(c,actor,expected);
  }
  if(!"CLASSIFY".equals(w.get("stage_code"))||!actor.appointmentId().equals(w.get("owner_appointment_id"))||locked.request().get("matter_id")==null)throw new Blocked("NOT_AUTHORIZED");
  var task=ports.task(c,actor.tenantId(),id(w.get("task_id")));if(task==null||!"OPEN".equals(task.state())||!actor.appointmentId().equals(task.owner()))throw stale();
  ports.validateRecipient(c,actor,locked.opportunity(),id(locked.request().get("transfer_request_id")),input);
  Instant now=ports.now(c);String body=ports.encode(Map.of("category",input.category(),"recipient",input.recipient().toString(),"explanation",input.explanation(),"matter",locked.request().get("matter_id").toString(),"intake",w.get("intake_id").toString()));
  var draft=ports.confirmDraft(c,actor,task,HexFormat.of().formatHex(hash(body)),now);UUID classification=fresh(c);
  write(c,"insert into transfer.classification(tenant_id,classification_id,previous_classification_id,transfer_request_id,opportunity_id,workflow_id,intake_id,matter_id,category_code,recipient_appointment_id,confirmed_action_draft_id,action_draft_digest,recorded_by,body_ciphertext,body_digest,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",actor.tenantId(),classification,w.get("classification_id"),locked.request().get("transfer_request_id"),locked.opportunity().id(),expected.id(),w.get("intake_id"),locked.request().get("matter_id"),input.category(),input.recipient(),draft.id(),HexFormat.of().parseHex(draft.digest()),actor.appointmentId(),ports.seal(actor.tenantId(),locked.opportunity().id(),classification,body),hash(body),now);
  var result=fact("classification",classification);ports.complete(c,actor.tenantId(),task,result,now);
  advance(c,actor,locked,expected.id(),id(w.get("submission_id")),id(w.get("review_id")),id(w.get("intake_id")),classification,"COMPLETE",null,now,instant(w.get("due_at")));return result;
 }
 public boolean recoveryNeeded(Connection c,Actor actor,Subject expected)throws SQLException{
  if(actor.principalKind()!=PrincipalKind.SERVICE||actor.onBehalfAppointmentId()!=null)throw new Blocked("NOT_AUTHORIZED");
  var w=current(c,actor,expected);var request=row(c,"select * from transfer.transfer_request where tenant_id=? and transfer_request_id=?",actor.tenantId(),w.get("transfer_request_id"));
  var opportunity=row(c,"select revision,closed_at from opportunity.opportunity where tenant_id=? and opportunity_id=?",actor.tenantId(),w.get("opportunity_id"));if(request==null||opportunity==null)throw stale();
  var locked=new Locked(new Subject("opportunity.opportunity",id(w.get("opportunity_id")),((Number)opportunity.get("revision")).longValue(),null),request);
  ports.authorize(c,actor,locked.opportunity(),"TRANSFER_TASK_RECOVER",List.of(expected));
  if(opportunity.get("closed_at")!=null||"COMPLETE".equals(w.get("target_stage_code")))return false;
  UUID submitter=null;if(w.get("submission_id")!=null){var submitted=row(c,"select recorded_by from transfer.submission where tenant_id=? and submission_id=?",actor.tenantId(),w.get("submission_id"));if(submitted==null)throw stale();submitter=id(submitted.get("recorded_by"));}
  Task old=w.get("task_id")==null?null:ports.task(c,actor.tenantId(),id(w.get("task_id")));if(old!=null&&!Set.of("OPEN","WAITING","CANCELLED").contains(old.state()))return false;
  UUID owner=qualifiedOwner(c,actor,locked,(String)w.get("target_stage_code"),submitter,old==null?null:old.owner());
  return old==null?owner!=null:!("OPEN".equals(old.state())&&old.owner().equals(owner));
 }
 public Subject recover(Connection c,Actor actor,Subject expected)throws SQLException{
  transaction(c);if(actor.principalKind()!=PrincipalKind.SERVICE)throw new Blocked("NOT_AUTHORIZED");var initial=current(c,actor,expected);var locked=lock(c,actor,requestOf(c,actor,expected),"CLASSIFY".equals(initial.get("target_stage_code")));var w=current(c,actor,expected);
  ports.authorize(c,actor,locked.opportunity(),"TRANSFER_TASK_RECOVER",List.of(expected));UUID submitter=null;
  if(w.get("submission_id")!=null)submitter=id(row(c,"select recorded_by from transfer.submission where tenant_id=? and submission_id=?",actor.tenantId(),w.get("submission_id")).get("recorded_by"));
  String target=(String)w.get("target_stage_code");if(target.equals("COMPLETE"))throw stale();Task old=w.get("task_id")==null?null:ports.task(c,actor.tenantId(),id(w.get("task_id")));
  if(old!=null&&!Set.of("OPEN","WAITING","CANCELLED").contains(old.state()))throw stale();
  UUID owner=qualifiedOwner(c,actor,locked,target,submitter,old==null?null:old.owner());
  if(old==null&&owner==null||old!=null&&"OPEN".equals(old.state())&&old.owner().equals(owner))throw stale();
  Instant now=ports.now(c);if(old!=null&&Set.of("OPEN","WAITING").contains(old.state()))ports.cancel(c,actor.tenantId(),old,now);
  return advance(c,actor,locked,expected.id(),id(w.get("submission_id")),id(w.get("review_id")),id(w.get("intake_id")),id(w.get("classification_id")),target,owner,now,instant(w.get("due_at")));
 }
}
