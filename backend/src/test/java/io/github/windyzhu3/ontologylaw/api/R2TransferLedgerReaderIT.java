package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.transfer.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;

class R2TransferLedgerReaderIT extends R2TransferIntakeIT {
 @Test void bounded_scan_excludes_predecessors_and_other_tenants()throws Exception {
  var initial=beginTransfer();var reader=TransferLedgerReader.databaseBacked();TransferLedgerReader.Row before;
  try(var c=database.apiConnection()){
   before=inTransaction(c,Capability.QUERY,x->reader.scan(x,seed.tenant(),null,null,1)).getFirst();
   assertEquals(initial,before.workflow());assertEquals("PREPARE",before.stage());
   assertTrue(inTransaction(c,Capability.QUERY,x->reader.scan(x,UUID.randomUUID(),null,null,1)).isEmpty());
   assertTrue(inTransaction(c,Capability.QUERY,x->reader.scan(x,seed.tenant(),before.request().id(),null,1)).isEmpty());
   assertThrows(IllegalArgumentException.class,()->reader.scan(c,seed.tenant(),null,null,101));
  }
  submitTransfer(initial);
  try(var c=database.apiConnection()){
   var rows=inTransaction(c,Capability.QUERY,x->reader.scan(x,seed.tenant(),null,"REVIEW_TRANSFER",10));assertEquals(1,rows.size());
   var next=rows.getFirst();assertEquals(before.request(),next.request());assertNotEquals(before.workflow(),next.workflow());
   assertEquals(before.dueAt(),next.dueAt());assertTrue(inTransaction(c,Capability.QUERY,x->reader.scan(x,seed.tenant(),null,"PREPARE",10)).isEmpty());
  }
 }
}
