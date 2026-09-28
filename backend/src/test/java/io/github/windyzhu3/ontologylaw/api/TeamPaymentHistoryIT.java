package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;

class TeamPaymentHistoryIT extends R2PaymentWorkflowIT {
 @Test void finance_history_keeps_each_original_return_supplement_and_confirmation_reason()throws Exception {
  var start=beginPayment();Subject returned,supplemented;
  try(var c=database.apiConnection()){
   returned=inTransaction(c,Capability.COMMAND,x->payments().returnForCorrection(x,actor(),start,"请补充本笔流水归属"));
   var correction=latestPayment();
   supplemented=inTransaction(c,Capability.COMMAND,x->payments().supplement(x,actor(),correction,material,bodySha,"已补充本笔账户流水"));
   inTransaction(c,Capability.COMMAND,x->{grant(x,"TEAM_TASK_READ");return null;});
  }
  var confirmed=confirm(latestPayment(),receipt(100,"TEAM-ORIGINAL-RECEIPT"));
  var service=new R2TeamManagementReadService(new byte[32],protection,cipher,protectedBodies,AuditAppender.databaseBacked("TEAM_PAYMENT_HISTORY"));
  for(var item:List.of(Map.entry(returned,"请补充本笔流水归属"),Map.entry(supplemented,"已补充本笔账户流水"),Map.entry(confirmed,"已人工核对到账归属"))){
   var task=UUID.fromString(scalar("select task_occurrence_id from responsibility.task_occurrence where tenant_id=? and completion_fact_id=?",seed.tenant(),item.getKey().id()));
   try(var c=database.apiConnection()){
    var detail=service.detail(c,actor(),"history",task);
    assertNull(detail.get("action"));assertNull(detail.get("taskId"));
    assertTrue(((List<?>)detail.get("facts")).contains(List.of("原因或说明",item.getValue())),"Each original review must retain its own reason: "+item.getValue());
    assertFalse(detail.toString().contains("TEAM-ORIGINAL-RECEIPT"),"Do not release the transaction reference with the reason");
   }
  }
  var firstTask=UUID.fromString(scalar("select task_occurrence_id from responsibility.task_occurrence where tenant_id=? and completion_fact_id=?",seed.tenant(),returned.id()));
  try(var c=database.apiConnection()){
   inTransaction(c,Capability.QUERY,x->{
    var reader=io.github.windyzhu3.ontologylaw.payment.PaymentLedgerReader.databaseBacked();
    assertNull(reader.review(x,UUID.randomUUID(),returned));
    assertNull(reader.review(x,seed.tenant(),new Subject(returned.type(),returned.id(),returned.revision()+1,null)));
    assertNull(reader.review(x,seed.tenant(),new Subject("contract.payment_request",returned.id(),returned.revision(),null)));
    return null;
   });
   var failing=new R2TeamManagementReadService(new byte[32],protection,cipher,protectedBodies,(tx,entry)->{});
   assertThrows(java.sql.SQLException.class,()->failing.detail(c,actor(),"history",firstTask));
   deny(returned,"TEAM_TASK_READ");
   var bomb=new io.github.windyzhu3.ontologylaw.contract.ContractProtection(){
    public byte[] seal(UUID t,UUID o,UUID f,Kind k,String text){throw new AssertionError();}
    public String open(UUID t,UUID o,UUID f,Kind k,byte[] bytes){throw new AssertionError("Denied payment history decrypted");}
   };
   var denied=new R2TeamManagementReadService(new byte[32],protection,cipher,bomb,AuditAppender.databaseBacked("TEAM_PAYMENT_DENY"));
   assertEquals(403,assertThrows(io.github.windyzhu3.ontologylaw.execution.R1ServiceReadRuntime.Failure.class,()->denied.detail(c,actor(),"history",firstTask)).status());
  }
 }
}
