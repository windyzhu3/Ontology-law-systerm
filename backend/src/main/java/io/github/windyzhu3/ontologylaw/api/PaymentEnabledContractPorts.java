package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.contract.ContractWorkflowService.*;
import io.github.windyzhu3.ontologylaw.contract.ContractWorkflowService;
import io.github.windyzhu3.ontologylaw.payment.PaymentWorkflowService;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.opportunity.OpportunityProgressProtection;
import io.github.windyzhu3.ontologylaw.evidence.MaterialObjectStore;
import java.sql.*;import java.util.*;
/** API composition keeps finance ownership independent from sales workflow disposition. */
final class PaymentEnabledContractPorts extends ContractWorkflowPorts {
 private final PaymentWorkflowService payments;
 PaymentEnabledContractPorts(OpportunityProgressProtection protection,MaterialObjectStore objects,PaymentWorkflowService payments){this(protection,objects,payments,new BusinessResponsibilityRouting(List.of()));}
 PaymentEnabledContractPorts(OpportunityProgressProtection protection,MaterialObjectStore objects,PaymentWorkflowService payments,BusinessResponsibilityRouting routing){super(protection,objects,routing);this.payments=Objects.requireNonNull(payments);}
 public RecoveryCandidate paymentRecovery(Connection c,Actor actor,Subject opportunity,Responsibility owner)throws SQLException{
  try{var candidate=payments.recovery(c,actor,opportunity);return candidate==null?null:new RecoveryCandidate(opportunity,owner.basis(),candidate.source(),candidate.workflow(),owner.owner());}catch(PaymentWorkflowService.Blocked denied){throw new ContractWorkflowService.Blocked(denied.code());}
 }
 private static Subject selector(Object value,String type){if(!(value instanceof Map<?,?> m)||!m.keySet().equals(Set.of("id","revision"))||!(m.get("revision") instanceof Number revision)||revision.longValue()!=0)throw new ContractWorkflowService.Blocked("STALE_SUBJECT");return new Subject(type,UUID.fromString(m.get("id").toString()),0L,null);}
 public Subject reconcilePayment(Connection c,Actor actor,Subject opportunity,Map<String,Object> payload)throws SQLException{
  String kind=String.valueOf(payload.get("sourceKind"));String type=switch(kind){case "PAYMENT_HANDOFF"->"contract.signature_archive";case "PAYMENT_RECOVERY"->"contract.payment_request";default->throw new ContractWorkflowService.Blocked("STALE_SUBJECT");};
  var source=selector(payload.get("source"),type);var workflow=payload.get("expectedWorkflow")==null?null:selector(payload.get("expectedWorkflow"),"contract.payment_workflow");
  if(kind.equals("PAYMENT_HANDOFF")&&workflow!=null||kind.equals("PAYMENT_RECOVERY")&&workflow==null)throw new ContractWorkflowService.Blocked("STALE_SUBJECT");
  try{return payments.reconcile(c,actor,opportunity,new PaymentWorkflowService.Recovery(source,workflow));}catch(PaymentWorkflowService.Blocked denied){throw new ContractWorkflowService.Blocked(denied.code());}
 }
 public List<Map<String,Object>> payments(Connection c,Actor actor,Subject opportunity)throws SQLException{try{return payments.context(c,actor,opportunity);}catch(PaymentWorkflowService.Blocked denied){throw new ContractWorkflowService.Blocked(denied.code());}}
 public Subject executePayment(Connection c,String action,Actor actor,Subject opportunity,UUID contract,UUID version,Map<String,Object> values)throws SQLException{
  var required=new LinkedHashSet<String>(List.of("expectedPaymentWorkflow","explanation"));var allowed=new LinkedHashSet<String>(required);allowed.add("expectedTermination");
  boolean confirm=action.equals("RECORD_CONTRACT_RECEIPT_REVIEW")&&"CONFIRM".equals(values.get("decision"));
  if(action.equals("RECORD_CONTRACT_RECEIPT_REVIEW")){required.add("decision");allowed.add("decision");if(!Set.of("CONFIRM","RETURN").contains(values.getOrDefault("decision","")))throw new ContractWorkflowService.Blocked("VALIDATION_FAILED");}
  if(confirm||!action.equals("RECORD_CONTRACT_RECEIPT_REVIEW")){required.addAll(List.of("materialVersionId","materialSha256"));allowed.addAll(required);}
  if(confirm){required.addAll(List.of("transactionReference","amountMinor","currency","receivedAt","attributionChecked"));allowed.addAll(required);}
  if(!values.keySet().containsAll(required)||!allowed.containsAll(values.keySet()))throw new ContractWorkflowService.Blocked("VALIDATION_FAILED");
  var workflow=selector(values.get("expectedPaymentWorkflow"),"contract.payment_workflow");
  try(var statement=c.prepareStatement("select 1 from contract.payment_workflow where tenant_id=? and opportunity_id=? and payment_workflow_id=?")){statement.setObject(1,actor.tenantId());statement.setObject(2,opportunity.id());statement.setObject(3,workflow.id());try(var row=statement.executeQuery()){if(!row.next())throw new ContractWorkflowService.Blocked("STALE_SUBJECT");}}
  try{
   String explanation=(String)values.get("explanation");
   if(action.equals("RECORD_CONTRACT_RECEIPT_REVIEW")&&!confirm)return payments.returnForCorrection(c,actor,workflow,explanation);
   UUID material=UUID.fromString((String)values.get("materialVersionId"));String sha=(String)values.get("materialSha256");
   if(action.equals("REQUEST_CONTRACT_RECEIPT_REVIEW"))return payments.request(c,actor,workflow,material,sha,explanation);
   if(action.equals("SUPPLEMENT_CONTRACT_RECEIPT"))return payments.supplement(c,actor,workflow,material,sha,explanation);
   Object amount=values.get("amountMinor");if(!(amount instanceof Integer||amount instanceof Long)||!Boolean.TRUE.equals(values.get("attributionChecked")))throw new IllegalArgumentException();
   return payments.confirm(c,actor,workflow,new io.github.windyzhu3.ontologylaw.payment.PaymentReceiptInput(contract,version,material,sha,(String)values.get("transactionReference"),((Number)amount).longValue(),(String)values.get("currency"),java.time.Instant.parse((String)values.get("receivedAt")),explanation,true));
  }catch(PaymentWorkflowService.Blocked denied){throw new ContractWorkflowService.Blocked(denied.code());}
 }
}
