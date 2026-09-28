package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.payment.PaymentLedgerReader;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;

class R2PaymentLedgerReaderIT extends R2PaymentWorkflowIT {
 @Test void financial_detail_distinguishes_request_receipts_from_contract_totals()throws Exception {
  receiptRequired=true;var first=beginPayment();confirm(first,receipt(4000,"M01-DETAIL-PART"));var reader=PaymentLedgerReader.databaseBacked();
  try(var c=database.apiConnection()){
   var row=inTransaction(c,Capability.QUERY,x->reader.scan(x,seed.tenant(),null,null,1)).getFirst();
   var detail=inTransaction(c,Capability.QUERY,x->reader.detail(x,seed.tenant(),row.request().id()));
   assertEquals(row,detail.row());assertTrue(detail.prepay());assertEquals(10000L,detail.requiredMinor());assertEquals(1,detail.receipts().size());assertEquals(4000L,detail.receipts().getFirst().amountMinor());assertEquals("CNY",detail.receipts().getFirst().currency());assertTrue(detail.receipts().getFirst().thisRequest());assertFalse(detail.facts().isEmpty());
   assertNull(inTransaction(c,Capability.QUERY,x->reader.detail(x,UUID.randomUUID(),row.request().id())));
  }
 }
 @Test void ledger_tracks_current_finance_occurrence_without_reopening_completed_work()throws Exception {
  var initial=beginPayment();var reader=PaymentLedgerReader.databaseBacked();
  PaymentLedgerReader.Row before;
  try(var c=database.apiConnection()){
   before=inTransaction(c,Capability.QUERY,x->reader.scan(x,seed.tenant(),null,null,10)).getFirst();
   assertEquals(initial,before.workflow());assertEquals("CHECK_RECEIPT",before.stage());
   assertEquals(opportunity.id(),before.opportunityId());assertNotNull(before.taskId());
   assertTrue(inTransaction(c,Capability.QUERY,x->reader.scan(x,UUID.randomUUID(),null,null,10)).isEmpty());
   assertTrue(inTransaction(c,Capability.QUERY,x->reader.scan(x,seed.tenant(),before.request().id(),null,10)).isEmpty());
   assertThrows(IllegalArgumentException.class,()->reader.scan(c,seed.tenant(),null,null,101));
   assertThrows(IllegalArgumentException.class,()->reader.scan(c,seed.tenant(),null,"unknown",10));
  }
  confirm(initial,receipt(12345,"M01-ONE-RECEIPT"));
  try(var c=database.apiConnection()){
   var rows=inTransaction(c,Capability.QUERY,x->reader.scan(x,seed.tenant(),null,"COMPLETE",1));
   assertEquals(1,rows.size());var after=rows.getFirst();assertEquals(before.request(),after.request());
   assertNotEquals(before.workflow(),after.workflow());assertEquals("COMPLETE",after.stage());assertNull(after.taskId());
   assertEquals(before.dueAt(),after.dueAt());assertEquals(before.contractRevisionId(),after.contractRevisionId());
   assertTrue(inTransaction(c,Capability.QUERY,x->reader.scan(x,seed.tenant(),null,"CHECK_RECEIPT",10)).isEmpty());
  }
  assertEquals("1",scalar("select count(*) from contract.payment_confirmation where tenant_id=?",seed.tenant()));
  var closed=latestPayment();
  try(var c=database.apiConnection()){
   inTransaction(c,Capability.COMMAND,x->payments().request(x,actor(),closed,material,bodySha,"后续凭证独立核对"));
   var first=inTransaction(c,Capability.QUERY,x->reader.scan(x,seed.tenant(),null,null,1));
   var second=inTransaction(c,Capability.QUERY,x->reader.scan(x,seed.tenant(),first.getFirst().request().id(),null,1));
   assertEquals(1,first.size());assertEquals(1,second.size());assertNotEquals(first.getFirst().request(),second.getFirst().request());
   assertEquals("COMPLETE",first.getFirst().stage());assertEquals("CHECK_RECEIPT",second.getFirst().stage());
  }
 }
 @Test void partial_payment_keeps_the_same_request_task_and_original_deadline()throws Exception {
  receiptRequired=true;var initial=beginPayment();var reader=PaymentLedgerReader.databaseBacked();PaymentLedgerReader.Row before;
  try(var c=database.apiConnection()){before=inTransaction(c,Capability.QUERY,x->reader.scan(x,seed.tenant(),null,null,1)).getFirst();}
  confirm(initial,receipt(4000,"M01-PARTIAL"));
  try(var c=database.apiConnection()){
   var current=inTransaction(c,Capability.QUERY,x->reader.scan(x,seed.tenant(),null,null,1)).getFirst();
   assertEquals("CHECK_RECEIPT",current.stage());assertEquals(before.request(),current.request());
   assertEquals(before.taskId(),current.taskId());assertEquals(before.dueAt(),current.dueAt());assertNotEquals(initial,current.workflow());
  }
 }
}
