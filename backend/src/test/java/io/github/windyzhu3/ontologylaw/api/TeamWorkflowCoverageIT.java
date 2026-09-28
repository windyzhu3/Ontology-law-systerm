package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import io.github.windyzhu3.ontologylaw.transfer.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;

/** Read the actual responsibilities produced by the existing corrected sales chain. */
class TeamWorkflowCoverageIT extends R2TransferIntakeIT {
 @Test void completed_chain_with_intake_correction_and_classification_keeps_all_team_sources_readable()throws Exception {
  intake_return_correct_resubmit_review_then_accept_keeps_the_original_snapshot_chain();
  var classification=new Subject("transfer.workflow",UUID.fromString(scalar("select workflow_id from transfer.workflow where tenant_id=? and stage_code='CLASSIFY'",seed.tenant())),0L,null);
  var recipient=reviewerActor();
  try(var c=database.apiConnection()){
   inTransaction(c,Capability.COMMAND,x->{workflows().classify(x,recipient,classification,new TransferClassificationInput("GENERAL",reviewer,"核对本次分类及实际承接"));grant(x,"TEAM_TASK_READ");return null;});
  }
  var service=new R2TeamManagementReadService(new byte[32],protection,cipher,protectedBodies,AuditAppender.databaseBacked("TEAM_CHAIN_COVERAGE"));
  var completed=new TreeSet<String>();
  record Entry(UUID id,String type,String state){}
  List<Entry> entries=new ArrayList<>();
  try(var c=database.adminConnection();var p=c.prepareStatement("select task_occurrence_id,business_purpose_code,state from responsibility.task_occurrence where tenant_id=? order by task_occurrence_id")){
   p.setObject(1,seed.tenant());try(var rows=p.executeQuery()){while(rows.next())entries.add(new Entry(rows.getObject(1,UUID.class),rows.getString(2),rows.getString(3)));}
  }
  assertFalse(entries.isEmpty());
  for(var entry:entries){
   String view=switch(entry.state()){case "OPEN"->"tasks";case "WAITING"->"waiting";default->"history";};
   try(var c=database.apiConnection()){
    var detail=assertDoesNotThrow(()->service.detail(c,actor(),view,entry.id()),entry.type()+" "+entry.state());
    assertEquals(entry.id().toString(),detail.get("id"));assertFalse(((List<?>)detail.get("facts")).isEmpty());
    if(view.equals("history")){assertNull(detail.get("action"));assertNull(detail.get("taskId"));assertNull(detail.get("exceptionId"));}
    if(entry.state().equals("DONE"))completed.add(entry.type());
   }
  }
  assertTrue(completed.containsAll(Set.of("PREPARE_TRANSFER","REVIEW_TRANSFER","SUPPLEMENT_TRANSFER","ACCEPT_TRANSFER","CLASSIFY_MATTER")),completed.toString());
  java.nio.file.Files.writeString(java.nio.file.Path.of("target/r25-team-chain-covered-types.txt"),String.join("\n",completed)+"\n");
 }
}
