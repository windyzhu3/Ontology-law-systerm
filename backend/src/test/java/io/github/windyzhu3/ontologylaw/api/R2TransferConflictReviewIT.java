package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.transfer.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.contract.*;
import java.util.*;import java.time.*;import io.github.windyzhu3.ontologylaw.lead.*;import io.github.windyzhu3.ontologylaw.responsibility.TaskFactory;import io.github.windyzhu3.ontologylaw.execution.CommandOutcome;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
class R2TransferConflictReviewIT extends R2TransferWorkflowIT {
 @Test void independent_transfer_review_uses_exact_submission_and_never_reuses_contract_review()throws Exception{
  var initial=beginTransfer();var submitted=submitTransfer(initial);
  var repository=TransferConflictReviewRepository.databaseBacked((c,t,o)->true,ContractCanonicalJson::encode);
  try(var c=database.apiConnection()){
   var scan=inTransaction(c,Capability.COMMAND,x->repository.inspect(x,seed.tenant(),submitted.id()));
   assertEquals(submitted.id(),scan.submissionId());assertTrue(scan.scopeComplete());
   assertEquals(0,scan.candidates().size());assertTrue(scan.permittedOutcomes().contains("CLEAR"));
   assertEquals(64,scan.scopeDigest().length());assertEquals(64,scan.corpusDigest().length());
  }
  assertEquals("0",scalar("select count(*) from conflict.conflict_review where tenant_id=? and review_type_code='PRE_TRANSFER'",seed.tenant()));
 }
 @Test void independent_transfer_review_incomplete_corpus_cannot_offer_clear()throws Exception{
  var initial=beginTransfer();var submitted=submitTransfer(initial);
  try(var c=database.apiConnection()){
   var scan=inTransaction(c,Capability.COMMAND,x->TransferConflictReviewRepository.databaseBacked((a,b,d)->false,ContractCanonicalJson::encode).inspect(x,seed.tenant(),submitted.id()));
   assertFalse(scan.scopeComplete());assertFalse(scan.permittedOutcomes().contains("CLEAR"));
  }
 }

 @Test void independent_transfer_review_clear_completes_review_and_creates_intake_without_case()throws Exception{
  var initial=beginTransfer();submitTransfer(initial);
  UUID workflow=UUID.fromString(scalar("select workflow_id from transfer.workflow where tenant_id=? and stage_code='REVIEW_TRANSFER'",seed.tenant()));
  UUID principal=UUID.fromString(scalar("select principal_id from identity.appointment where tenant_id=? and appointment_id=?",seed.tenant(),reviewer));
  var reviewActor=new Actor(seed.tenant(),principal,reviewer,null,null,PrincipalKind.HUMAN);
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->workflows().review(x,reviewActor,new Subject("transfer.workflow",workflow,0L,null),new TransferConflictReviewInput("CLEAR","已核对本次主体和冲突依据",true)));}
  assertEquals("1",scalar("select count(*) from transfer.review where tenant_id=? and outcome_code='CLEAR'",seed.tenant()));
  assertEquals("1",scalar("select count(*) from conflict.conflict_review where tenant_id=? and review_type_code='PRE_TRANSFER'",seed.tenant()));
  assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='REVIEW_TRANSFER' and state='DONE'",seed.tenant()));
  assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='ACCEPT_TRANSFER' and state='OPEN'",seed.tenant()));
  assertEquals("0",scalar("select count(*) from transfer.transfer_request where tenant_id=? and matter_id is not null",seed.tenant()));
 }

 Subject currentReview()throws Exception{return new Subject("transfer.workflow",UUID.fromString(scalar("select workflow_id from transfer.workflow where tenant_id=? and stage_code='REVIEW_TRANSFER'",seed.tenant())),0L,null);}
 Actor reviewerActor()throws Exception{return new Actor(seed.tenant(),UUID.fromString(scalar("select principal_id from identity.appointment where tenant_id=? and appointment_id=?",seed.tenant(),reviewer)),reviewer,null,null,PrincipalKind.HUMAN);}
 @Test void independent_transfer_review_need_info_returns_to_sales_with_original_deadline()throws Exception{
  var initial=beginTransfer();submitTransfer(initial);var w=currentReview();var a=reviewerActor();String due=scalar("select due_at::text from transfer.workflow where tenant_id=? and workflow_id=?",seed.tenant(),w.id());
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->workflows().review(x,a,w,new TransferConflictReviewInput("NEED_INFO","请补齐实际相对方的主体依据",true)));}
  assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='SUPPLEMENT_TRANSFER' and state='OPEN' and owner_appointment_id=?",seed.tenant(),seed.appointment()));
  assertEquals(due,scalar("select due_at::text from transfer.workflow where tenant_id=? and stage_code='SUPPLEMENT'",seed.tenant()));
 }
 @Test void independent_transfer_review_denies_sales_and_rolls_back_all_facts_on_audit_failure()throws Exception{
  var initial=beginTransfer();submitTransfer(initial);var w=currentReview();var a=reviewerActor();var input=new TransferConflictReviewInput("CLEAR","已核对",true);
  try(var c=database.apiConnection()){assertThrows(TransferWorkflowService.Blocked.class,()->inTransaction(c,Capability.COMMAND,x->workflows().review(x,actor(),w,input)));}
  try(var c=database.apiConnection()){assertThrows(IllegalStateException.class,()->inTransaction(c,Capability.COMMAND,x->{workflows().review(x,a,w,input);throw new IllegalStateException("audit failure");}));}
  assertEquals("0",scalar("select count(*) from transfer.review where tenant_id=?",seed.tenant()));
  assertEquals("0",scalar("select count(*) from conflict.conflict_review where tenant_id=? and review_type_code='PRE_TRANSFER'",seed.tenant()));
  assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='REVIEW_TRANSFER' and state='OPEN'",seed.tenant()));
 }
    UUID otherOpportunityWithSameParty()throws Exception {
        UUID other=UUID.randomUUID(),contact=UUID.randomUUID();
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            var leads=LeadIngressService.databaseBacked(protection);var now=leads.now(x);
            var lead=leads.capture(x,seed.tenant(),input(true),ContractCanonicalJson.digest(UUID.randomUUID().toString()),now);
            var assignment=leads.assign(x,seed.tenant(),lead,seed.appointment(),"MANUAL_SELECTION",now);
            lead=leads.update(x,seed.tenant(),lead,null,null,null,seed.appointment(),assignment.selector().id(),now);
            var task=tasks.create(x,seed.tenant(),TaskFactory.Type.CONTACT_LEAD,seed.appointment(),lead.selector(),ZoneId.of("Asia/Shanghai"),now);
            sql(x,"insert into lead.lead_contact_result(tenant_id,lead_contact_result_id,lead_id,lead_assignment_id,contact_no,contact_task_id,contact_channel_code,result_code,resulted_at,created_at) values(?,?,?,?,1,?,'PHONE','CONNECTED_VALID',?,?)",seed.tenant(),contact,lead.selector().id(),assignment.selector().id(),task.selector().id(),now.atOffset(ZoneOffset.UTC),now.atOffset(ZoneOffset.UTC));
            sql(x,"insert into opportunity.opportunity(tenant_id,opportunity_id,source_lead_id,source_assignment_id,source_contact_result_id,owner_appointment_id,legal_need_ciphertext,legal_need_digest,created_at) values(?,?,?,?,?,?,decode('01','hex'),?,?)",seed.tenant(),other,lead.selector().id(),assignment.selector().id(),contact,seed.appointment(),ContractCanonicalJson.digest("Synthetic separate opportunity"),now.atOffset(ZoneOffset.UTC));
            tasks.complete(x,seed.tenant(),task,CurrentLeadReader.databaseBacked(protection).contactResult(x,seed.tenant(),contact).selector(),now);
            for(String code:List.of("QUOTE_PREPARE","QUOTE_READ"))grant(x,code);
            return null;
        });}
        var original=opportunity;var sameParty=canonical(confirmationFact);
        @SuppressWarnings("unchecked") var opponent=new LinkedHashMap<String,Object>((Map<String,Object>)((List<?>)sameParty.get("participants")).getFirst());opponent.put("role","OPPONENT");
        sameParty.put("participants",List.of(Map.of("role","CLIENT","newParty",Map.of("kind","ORGANIZATION","name","另一商机独立委托客户","distinctIdentityConfirmed",true)),opponent));sameParty.put("unknownOpponent",false);
        opportunity=new Subject("opportunity.opportunity",other,0L,null);
        try {
            var draft=save(sameParty);var confirmed=execute(command(true,draft.resultFact(),null,null));assertEquals(CommandOutcome.Status.SUCCEEDED,confirmed.status(),confirmed.rejectionCode());
            var quotes=R2QuoteServices.create(cipher);Map<String,Object> context;
            try(var c=database.apiConnection()){context=inTransaction(c,Capability.QUERY,x->quotes.context(x,actor(),other));}
            var payload=new LinkedHashMap<String,Object>();payload.put("opportunityId",other.toString());payload.put("expectedOpportunityRevision",0L);payload.put("responsibilityBasis",context.get("responsibilityBasis"));payload.put("customerConfirmation",context.get("customerConfirmation"));payload.put("expectedDraft",null);payload.put("expectedQuote",null);payload.put("expectedWorkflow",null);
            payload.put("values",Map.of("currency","CNY","scope","另一个商机的真实合成服务范围","lines",List.of(Map.of("description","服务费","amountMinor",10000L,"discount",false)),"paymentTerms","签约后支付","validUntil",Instant.now().plusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString()));
            try(var c=database.apiConnection()){assertEquals("opportunity.quote_revision",inTransaction(c,Capability.COMMAND,x->quotes.execute(x,"FORM_QUOTE",actor(),payload)).type());}
        } finally {opportunity=original;}
        return other;
    }

 @Test void independent_transfer_review_real_conflict_blocks_and_returns_for_correction()throws Exception{
  var initial=beginTransfer();submitTransfer(initial);otherOpportunityWithSameParty();var w=currentReview();var a=reviewerActor();
  try(var c=database.apiConnection()){assertThrows(TransferWorkflowService.Blocked.class,()->inTransaction(c,Capability.COMMAND,x->workflows().review(x,a,w,new TransferConflictReviewInput("CLEAR","尝试通过",true))));}
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->workflows().review(x,a,w,new TransferConflictReviewInput("BLOCKED","已核实准确主体在另一事项中处于相对方角色",true)));}
  assertEquals("1",scalar("select count(*) from conflict.conflict_review where tenant_id=? and review_type_code='PRE_TRANSFER' and resolution_code='BLOCKED'",seed.tenant()));
  assertEquals("1",scalar("select count(*) from responsibility.decision_record where tenant_id=? and decision_contract_code='R2_TRANSFER_CONFLICT_BLOCK_V1' and decision_code='BLOCKED'",seed.tenant()));
  assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='SUPPLEMENT_TRANSFER' and state='OPEN'",seed.tenant()));
 }

 @Test void independent_transfer_review_correction_requires_every_item_and_reenters_fresh_review()throws Exception{
  var initial=beginTransfer();var first=submitTransfer(initial);var review=currentReview();var a=reviewerActor();
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->workflows().review(x,a,review,new TransferConflictReviewInput("NEED_INFO","请补齐主体证明",true)));}
  var correction=new Subject("transfer.workflow",UUID.fromString(scalar("select workflow_id from transfer.workflow where tenant_id=? and stage_code='SUPPLEMENT'",seed.tenant())),0L,null);
  assertThrows(IllegalArgumentException.class,()->submitTransfer(correction));
  UUID item=UUID.fromString(scalar("select review_return_item_id from transfer.review_return_item where tenant_id=?",seed.tenant()));
  var proof=new TransferSubmissionInput.Material(material,bodySha);
  var corrected=new TransferSubmissionInput(proof,proof,"补齐后重新提交",true,List.of(new TransferSubmissionInput.Correction(item,"已补齐证明",proof)));
  Subject second;try(var c=database.apiConnection()){second=inTransaction(c,Capability.COMMAND,x->workflows().submit(x,actor(),correction,corrected));}
  assertNotEquals(first.id(),second.id());assertEquals(first.id().toString(),scalar("select previous_submission_id from transfer.submission where tenant_id=? and submission_id=?",seed.tenant(),second.id()));
  assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='SUPPLEMENT_TRANSFER' and state='DONE'",seed.tenant()));
  assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='REVIEW_TRANSFER' and state='OPEN'",seed.tenant()));
  try(var c=database.apiConnection()){assertThrows(TransferWorkflowService.Blocked.class,()->inTransaction(c,Capability.COMMAND,x->TransferConflictReviewRepository.databaseBacked((q,t,o)->true,ContractCanonicalJson::encode).inspect(x,seed.tenant(),first.id())));}
 }
}
