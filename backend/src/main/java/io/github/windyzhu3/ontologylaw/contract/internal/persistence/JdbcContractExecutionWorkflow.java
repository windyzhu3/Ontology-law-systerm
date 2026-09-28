package io.github.windyzhu3.ontologylaw.contract.internal.persistence;
import io.github.windyzhu3.ontologylaw.contract.*;
import io.github.windyzhu3.ontologylaw.contract.ContractWorkflowService.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import static io.github.windyzhu3.ontologylaw.contract.internal.persistence.JdbcContractWorkflowService.*;

/** Runs inside the existing authorized command transaction and opportunity root lock. */
final class JdbcContractExecutionWorkflow {
 private final JdbcContractWorkflowService owner;private final Ports ports;private final ContractProtection protection;private final ContractPreparationRepository.Codec codec;
 JdbcContractExecutionWorkflow(JdbcContractWorkflowService owner,Ports ports,ContractProtection protection,ContractPreparationRepository.Codec codec){this.owner=owner;this.ports=ports;this.protection=protection;this.codec=codec;}
 Map<String,Object> latest(Connection c,UUID tenant,UUID handoff)throws SQLException{return row(c,"select w.* from contract.execution_workflow w where w.tenant_id=? and w.handoff_id=? and not exists(select 1 from contract.execution_workflow n where n.tenant_id=w.tenant_id and n.previous_workflow_id=w.execution_workflow_id)",tenant,handoff);}
 Map<String,Object> source(Connection c,UUID tenant,UUID opportunity)throws SQLException{return row(c,"select h.* from contract.signature_handoff h where h.tenant_id=? and h.opportunity_id=? order by h.created_at desc limit 1",tenant,opportunity);}
 Map<String,Object> context(Connection c,Actor actor,UUID opportunity)throws SQLException {
  var h=source(c,actor.tenantId(),opportunity);if(h==null)return null;
  UUID handoff=uuid(h.get("signature_handoff_id"));
  if(ContractExecutionSourceReader.databaseBacked().find(c,actor.tenantId(),handoff).isEmpty())return null;
  var w=latest(c,actor.tenantId(),handoff);var work=new LinkedHashMap<String,Object>();
  work.put("selector",w==null?null:selector("contract.execution_workflow",w.get("execution_workflow_id")));
  work.put("stage",w==null?"OWNER_EXCEPTION":w.get("stage_code"));
  work.put("ownerAppointmentId",w==null||w.get("owner_appointment_id")==null?null:w.get("owner_appointment_id").toString());
  work.put("ownerLabel","当前销售责任人");work.put("dueAt",w==null?null:time(w.get("due_at")).toString());work.put("task",null);
  if(w!=null&&w.get("task_id")!=null){var task=ports.currentTask(c,actor.tenantId(),uuid(w.get("task_id")));if(task!=null){work.put("task",selector(task.selector()));work.put("ownerAppointmentId",task.owner().toString());}}
  work.put("message",w==null?"签署归档已完成，正在接续执行条件责任。":switch((String)w.get("stage_code")){case "OWNER_EXCEPTION"->"请有权主管恢复当前销售负责人的合同读取及执行条件核验权限，系统将按原期限接续。";case "WAIT_RECEIPT"->"等待合同约定首款核实足额后，继续核对其他执行条件。";case "READY_TRANSFER"->"执行条件已确认，接下来准备转案资料。";default->"请对照本版批准正文核对执行条件。";});
  return Map.of("workflow",work,"handoff",selector("contract.signature_handoff",handoff));
 }
 boolean qualified(Connection c,Actor actor,Responsibility responsibility,List<Subject> facts)throws SQLException{return ports.eligible(c,actor.tenantId(),responsibility.organization(),facts,"CONTRACT_EXECUTION_VERIFY").contains(responsibility.owner())&&ports.eligible(c,actor.tenantId(),responsibility.organization(),facts,"CONTRACT_READ").contains(responsibility.owner());}
 List<String> actions(Connection c,Actor actor,Subject opportunity,Responsibility responsibility)throws SQLException {
  if(!responsibility.owner().equals(actor.appointmentId()))return List.of();
  var h=source(c,actor.tenantId(),opportunity.id());if(h==null)return List.of();var w=latest(c,actor.tenantId(),uuid(h.get("signature_handoff_id")));
  if(w==null||!"CHECK_CONDITIONS".equals(w.get("stage_code"))||w.get("task_id")==null)return List.of();
  var task=ports.currentTask(c,actor.tenantId(),uuid(w.get("task_id")));if(task==null||!"OPEN".equals(task.state())||!task.owner().equals(actor.appointmentId()))return List.of();
  var facts=new ArrayList<>(owner.protectedFacts(c,actor.tenantId(),opportunity.id()));facts.add(task.selector());
  return ports.permitted(c,actor,responsibility.organization(),facts,"CONTRACT_READ")&&ports.permitted(c,actor,responsibility.organization(),facts,"CONTRACT_EXECUTION_VERIFY")?List.of("VERIFY_CONTRACT_EXECUTION_CONDITIONS"):List.of();
 }
 boolean recoverable(Connection c,Actor actor,Responsibility responsibility,List<Subject> facts,Map<String,Object> w)throws SQLException {
  if(w==null)return true;
  if("READY_TRANSFER".equals(w.get("stage_code")))return false;
  boolean qualified=qualified(c,actor,responsibility,facts);
  if("OWNER_EXCEPTION".equals(w.get("stage_code")))return qualified;
  var task=ports.currentTask(c,actor.tenantId(),uuid(w.get("task_id")));
  if(task==null||!Set.of("OPEN","WAITING").contains(task.state()))return false;
  if(!qualified||!task.owner().equals(responsibility.owner()))return true;
  if(!"WAIT_RECEIPT".equals(w.get("stage_code"))||!"WAITING".equals(task.state()))return false;
  var basis=ContractExecutionSourceReader.databaseBacked().find(c,actor.tenantId(),uuid(w.get("handoff_id"))).orElseThrow(JdbcContractWorkflowService::stale);
  return !gate(c,actor.tenantId(),uuid(w.get("opportunity_id")),basis).waiting();
 }
 RecoveryCandidate recovery(Connection c,Actor actor,Subject opportunity,Responsibility responsibility,List<Subject> facts)throws SQLException{
  var source=source(c,actor.tenantId(),opportunity.id());if(source==null)return null;
  UUID handoff=uuid(source.get("signature_handoff_id"));
  if(ContractExecutionSourceReader.databaseBacked().find(c,actor.tenantId(),handoff).isEmpty())return null;
  var w=latest(c,actor.tenantId(),handoff);if(!recoverable(c,actor,responsibility,facts,w))return null;
  return new RecoveryCandidate(opportunity,responsibility.basis(),fact("contract.signature_handoff",handoff),w==null?null:fact("contract.execution_workflow",uuid(w.get("execution_workflow_id"))),responsibility.owner());
 }
 private record Gate(Subject version,boolean waiting) {}
 private Gate gate(Connection c,UUID tenant,UUID opportunity,ContractExecutionConditions.Basis basis)throws SQLException {
   var revision=row(c,"select * from contract.contract_revision where tenant_id=? and contract_revision_id=?",tenant,basis.revisionId());
   var body=owner.packageBody(tenant,opportunity,revision);var rawGate=map(body.get("paymentGate"));
   var gate=new ContractVersionInput.PaymentGate((Boolean)rawGate.get("receiptRequiredBeforeTransfer"),rawGate.get("requiredMinor")==null?null:number(rawGate.get("requiredMinor")));
   // Independent finance is not an execution prerequisite unless this exact approved version says so.
   if(!gate.receiptRequiredBeforeTransfer())return new Gate(version(revision),false);
   var receipts=new ArrayList<ContractExecutionConditions.Receipt>();
   for(var receipt:rows(c,"select * from contract.payment_confirmation where tenant_id=? and contract_id=? and contract_revision_id=? order by payment_confirmation_id",tenant,basis.contractId(),basis.revisionId())){
    // R2 does not implement refund/reversal disposition. Never silently count a reversed receipt.
    if(!"RECEIPT".equals(receipt.get("confirmation_type")))throw new Blocked("STALE_SUBJECT");
    receipts.add(new ContractExecutionConditions.Receipt(uuid(receipt.get("payment_confirmation_id")),tenant,basis.contractId(),basis.revisionId(),(String)receipt.get("currency_code"),number(receipt.get("amount_minor"))));
   }
   boolean waiting=ContractExecutionConditions.evaluate(basis,gate,(String)map(body.get("commercial")).get("currency"),false,receipts).remainingMinor()>0;
   return new Gate(version(revision),waiting);
 }
 Subject verify(Connection c,Actor actor,Subject opportunity,Responsibility responsibility,Subject confirmation,Map<String,Object> root,Map<String,Object> revision,Map<String,Object> values)throws SQLException {
  if(actor.principalKind()!=PrincipalKind.HUMAN)throw new Blocked("NOT_AUTHORIZED");
  if(!Set.of("expectedExecutionWorkflow","expectedTermination","approvedConditionsChecked","archiveAndConditionsComplete").containsAll(values.keySet())||!Boolean.TRUE.equals(values.get("approvedConditionsChecked"))||!Boolean.TRUE.equals(values.get("archiveAndConditionsComplete")))throw new Blocked("VALIDATION_FAILED");
  UUID tenant=actor.tenantId(),oid=opportunity.id();var h=source(c,tenant,oid);if(h==null)throw stale();
  var basis=ContractExecutionSourceReader.databaseBacked().find(c,tenant,uuid(h.get("signature_handoff_id"))).orElseThrow(JdbcContractWorkflowService::stale);
  var w=latest(c,tenant,basis.handoffId());expect(values.get("expectedExecutionWorkflow"),w==null?null:fact("contract.execution_workflow",uuid(w.get("execution_workflow_id"))));
  if(w==null||!"CHECK_CONDITIONS".equals(w.get("stage_code"))||w.get("task_id")==null)throw stale();
  var task=ports.currentTask(c,tenant,uuid(w.get("task_id")));var fs=new ArrayList<>(owner.protectedFacts(c,tenant,oid));if(task!=null)fs.add(task.selector());
  if(task==null||!"OPEN".equals(task.state())||!task.owner().equals(actor.appointmentId())||!responsibility.owner().equals(actor.appointmentId())||!ports.permitted(c,actor,responsibility.organization(),fs,"CONTRACT_EXECUTION_VERIFY")||!ports.permitted(c,actor,responsibility.organization(),fs,"CONTRACT_READ"))throw new Blocked("NOT_AUTHORIZED");
  if(gate(c,tenant,oid,basis).waiting())throw stale();
  var signatures=new JdbcManualSignatureWorkflow(owner,ports,protection,codec);var ready=signatures.readiness(c,tenant,revision);
  signatures.current(c,actor,responsibility,confirmation,root,revision,ready,fs);
  var arrangement=row(c,"select * from contract.signature_arrangement where tenant_id=? and signature_arrangement_id=?",tenant,h.get("arrangement_id"));
  var plan=signatures.plan(c,tenant,revision,ready,arrangement);var verified=signatures.verified(c,tenant,arrangement,plan);
  if(!plan.remainingRequired(verified).isEmpty())throw new Blocked("SIGNATURE_INCOMPLETE");
  var archive=row(c,"select * from contract.signature_archive where tenant_id=? and signature_archive_id=?",tenant,basis.archiveId());
  var archiveBody=signatures.body(tenant,oid,"signature_archive",archive);
  signatures.material(c,actor,oid,responsibility.organization(),fs,uuid(archive.get("material_version_id")),(String)archiveBody.get("materialSha256"),"CONTRACT_EXECUTION_VERIFY");
  for(var evidence:rows(c,"select u.* from contract.signature_plan p join contract.contract_signature s on s.tenant_id=p.tenant_id and s.signature_plan_id=p.signature_plan_id join contract.signature_verification v on v.tenant_id=s.tenant_id and v.signature_verification_id=s.verification_id join contract.signature_submission u on u.tenant_id=v.tenant_id and u.signature_submission_id=v.submission_id where p.tenant_id=? and p.arrangement_id=? and p.required and s.revoked_at is null",tenant,h.get("arrangement_id"))){
   signatures.material(c,actor,oid,responsibility.organization(),fs,uuid(evidence.get("material_version_id")),hex(evidence.get("material_sha256")),"CONTRACT_EXECUTION_VERIFY");
   signatures.material(c,actor,oid,responsibility.organization(),fs,uuid(evidence.get("authority_material_version_id")),hex(evidence.get("authority_material_sha256")),"CONTRACT_EXECUTION_VERIFY");
  }
  UUID verificationId=uuid(row(c,"select uuidv7() id").get("id"));Instant now=ports.now(c);
  var clear=new LinkedHashMap<String,Object>();clear.put("approvedConditionsChecked",true);clear.put("archiveAndConditionsComplete",true);clear.put("handoffId",basis.handoffId().toString());clear.put("archiveId",basis.archiveId().toString());clear.put("version",selector(version(revision)));clear.put("requiredMinor",revision.get("required_amount_minor"));
  clear.put("receipts",!Boolean.TRUE.equals(revision.get("receipt_required_before_transfer"))?List.of():rows(c,"select payment_confirmation_id,attribution_digest from contract.payment_confirmation where tenant_id=? and contract_id=? and contract_revision_id=? order by payment_confirmation_id",tenant,basis.contractId(),basis.revisionId()).stream().map(r->Map.of("id",r.get("payment_confirmation_id").toString(),"digest",hex(r.get("attribution_digest")))).toList());
  String body=ContractCanonicalJson.encode(clear);
  write(c,"insert into contract.execution_verification(tenant_id,execution_verification_id,opportunity_id,handoff_id,contract_revision_id,recorded_by,body_ciphertext,body_digest,created_at) values(?,?,?,?,?,?,?,?,?)",tenant,verificationId,oid,basis.handoffId(),basis.revisionId(),actor.appointmentId(),protection.seal(tenant,oid,verificationId,ContractProtection.Kind.EXECUTION,body),ContractCanonicalJson.digest(body),now);
  var binding=row(c,"select * from contract.revision_review_binding where tenant_id=? and contract_revision_id=?",tenant,basis.revisionId());
  var approvals=rows(c,"select d.revision_approval_decision_id from contract.revision_approval_requirement q join contract.revision_approval_decision d on d.tenant_id=q.tenant_id and d.requirement_id=q.revision_approval_requirement_id where q.tenant_id=? and q.contract_revision_id=? order by q.requirement_code",tenant,basis.revisionId()).stream().map(r->r.get("revision_approval_decision_id").toString()).toList();
  var signatureIds=rows(c,"select s.contract_signature_id from contract.signature_plan p join contract.contract_signature s on s.tenant_id=p.tenant_id and s.signature_plan_id=p.signature_plan_id where p.tenant_id=? and p.arrangement_id=? and p.required and s.revoked_at is null order by p.slot_no",tenant,h.get("arrangement_id")).stream().map(r->r.get("contract_signature_id").toString()).toList();
  UUID executionId=uuid(row(c,"select uuidv7() id").get("id"));
  var executionBody=new LinkedHashMap<String,Object>(clear);executionBody.put("verificationId",verificationId.toString());executionBody.put("approvalIds",approvals);executionBody.put("signatureIds",signatureIds);executionBody.put("reviewBindingId",binding.get("revision_review_binding_id").toString());
  byte[] executionDigest=ContractCanonicalJson.digest(ContractCanonicalJson.encode(executionBody));
  write(c,"insert into contract.contract_execution(tenant_id,contract_execution_id,contract_id,contract_revision_id,approval_set_digest,signature_set_digest,review_scope_hash,review_resolution_digest,execution_verification_id,execution_digest,executed_by_appointment_id,executed_at) values(?,?,?,?,?,?,?,?,?,?,?,?)",tenant,executionId,basis.contractId(),basis.revisionId(),ContractCanonicalJson.digest(ContractCanonicalJson.encode(approvals)),ContractCanonicalJson.digest(ContractCanonicalJson.encode(signatureIds)),binding.get("scope_hash"),binding.get("resolution_digest"),verificationId,executionDigest,actor.appointmentId(),now);
  write(c,"update contract.contract set contract_execution_id=?,deal_activated_at=?,activation_source_type='contract.contract_execution',activation_source_id=?,activation_source_hash=?,revision=revision+1,changed_at=? where tenant_id=? and contract_id=? and revision=? and current_revision_id=? and approved_revision_id=? and contract_execution_id is null",executionId,now,executionId,executionDigest,now,tenant,basis.contractId(),root.get("revision"),basis.revisionId(),basis.revisionId());
  var result=fact("contract.execution_verification",verificationId);ports.complete(c,tenant,task,result,now);
  write(c,"insert into contract.execution_workflow(tenant_id,execution_workflow_id,opportunity_id,handoff_id,contract_revision_id,previous_workflow_id,stage_code,owner_appointment_id,verification_id,execution_id,recorded_by,due_at,created_at) values(?,?,?,?,?,?,'READY_TRANSFER',?,?,?,?,?,?)",tenant,uuid(row(c,"select uuidv7() id").get("id")),oid,basis.handoffId(),basis.revisionId(),w.get("execution_workflow_id"),actor.appointmentId(),verificationId,executionId,actor.appointmentId(),w.get("due_at"),now);
  return result;
 }
 Subject reconcile(Connection c,Actor actor,Subject opportunity,Responsibility responsibility,Map<String,Object> payload)throws SQLException{
  var source=source(c,actor.tenantId(),opportunity.id());if(source==null)throw stale();
  UUID handoff=uuid(source.get("signature_handoff_id"));expect(payload.get("source"),fact("contract.signature_handoff",handoff));
  var basis=ContractExecutionSourceReader.databaseBacked().find(c,actor.tenantId(),handoff).orElseThrow(JdbcContractWorkflowService::stale);
  var w=latest(c,actor.tenantId(),handoff);expect(payload.get("expectedWorkflow"),w==null?null:fact("contract.execution_workflow",uuid(w.get("execution_workflow_id"))));
  var facts=owner.protectedFacts(c,actor.tenantId(),opportunity.id());
  if(!ports.permitted(c,actor,responsibility.organization(),facts,"CONTRACT_TASK_RECOVER"))throw new Blocked("NOT_AUTHORIZED");
  if(!recoverable(c,actor,responsibility,facts,w))throw stale();
  Instant now=ports.now(c),due=w==null?ports.signatureDue(time(source.get("created_at")),ZoneId.of("Asia/Shanghai")):time(w.get("due_at"));
  var prior=w==null||w.get("task_id")==null?null:ports.currentTask(c,actor.tenantId(),uuid(w.get("task_id")));
  boolean eligible=qualified(c,actor,responsibility,facts);
  var gate=eligible?gate(c,actor.tenantId(),opportunity.id(),basis):null;
  boolean waiting=gate!=null&&gate.waiting();
  boolean resume=eligible&&prior!=null&&"WAIT_RECEIPT".equals(w.get("stage_code"))&&"WAITING".equals(prior.state())&&prior.owner().equals(responsibility.owner())&&!waiting;
  Task next;
  if(resume)next=ports.resumeReceipt(c,actor.tenantId(),prior,gate.version());
  else {
   if(prior!=null)ports.cancelForContract(c,actor.tenantId(),prior,"CONTRACT_AUTHORITY_MISSING",now);
   next=eligible?ports.createSignatureTask(c,actor.tenantId(),"CHECK_CONTRACT_EXECUTION",responsibility.owner(),opportunity,ZoneId.of("Asia/Shanghai"),now,due):null;
   if(next!=null&&waiting)next=ports.waitForReceipt(c,actor.tenantId(),next,actor.appointmentId(),now,gate.version());
  }
  UUID id=uuid(row(c,"select uuidv7() id").get("id"));
  write(c,"insert into contract.execution_workflow(tenant_id,execution_workflow_id,opportunity_id,handoff_id,contract_revision_id,previous_workflow_id,stage_code,owner_appointment_id,task_id,verification_id,recorded_by,due_at,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?)",actor.tenantId(),id,opportunity.id(),handoff,basis.revisionId(),w==null?null:w.get("execution_workflow_id"),eligible?(waiting?"WAIT_RECEIPT":"CHECK_CONDITIONS"):"OWNER_EXCEPTION",eligible?responsibility.owner():null,next==null?null:next.selector().id(),w==null?null:w.get("verification_id"),actor.appointmentId(),due,now);
  return fact("contract.execution_workflow",id);
 }
}
