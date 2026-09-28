package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import io.github.windyzhu3.ontologylaw.execution.R1ServiceReadRuntime;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
class R2ManagementLedgerReadIT extends R2PaymentWorkflowIT {
 @Test void financial_detail_reports_partial_receipt_without_granting_business_actions()throws Exception{
  receiptRequired=true;var initial=beginPayment();confirm(initial,receipt(4000,"M01-READ-PARTIAL"));
  try(var c=database.apiConnection()){
   inTransaction(c,Capability.COMMAND,x->{grant(x,"PAYMENT_LEDGER_READ");return null;});
   var page=management().list(c,actor(),"payments",20,null,"",null);var row=(Map<?,?>)((List<?>)page.get("items")).getFirst();
   var detail=management().detail(c,actor(),"payments",UUID.fromString((String)row.get("id")));
   assertEquals(false,detail.get("canHandle"));assertNull(detail.get("taskId"));
   assertTrue(detail.get("facts").toString().contains("40.00"));assertTrue(detail.get("facts").toString().contains("60.00"));assertFalse(detail.containsKey("document"));
   assertThrows(R1ServiceReadRuntime.Failure.class,()->management().detail(c,actor(),"payments",UUID.randomUUID()));
  }
 }
 private R2ManagementLedgerReadService management(){return new R2ManagementLedgerReadService(new byte[32],protection,AuditAppender.databaseBacked("M01_READ_TEST"));}
 @Test void independent_financial_read_requires_its_own_grant_and_audits_without_contract_body()throws Exception{
  beginPayment();
  try(var c=database.apiConnection()){assertThrows(R1ServiceReadRuntime.Failure.class,()->management().list(c,actor(),"payments",20,null,"",null));inTransaction(c,Capability.COMMAND,x->{grant(x,"PAYMENT_LEDGER_READ");return null;});}
  mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_code='CONTRACT_READ'",seed.tenant());
  try(var c=database.apiConnection()){
   var page=management().list(c,actor(),"payments",20,null,"",null);var rows=(List<?>)page.get("items");assertEquals(1,rows.size());var row=(Map<?,?>)rows.getFirst();
   assertEquals("待核对本笔收款",row.get("stateLabel"));assertFalse(row.containsKey("document"));assertFalse(row.containsKey("amount"));assertFalse(row.containsKey("canHandle"));
   assertEquals(0,((List<?>)management().list(c,actor(),"payments",20,null,"不会匹配的客户",null).get("items")).size());
  }
  try(var c=database.adminConnection();var p=c.prepareStatement("select count(*) from audit.audit_entry where tenant_id=? and action_code='READ_BUSINESS_MANAGEMENT'")){p.setObject(1,seed.tenant());try(var r=p.executeQuery()){r.next();assertTrue(r.getLong(1)>0);}}
  try(var c=database.apiConnection()){var failing=new R2ManagementLedgerReadService(new byte[32],protection,(tx,entry)->{});assertThrows(java.sql.SQLException.class,()->failing.list(c,actor(),"payments",20,null,"",null));}
  mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_code='PAYMENT_LEDGER_READ'",seed.tenant());
  try(var c=database.apiConnection()){assertThrows(R1ServiceReadRuntime.Failure.class,()->management().list(c,actor(),"payments",20,null,"",null));}
 }
}


