package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.contract.ContractTeamExceptionReader;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import io.github.windyzhu3.ontologylaw.execution.R1ServiceReadRuntime;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;import java.util.*;
class TeamPaymentExceptionIT extends R2PaymentWorkflowIT {
 @Test void missing_finance_owner_is_visible_without_task_and_hides_after_recovery()throws Exception{
  ownerAvailable=false;var workflow=beginPayment();var service=new R2TeamManagementReadService(new byte[32],protection,AuditAppender.databaseBacked("TEAM_PAYMENT_EXCEPTION"));
  try(var c=database.apiConnection()){
   inTransaction(c,Capability.COMMAND,x->{grant(x,"TEAM_TASK_READ");return null;});
   var rows=(List<?>)service.list(c,actor(),"exceptions",20,null,"",null).get("items");assertEquals(1,rows.size());assertEquals(workflow.id().toString(),((Map<?,?>)rows.getFirst()).get("id"));
   var detail=service.detail(c,actor(),"exceptions",workflow.id());assertNull(detail.get("action"));assertNull(detail.get("taskId"));assertTrue(detail.toString().contains("收款核对责任异常"));
   assertTrue(((List<?>)service.list(c,actor(),"exceptions",20,null,"不存在的客户",null).get("items")).isEmpty());
   inTransaction(c,Capability.QUERY,x->{assertNull(ContractTeamExceptionReader.databaseBacked().current(x,UUID.randomUUID(),workflow.id()));assertThrows(IllegalArgumentException.class,()->ContractTeamExceptionReader.databaseBacked().scan(x,seed.tenant(),null,101));return null;});
   deny(workflow,"TEAM_TASK_READ");assertTrue(((List<?>)service.list(c,actor(),"exceptions",20,null,"",null).get("items")).isEmpty());assertThrows(R1ServiceReadRuntime.Failure.class,()->service.detail(c,actor(),"exceptions",workflow.id()));
   ownerAvailable=true;var worker=service("CONTRACT_TASK_RECOVER");inTransaction(c,Capability.COMMAND,x->payments().recover(x,worker,workflow));
   inTransaction(c,Capability.QUERY,x->{assertNull(ContractTeamExceptionReader.databaseBacked().current(x,seed.tenant(),workflow.id()));return null;});
  }
 }
}
