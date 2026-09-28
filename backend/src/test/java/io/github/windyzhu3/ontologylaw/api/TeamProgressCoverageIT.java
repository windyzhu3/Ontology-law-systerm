package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.lead.ContactFlowFixture;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import org.junit.jupiter.api.Test;
import java.time.ZoneId;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;

/** Real immutable progress and distinct waiting successor through Owner composition. */
class TeamProgressCoverageIT extends ContactFlowFixture {
 @Test void original_progress_history_keeps_confirmed_body_and_distinct_waiting_successor()throws Exception {
  setupContact();var contact=execute(prepare(contact("CONNECTED_VALID")));assertEquals(CommandOutcome.Status.SUCCEEDED,contact.status());
  var cipher=OpportunityProgressProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES"));var zone=ZoneId.of("Asia/Shanghai");
  try(var c=database.apiConnection()){
   var opening=inTransaction(c,Capability.QUERY,x->EventOpportunityReader.databaseBacked().forContact(x,seed.tenant(),contact.resultFact().id()));
   var first=inTransaction(c,Capability.COMMAND,x->{grant(x,"SALES_OPPORTUNITY_OWNER");grant(x,"TEAM_TASK_READ");return TaskFactory.databaseBacked().createInitialOpportunity(x,seed.tenant(),seed.appointment(),opening.selector(),zone,businessAt);});
   var at=businessAt.plusSeconds(60);var result=inTransaction(c,Capability.COMMAND,x->R2OpportunityProgressServices.create(cipher).record(x,seed.request().actor(),opening.selector(),first.selector(),new OpportunityProgressInput("PHONE_CONNECTED","核对原客户说明并约定补交材料",businessAt,businessAt.plusSeconds(86400)),zone,at));
   var reader=new R2TeamManagementReadService(new byte[32],protection,cipher,AuditAppender.databaseBacked("TEAM_PROGRESS_COVERAGE"));
   var detail=reader.detail(c,seed.request().actor(),"history",first.selector().id());var fields=(List<?>)detail.get("facts");
   assertTrue(fields.contains(List.of("原因或说明","核对原客户说明并约定补交材料")));assertTrue(fields.contains(List.of("处理时间",at.toString())));
   assertTrue(fields.stream().anyMatch(v->((List<?>)v).get(0).equals("当时确认人")&&!((List<?>)v).get(1).toString().contains("未取得")));assertNull(detail.get("action"));
   assertNotEquals(first.selector().id(),result.nextTask().id());var waiting=reader.detail(c,seed.request().actor(),"waiting",result.nextTask().id());assertNull(waiting.get("action"));assertFalse(waiting.toString().contains("衔接异常待处理"));
   deny(result.progress(),"TEAM_TASK_READ");
   var bomb=new OpportunityProgressProtection(){public byte[] encrypt(UUID t,UUID o,UUID f,String text){throw new AssertionError();}public String decrypt(UUID t,UUID o,UUID f,byte[] bytes){throw new AssertionError("Denied progress decrypted");}};
   var denied=new R2TeamManagementReadService(new byte[32],protection,bomb,AuditAppender.databaseBacked("TEAM_PROGRESS_DENIED"));
   assertEquals(403,assertThrows(R1ServiceReadRuntime.Failure.class,()->denied.detail(c,seed.request().actor(),"history",first.selector().id())).status());
  }
 }
}
