package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.responsibility.TeamResponsibilityReader;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
class R2TeamResponsibilityReaderIT extends R2PaymentWorkflowIT {
 @Test void scans_are_bounded_tenant_isolated_and_do_not_reopen_completed_tasks()throws Exception{
  var initial=beginPayment();var reader=TeamResponsibilityReader.databaseBacked();
  try(var c=database.apiConnection()){
   var all=inTransaction(c,Capability.QUERY,x->reader.scan(x,seed.tenant(),TeamResponsibilityReader.View.TASKS,null,100));
   assertFalse(all.isEmpty());assertTrue(all.stream().allMatch(t->Set.of("OPEN").contains(t.state())));
   var one=inTransaction(c,Capability.QUERY,x->reader.scan(x,seed.tenant(),TeamResponsibilityReader.View.TASKS,null,1));assertEquals(1,one.size());
   var next=inTransaction(c,Capability.QUERY,x->reader.scan(x,seed.tenant(),TeamResponsibilityReader.View.TASKS,one.getFirst().selector().id(),100));assertFalse(next.stream().anyMatch(t->t.selector().equals(one.getFirst().selector())));
   assertTrue(inTransaction(c,Capability.QUERY,x->reader.scan(x,UUID.randomUUID(),TeamResponsibilityReader.View.TASKS,null,100)).isEmpty());
   assertThrows(IllegalArgumentException.class,()->reader.scan(c,seed.tenant(),TeamResponsibilityReader.View.TASKS,null,0));assertThrows(IllegalArgumentException.class,()->reader.scan(c,seed.tenant(),TeamResponsibilityReader.View.TASKS,null,101));
   var again=inTransaction(c,Capability.QUERY,x->reader.scan(x,seed.tenant(),TeamResponsibilityReader.View.TASKS,null,100));assertEquals(all,again,"Reads preserve responsibility and original deadlines");
  }
  confirm(initial,receipt(12345,"M02-HISTORY"));
  try(var c=database.apiConnection()){
   var history=inTransaction(c,Capability.QUERY,x->reader.scan(x,seed.tenant(),TeamResponsibilityReader.View.HISTORY,null,100));assertFalse(history.isEmpty());assertTrue(history.stream().allMatch(t->Set.of("DONE","CANCELLED").contains(t.state())));
  }
 }
}
