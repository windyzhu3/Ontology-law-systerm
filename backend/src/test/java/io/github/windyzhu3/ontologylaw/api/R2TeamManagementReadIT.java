package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import io.github.windyzhu3.ontologylaw.execution.R1ServiceReadRuntime;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
class R2TeamManagementReadIT extends R2PaymentWorkflowIT {
 @Test void team_detail_keeps_current_task_separate_from_readonly_history_and_other_owners()throws Exception{
  beginPayment();var service=new R2TeamManagementReadService(new byte[32],protection,cipher,protectedBodies,AuditAppender.databaseBacked("M02_DETAIL_TEST"));
  try(var c=database.apiConnection()){
   inTransaction(c,Capability.COMMAND,x->{grant(x,"TEAM_TASK_READ");return null;});
   var tasks=(List<?>)service.list(c,actor(),"tasks",20,null,"",null).get("items");assertFalse(tasks.isEmpty());
   for(var value:tasks){var row=(Map<?,?>)value;UUID id=UUID.fromString((String)row.get("id"));var detail=service.detail(c,actor(),"tasks",id);assertEquals(row.get("id"),detail.get("id"));assertFalse(((List<?>)detail.get("facts")).isEmpty());
    var task=inTransaction(c,Capability.QUERY,x->io.github.windyzhu3.ontologylaw.responsibility.CurrentTaskReader.databaseBacked().read(x,seed.tenant(),id));
    if(!task.owner().equals(actor().appointmentId())){assertNull(detail.get("action"));assertNull(detail.get("taskId"));}
    assertThrows(R1ServiceReadRuntime.Failure.class,()->service.detail(c,actor(),"history",id));
   }
   var history=(List<?>)service.list(c,actor(),"history",100,null,"",null).get("items");assertFalse(history.isEmpty());
   for(var value:history){var row=(Map<?,?>)value;var detail=service.detail(c,actor(),"history",UUID.fromString((String)row.get("id")));assertNull(detail.get("action"));assertNull(detail.get("taskId"));assertNull(detail.get("exceptionId"));assertFalse(((List<?>)detail.get("history")).isEmpty());}
   var failing=new R2TeamManagementReadService(new byte[32],protection,cipher,protectedBodies,(tx,entry)->{});assertThrows(java.sql.SQLException.class,()->failing.detail(c,actor(),"tasks",UUID.fromString((String)((Map<?,?>)tasks.getFirst()).get("id"))));
  }
 }
 @Test void team_read_has_independent_permission_and_disclosure_audit()throws Exception{
  beginPayment();var service=new R2TeamManagementReadService(new byte[32],protection,cipher,protectedBodies,AuditAppender.databaseBacked("M02_READ_TEST"));
  try(var c=database.apiConnection()){
   assertThrows(R1ServiceReadRuntime.Failure.class,()->service.list(c,actor(),"tasks",20,null,"",null));
   inTransaction(c,Capability.COMMAND,x->{grant(x,"TEAM_TASK_READ");return null;});
   var page=service.list(c,actor(),"tasks",20,null,"",null);var rows=(List<?>)page.get("items");assertFalse(rows.isEmpty());assertTrue(rows.size()<=20);var row=(Map<?,?>)rows.getFirst();assertTrue(row.containsKey("purposeLabel"));assertFalse(row.containsKey("document"));assertFalse(row.containsKey("canHandle"));
   assertTrue(((List<?>)service.list(c,actor(),"tasks",20,null,"绝不会匹配的合成检索",null).get("items")).isEmpty());
   assertThrows(R1ServiceReadRuntime.Failure.class,()->service.list(c,actor(),"tasks",101,null,"",null));
   var failing=new R2TeamManagementReadService(new byte[32],protection,cipher,protectedBodies,(tx,entry)->{});assertThrows(java.sql.SQLException.class,()->failing.list(c,actor(),"tasks",20,null,"",null));
  }
  assertNotEquals("0",scalar("select count(*) from audit.audit_entry_classified_v where tenant_id=? and action_code='READ_BUSINESS_MANAGEMENT'",seed.tenant()));
  mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_code='TEAM_TASK_READ'",seed.tenant());
  try(var c=database.apiConnection()){assertThrows(R1ServiceReadRuntime.Failure.class,()->service.list(c,actor(),"tasks",20,null,"",null));}
 }
}
