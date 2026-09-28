package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;
class TeamQuoteExceptionIT extends R2QuoteWorkflowIT {
 @Test void repaired_quote_authority_exception_returns_only_its_qualified_original_task()throws Exception {
  setup(true,true);confirmed();quoteCommand("FORM_QUOTE",commercial());
  var workflow=(Map<?,?>)context().get("workflow");assertEquals("OWNER_EXCEPTION",workflow.get("stage"));
  UUID id=UUID.fromString((String)((Map<?,?>)workflow.get("selector")).get("id"));
  UUID task=UUID.fromString((String)((Map<?,?>)workflow.get("task")).get("id"));
  policy("REQUIRE_APPROVAL",List.of(seed.appointment()));
  try(var c=database.apiConnection()){
   inTransaction(c,Capability.COMMAND,x->{grant(x,"TEAM_TASK_READ");return null;});
   var service=new R2TeamManagementReadService(new byte[32],protection,cipher,AuditAppender.databaseBacked("TEAM_QUOTE_EXCEPTION"));
   var detail=service.detail(c,seed.request().actor(),"exceptions",id);
   assertEquals(Map.of("kind","task","label","前往办理"),detail.get("action"));assertEquals(task.toString(),detail.get("taskId"));assertNull(detail.get("exceptionId"));
   mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_code='QUOTE_PREPARE'",seed.tenant());
   var readonly=service.detail(c,seed.request().actor(),"exceptions",id);assertNull(readonly.get("action"));assertNull(readonly.get("taskId"));
   assertEquals("OPEN",inTransaction(c,Capability.QUERY,x->CurrentTaskReader.databaseBacked().read(x,seed.tenant(),task).state()));
  }
 }
}
