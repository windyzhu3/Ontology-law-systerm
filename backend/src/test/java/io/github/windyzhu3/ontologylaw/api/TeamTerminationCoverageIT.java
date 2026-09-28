package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import io.github.windyzhu3.ontologylaw.execution.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual termination command/auth/audit composition against existing synthetic signature fixtures. */
class TeamTerminationCoverageIT extends R2ContractTerminationRuntimeIT {
 @Test void completed_termination_review_keeps_actual_independent_reviewer_and_time()throws Exception {
  start();arrange();submit();var reviewer=supervisor();
  for(var who:List.of(actor(),reviewer))authorize(who,"CONTRACT_READ");authorize(actor(),"OPPORTUNITY_CLOSE");authorize(actor(),"TEAM_TASK_READ");authorize(reviewer,"CONTRACT_TERMINATION_REVIEW");
  var request=new CommandEnvelope(CommandEnvelope.Type.REQUEST_CONTRACT_TERMINATION_REVIEW,UUID.randomUUID(),UUID.randomUUID(),actor(),dispositionPayload(Map.of()));
  assertEquals(CommandOutcome.Status.SUCCEEDED,run(request).status());
  var current=(Map<?,?>)context().get("termination");UUID task=UUID.fromString((String)((Map<?,?>)current.get("task")).get("id"));
  var decision=new CommandEnvelope(CommandEnvelope.Type.RECORD_CONTRACT_TERMINATION_REVIEW,UUID.randomUUID(),UUID.randomUUID(),reviewer,dispositionPayload(Map.of("decision","STOP")));
  var stopped=run(decision);assertEquals(CommandOutcome.Status.SUCCEEDED,stopped.status());assertEquals(stopped,run(decision));
  try(var c=database.apiConnection()){
   var detail=new R2TeamManagementReadService(new byte[32],protection,cipher,protectedBodies,AuditAppender.databaseBacked("TEAM_TERMINATION_COVERAGE")).detail(c,actor(),"history",task);
   var fields=(List<?>)detail.get("facts");assertTrue(fields.stream().anyMatch(v->((List<?>)v).get(0).equals("当时确认人")&&((List<?>)v).get(1).toString().contains("合成主管")));
   assertTrue(fields.stream().anyMatch(v->((List<?>)v).get(0).equals("处理时间")&&!((List<?>)v).get(1).toString().contains("未取得")));assertNull(detail.get("action"));assertNull(detail.get("taskId"));
  }
 }
}
