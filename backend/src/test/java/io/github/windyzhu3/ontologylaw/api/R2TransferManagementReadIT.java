package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.transfer.*;import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import java.util.*;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
class R2TransferManagementReadIT extends R2TransferIntakeIT {
 private R2ManagementLedgerReadService management(){return new R2ManagementLedgerReadService(new byte[32],protection,AuditAppender.databaseBacked("M01_TRANSFER_TEST"));}
 private UUID request()throws Exception{return UUID.fromString(scalar("select transfer_request_id from transfer.transfer_request where tenant_id=?",seed.tenant()));}
 @Test void management_distinguishes_unaccepted_unclassified_and_classified_case()throws Exception{
  var intake=readyForIntake();var id=request();var reviewerContext=reviewerActor();
  try(var c=database.apiConnection()){
   inTransaction(c,Capability.COMMAND,x->{grant(x,"TRANSFER_LEDGER_READ");return null;});
   var before=management().detail(c,actor(),"transfer",id);assertTrue(before.get("facts").toString().contains("尚未生成案件"));assertEquals(false,before.get("canHandle"));
   inTransaction(c,Capability.COMMAND,x->workflows().intake(x,reviewerContext,intake,new TransferIntakeInput("ACCEPT",null,"完整材料已核对",true)));
   var accepted=management().detail(c,actor(),"transfer",id);assertTrue(accepted.get("facts").toString().contains("待确认分类"));assertFalse(accepted.get("facts").toString().contains("尚未生成案件"));
   var state=inTransaction(c,Capability.QUERY,x->TransferWorkflowReader.databaseBacked().current(x,seed.tenant(),id));
   inTransaction(c,Capability.COMMAND,x->workflows().classify(x,reviewerContext,state.workflow(),new TransferClassificationInput("GENERAL",reviewer,"确认分类")));
   var classified=management().detail(c,actor(),"transfer",id);assertTrue(classified.get("facts").toString().contains("综法业务"));assertEquals(false,classified.get("canHandle"));assertEquals(1,((List<?>)management().list(c,actor(),"transfer",20,null,"","COMPLETE").get("items")).size());
  }
 }
 @Test void management_intake_return_discloses_only_authorized_missing_items()throws Exception{
  var intake=readyForIntake();var id=request();var reviewerContext=reviewerActor();
  try(var c=database.apiConnection()){
   inTransaction(c,Capability.COMMAND,x->{grant(x,"TRANSFER_LEDGER_READ");return workflows().intake(x,reviewerContext,intake,new TransferIntakeInput("RETURN","CLIENT_IDENTITY","证明缺页",false));});
   var detail=management().detail(c,actor(),"transfer",id);assertTrue(detail.get("facts").toString().contains("委托主体证明"));assertTrue(detail.get("facts").toString().contains("尚未生成案件"));
  }
 }
}
