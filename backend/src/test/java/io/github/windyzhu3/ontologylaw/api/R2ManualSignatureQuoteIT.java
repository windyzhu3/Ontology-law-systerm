package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.contract.*;
import io.github.windyzhu3.ontologylaw.evidence.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import java.sql.*;
import java.util.*;
import java.nio.file.Path;
import java.io.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;

/** Actual quote response, real scanned bytes, production authority/task/receipt ports and bilateral signatures. */
class R2ManualSignatureQuoteIT extends R2ContractQuoteSourceIT {
 @TempDir Path signingObjects;
 @org.junit.jupiter.api.BeforeEach void resetSigningObjects(){objects=null;}
 MaterialObjectStore objects;ContractWorkflowService contracts;String materialSha;UUID signingMaterial;
 @Override Map<String,Object> doc(){var result=super.doc();result.put("unknownOpponent",false);return result;}
 @Override Subject material()throws Exception{
  if(objects==null)objects=MaterialObjectStore.localPrivate(signingObjects,input->new MalwareScanner.ScanResult(MalwareScanner.Verdict.CLEAN,"SYNTHETIC-SIGNATURE-IT"));
  var evidence=R2MaterialsServices.evidence(cipher);UUID upload;
  try(var c=database.apiConnection()){upload=inTransaction(c,Capability.COMMAND,x->{var conf=(Map<?,?>)quotes().context(x,seed.request().actor(),opportunity.id()).get("customerConfirmation");return evidence.open(x,seed.tenant(),opportunity,opportunity,seed.appointment(),new Subject("opportunity.customer_requirement_confirmation",UUID.fromString((String)conf.get("id")),0L,null),null,"CONTRACT_BUSINESS","合成签署测试材料.png","Synthetic workflow evidence; not a legal signature").selector().id();});}
  var image=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);var bytes=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"PNG",bytes);var stored=objects.store(new ByteArrayInputStream(bytes.toByteArray()));materialSha=stored.sha256();
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{var basis=evidence.basis(x,seed.tenant(),upload);var check=evidence.beginCheck(x,seed.tenant(),basis);evidence.completeCheck(x,seed.tenant(),basis,check,stored,"PASSED","CLEAN");return null;});}
  try(var c=database.apiConnection()){return inTransaction(c,Capability.COMMAND,x->{var basis=evidence.basis(x,seed.tenant(),upload);var accepted=evidence.accept(x,seed.tenant(),basis);var body=evidence.protectedBody(x,seed.tenant(),upload);return OpportunityMaterials.databaseBacked().accept(x,seed.tenant(),opportunity.id(),null,upload,accepted.source(),accepted.submission(),accepted.binding(),"CONTRACT_BUSINESS",seed.appointment(),accepted.at(),body.ciphertext(),body.digest()).selector();});}
 }
 Map<String,Object> contractContext()throws Exception{try(var c=database.apiConnection()){return inTransaction(c,Capability.QUERY,x->contracts.context(x,seed.request().actor(),opportunity.id()));}}
 @SuppressWarnings("unchecked") Map<String,Object> signature()throws Exception{return (Map<String,Object>)contractContext().get("signature");}
 Map<String,Object> payload(String action,Map<String,Object> values)throws Exception{var ctx=contractContext();var p=new LinkedHashMap<String,Object>();p.put("opportunityId",opportunity.id().toString());p.put("expectedOpportunityRevision",((Map<?,?>)ctx.get("opportunity")).get("revision"));p.put("responsibilityBasis",ctx.get("responsibilityBasis"));p.put("customerConfirmation",ctx.get("customerConfirmation"));var root=(Map<?,?>)ctx.get("contract");var draft=(Map<?,?>)ctx.get("draft");var workflow=(Map<?,?>)ctx.get("workflow");p.put("expectedContract",root==null?null:root.get("selector"));p.put("expectedVersion",root==null?null:root.get("currentRevision"));p.put("expectedDraft",draft==null?null:draft.get("selector"));p.put("expectedWorkflow",workflow==null?null:workflow.get("selector"));var input=new LinkedHashMap<>(values);if(ctx.get("signature") instanceof Map<?,?> signature)input.put("expectedSignatureWorkflow",((Map<?,?>)signature.get("workflow")).get("selector"));p.put("values",input);return p;}
 CommandOutcome commandContract(String action,Map<String,Object> values)throws Exception{var envelope=new CommandEnvelope(CommandEnvelope.Type.valueOf(action),UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),payload(action,values));var result=execute(envelope);assertEquals(CommandOutcome.Status.SUCCEEDED,result.status(),action+": "+result.rejectionCode());assertEquals(result,execute(envelope),"same key restores original receipt without another fact");
  var signature=signature();if(signature!=null&&signature.get("workflow") instanceof Map<?,?> workflow&&workflow.get("task") instanceof Map<?,?> task){
   UUID next=UUID.fromString((String)task.get("id"));try(var c=database.apiConnection()){
    var read=new CurrentWorkCardDisclosureService(protection,policies,"SIGN_QUOTE_IT",cipher).read(c,seed.request().actor(),UUID.randomUUID(),null,next);
    assertEquals(200,read.status(),action+" next workcard: "+read.errorCode());
    assertEquals(next.toString(),((Map<?,?>)read.body().get("currentCard")).get("taskId"));
    assertDoesNotThrow(()->R1WireModels.model(read.body(),io.github.windyzhu3.ontologylaw.api.adapter.generated.model.CurrentWorkCardEnvelope.class));
   }
  }return result;}
 @Test void accepted_quote_flows_through_production_signature_commands_to_archive()throws Exception{
  shortValidity=false;UUID response=reply("ACCEPTED");try(var c=database.apiConnection()){opportunity=inTransaction(c,Capability.QUERY,x->EventOpportunityReader.databaseBacked().byId(x,seed.tenant(),opportunity.id()).selector());}
  signingMaterial=material().id();UUID template=UUID.randomUUID(),policy=UUID.randomUUID();
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{for(String authority:List.of("CONTRACT_READ","CONTRACT_PREPARE","CONTRACT_REVIEW","CONTRACT_APPROVE","CONTRACT_SIGNATURE_VERIFY"))grant(x,authority);
   sql(x,"insert into contract.template_version(tenant_id,template_version_id,document_code,version_no,evidence_version_id,body_sha256,approved_by_appointment_id,approved_at,created_at) values(?,?,'SYNTHETIC_QUOTE_SIGNING',1,?,?,?,clock_timestamp(),clock_timestamp())",seed.tenant(),template,signingMaterial,HexFormat.of().parseHex(materialSha),seed.appointment());
   var parties=io.github.windyzhu3.ontologylaw.party.CustomerPartyProfiles.databaseBacked();var firm=parties.create(x,seed.tenant(),"ORGANIZATION","报价签署验收合成律所");var profile=parties.snapshot(x,seed.tenant(),firm,seed.appointment());sql(x,"insert into contract.template_signing_party(tenant_id,template_signing_party_id,template_version_id,party_id,party_revision,profile_version_id,party_snapshot_digest,role_code,created_by_appointment_id,created_at) values(?,?,?,?,?,?,?,'FIRM',?,clock_timestamp())",seed.tenant(),UUID.randomUUID(),template,firm.selector().id(),firm.selector().revision(),profile.selector().id(),ContractCanonicalJson.digest(profile.selector().id().toString()),seed.appointment());
   sql(x,"insert into contract.approval_policy(tenant_id,approval_policy_id,organization_unit_id,policy_code,policy_version,mode,policy_digest,created_at) values(?,?,?,'R2_CONTRACT_APPROVAL_V1',1,'REQUIRE_APPROVAL',decode(repeat('12',32),'hex'),clock_timestamp())",seed.tenant(),policy,seed.org());sql(x,"insert into contract.approval_policy_member(tenant_id,approval_policy_member_id,policy_id,requirement_code,appointment_id,created_at) values(?,?,?,'LEGAL',?,clock_timestamp())",seed.tenant(),UUID.randomUUID(),policy,seed.appointment());return null;});}
  contracts=R2ContractServices.create(ContractProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES")),cipher,objects);runtime=R2OpportunityCommandRuntime.fromSourcePolicy(policies,protection,cipher,null,"SIGN_QUOTE_IT",contracts);
  commandContract("START_CONTRACT_PREPARATION",Map.of());var ctx=contractContext();var root=(Map<?,?>)ctx.get("contract");assertEquals("ACCEPTED_QUOTE",((Map<?,?>)root.get("source")).get("kind"));assertEquals(response.toString(),((Map<?,?>)((Map<?,?>)root.get("source")).get("selector")).get("id"));
  commandContract("FORM_CONTRACT",Map.of("commercial",((Map<?,?>)root.get("document")).get("commercial"),"document",Map.of("evidenceVersionId",signingMaterial.toString(),"bodySha256",materialSha,"templateVersionId",template.toString(),"clauseVersionIds",List.of()),"signing",Map.of("partySnapshotDigest",ctx.get("partySnapshotDigest"),"requirements","客户及律所的有权代表签字并盖章"),"paymentGate",new LinkedHashMap<String,Object>(){{put("receiptRequiredBeforeTransfer",false);put("requiredMinor",null);}}));
  commandContract("REQUEST_CONTRACT_REVIEW",Map.of());commandContract("RECORD_CONTRACT_REVIEW",Map.of("decision","CLEAR","reason","核对准确全部参与主体及当前冲突资料"));commandContract("REQUEST_CONTRACT_APPROVAL",Map.of());commandContract("RECORD_CONTRACT_DECISION",Map.of("decision","APPROVED","reason","批准准确合同正文及签署要求"));
  var worker=service("CONTRACT_TASK_RECOVER");Map<String,Object> candidate;try(var c=database.apiConnection()){var candidates=(List<?>)new ContractPreparationDiscovery(contracts,new byte[32]).list(c,worker,50,null).get("candidates");assertEquals(1,candidates.size());candidate=new LinkedHashMap<>();((Map<?,?>)candidates.getFirst()).forEach((k,v)->candidate.put((String)k,v));}candidate.remove("kind");UUID key=UUID.fromString((String)candidate.remove("idempotencyKey"));var recovery=new CommandEnvelope(CommandEnvelope.Type.RECONCILE_CONTRACT_PREPARATION,key,UUID.randomUUID(),worker,candidate);var recovered=execute(recovery);assertEquals(CommandOutcome.Status.SUCCEEDED,recovered.status(),recovered.rejectionCode());assertEquals(recovered,execute(recovery));
  var slots=new ArrayList<Map<String,Object>>();for(Object raw:(List<?>)signature().get("parties")){var party=(Map<?,?>)raw;var slot=new LinkedHashMap<String,Object>();slot.put("slotNumber",slots.size()+1);slot.put("partyId",party.get("id"));if(party.get("participationId")!=null)slot.put("participationId",party.get("participationId"));else slot.put("templateSigningPartyId",party.get("templateSigningPartyId"));slot.put("authoritySlot","SIGNER_"+(slots.size()+1));slot.put("required",true);slot.put("signatureRequired",true);slot.put("sealRequired",true);slot.put("clauseBasis","批准合同签署条款");slots.add(slot);}assertEquals(2,slots.size());commandContract("CONFIRM_CONTRACT_SIGNATURE_ARRANGEMENT",Map.of("humanConfirmed",true,"slots",slots));
  for(int slot=1;slot<=slots.size();slot++){commandContract("SUBMIT_CONTRACT_SIGNATURE",Map.of("slotNumber",slot,"materialVersionId",signingMaterial.toString(),"materialSha256",materialSha,"authorityMaterialVersionId",signingMaterial.toString(),"authorityMaterialSha256",materialSha,"signerName","合成代表"+slot,"signedAt","2026-01-01T00:00:00Z"));commandContract("RECORD_CONTRACT_SIGNATURE_VERIFICATION",Map.of("decision","VERIFIED","reason","准确正文、代表权限、签字及盖章核验通过","contentCorresponds",true,"arrangementComplete",true,"authorityVerified",true,"signatureVerified",true,"sealVerified",true,"materialComplete",true));}
  commandContract("ARCHIVE_CONTRACT_SIGNATURE",Map.of("materialVersionId",signingMaterial.toString(),"materialSha256",materialSha,"archiveComplete",true,"reason","完整归档核对通过"));assertEquals("SIGNATURE_COMPLETE",((Map<?,?>)signature().get("workflow")).get("stage"));assertEquals("2",scalar("select count(*) from contract.contract_signature where tenant_id=?",seed.tenant()));assertEquals("1",scalar("select count(*) from contract.signature_handoff where tenant_id=?",seed.tenant()));assertEquals("0",scalar("select count(*) from contract.contract_execution where tenant_id=?",seed.tenant()));assertEquals("0",scalar("select count(*) from transfer.transfer_request where tenant_id=?",seed.tenant()));
 }
}
