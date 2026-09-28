package io.github.windyzhu3.ontologylaw.audit;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class OpportunityClosureReceiptMetadataTest {
    private Map<String,Object> scope(){var s=new LinkedHashMap<String,Object>();s.put("profile","R2_CLOSE_OPPORTUNITY_SCOPE_V1");s.put("tenantId",UUID.randomUUID().toString());s.put("commandType","CLOSE_OPPORTUNITY");s.put("principalId",UUID.randomUUID().toString());s.put("appointmentId",UUID.randomUUID().toString());var opportunity=Map.of("type","opportunity.opportunity","id",UUID.randomUUID().toString(),"revision",0L);s.put("opportunity",opportunity);s.put("basis",opportunity);s.put("task",null);s.put("wait",null);return s;}
    private ReceiptRecoveryMetadata parse(Map<String,Object> scope){return new ReceiptRecoveryMetadata("CLOSE_OPPORTUNITY",Map.of("profile","R1_COMMAND_RECEIPT_RECOVERY_V1","scope",scope,"binding",Map.of("kind","OPPORTUNITY_CLOSURE")));}
    @Test void exact_no_task_scope_retains_original_actor_and_selectors_without_summary(){var scope=scope();var value=parse(scope);assertEquals(scope,value.opportunityClosureScope());assertEquals("opportunity.opportunity",value.lead().type());assertNull(value.taskId());assertNull(value.ownerExceptionScope());assertEquals("ReceiptRecoveryMetadata[restricted]",value.toString());}
    @Test void partial_forged_or_unbounded_scope_is_rejected(){for(String key:List.of("appointmentId","principalId","basis","task","wait")){var scope=scope();scope.remove(key);assertThrows(IllegalArgumentException.class,()->parse(scope));}var extra=scope();extra.put("summary","Do not persist sensitive prose here");assertThrows(IllegalArgumentException.class,()->parse(extra));var wait=scope();wait.put("wait",Map.of("type","responsibility.wait_receipt","id",UUID.randomUUID().toString(),"hash","A".repeat(43)));assertThrows(IllegalArgumentException.class,()->parse(wait));var wrong=scope();wrong.put("basis",Map.of("type","lead.lead","id",UUID.randomUUID().toString(),"revision",0L));assertThrows(IllegalArgumentException.class,()->parse(wrong));}
}
