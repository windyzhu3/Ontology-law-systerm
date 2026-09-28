package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.lead.ContactFlowFixture;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;

class TeamEarlyHistoryIT extends ContactFlowFixture {
 private void allow()throws Exception{try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"TEAM_TASK_READ");grant(x,"LEAD_ASSIGN");return null;});}}
 private R2TeamManagementReadService reads(){return new R2TeamManagementReadService(new byte[32],protection,AuditAppender.databaseBacked("TEAM_EARLY_HISTORY_IT"));}
 @Test void completed_ingress_assignment_and_contact_use_their_original_confirmed_input()throws Exception {
  for(var type:List.of(TaskFactory.Type.COMPLETE_LEAD_INGRESS,TaskFactory.Type.ASSIGN_LEAD,TaskFactory.Type.CONTACT_LEAD)){
   setupFlow(type);allow();UUID original=current.selector().id();
   Map<String,Object> values=switch(type){
    case COMPLETE_LEAD_INGRESS->Map.of("phone","+12025550177","sourceCode","CUSTOMER_PROVIDED","sourceSummary","客户补充原联系方式");
    case ASSIGN_LEAD->Map.of("ownerAppointmentId",secondaryAppointment.toString());
    case CONTACT_LEAD->{var v=new TreeMap<String,Object>(contact("CONNECTED_VALID"));v.put("resultSummary","本人核对的首次联系说明");yield v;}
    default->throw new AssertionError();
   };
   var command=prepare(values);
   try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,tx->{assertNull(TaskConfirmationReader.databaseBacked().forTask(tx,seed.tenant(),original),"Unconfirmed values are not history");return null;});}
   assertEquals(CommandOutcome.Status.SUCCEEDED,execute(command).status(),type.name());
   assertEquals(CommandOutcome.Status.SUCCEEDED,execute(command).status(),"Original receipt replay remains idempotent");
   try(var c=database.apiConnection()){
    var detail=reads().detail(c,seed.request().actor(),"history",original);var facts=(List<?>)detail.get("facts");
    assertTrue(facts.contains(List.of("当时确认人","本次查询未取得原确认人")),"Legacy represented appointment cannot prove actual confirmer: "+type.name());
    assertTrue(facts.stream().anyMatch(v->((List<?>)v).get(0).equals("处理时间")&&!((List<?>)v).get(1).toString().contains("未取得")),type.name());
    if(type==TaskFactory.Type.COMPLETE_LEAD_INGRESS)assertTrue(facts.contains(List.of("原因或说明","客户补充原联系方式")));
    if(type==TaskFactory.Type.CONTACT_LEAD)assertTrue(facts.contains(List.of("原因或说明","本人核对的首次联系说明")));
    assertNull(detail.get("action"));assertNull(detail.get("taskId"));
    var draft=inTransaction(c,Capability.QUERY,x->ActionDraftService.databaseBacked().read(x,seed.tenant(),original));assertEquals("CONFIRMED",draft.state());
    deny(draft.selector(),"TEAM_TASK_READ");long auditBefore=auditCount();
    var deniedProbe=new ReadConnectionProbe(c);
    assertEquals(403,assertThrows(R1ServiceReadRuntime.Failure.class,()->reads().detail(deniedProbe.connection(),seed.request().actor(),"history",original)).status());
    assertTrue(deniedProbe.statements.stream().noneMatch(q->q.contains("jsonb_each")),"Denied confirmed values must not be decoded");
    assertEquals(auditBefore,auditCount(),"Denied confirmed input must not commit partial disclosure");
   }
  }
 }
 @Test void delegated_contact_history_does_not_mislabel_the_represented_appointment_as_actual_confirmer()throws Exception {
  setupContact();allow();UUID principal=UUID.randomUUID(),appointment=UUID.randomUUID();var originalTask=current;
  mutate("insert into identity.principal(tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) values (?,?,'HUMAN','TEAM_DELEGATE',?,'合成实际代办确认人','ACTIVE',clock_timestamp())",seed.tenant(),principal,CanonicalJson.digest(principal.toString()));
  mutate("insert into identity.appointment(tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'DELEGATE',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),appointment,principal,seed.org());
  mutate("insert into identity.delegation_grant(tenant_id,delegation_grant_id,source_authority_grant_id,delegator_appointment_id,delegate_appointment_id,scope_organization_unit_id,valid_from,state,created_at) values (?,?,?,?,?,?,clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),seed.grant(),seed.appointment(),appointment,seed.org());
  var actor=new io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor(seed.tenant(),principal,appointment,seed.principal(),seed.appointment());var original=prepare(contact("CONNECTED_VALID"));
  var command=new CommandEnvelope(original.type(),original.commandId(),original.correlationId(),actor,original.payload(),new CommandEnvelope.TaskPrecondition(originalTask.selector().id(),R1ResourceTags.task(actor,originalTask.selector(),originalTask.state())));
  assertEquals(CommandOutcome.Status.SUCCEEDED,execute(command).status());
  try(var c=database.apiConnection()){
   var detail=reads().detail(c,seed.request().actor(),"history",originalTask.selector().id());
   assertTrue(((List<?>)detail.get("facts")).contains(List.of("当时确认人","本次查询未取得原确认人")),"Do not infer actual actor from represented appointment");
   inTransaction(c,Capability.QUERY,tx->{assertNull(TaskConfirmationReader.databaseBacked().forTask(tx,UUID.randomUUID(),originalTask.selector().id()));return null;});
   var failing=new R2TeamManagementReadService(new byte[32],protection,(tx,entry)->{});long before=auditCount();
   assertThrows(java.sql.SQLException.class,()->failing.detail(c,seed.request().actor(),"history",originalTask.selector().id()));assertEquals(before,auditCount());
  }
 }
 private void history(UUID original)throws Exception {
  try(var c=database.apiConnection()){
   var detail=reads().detail(c,seed.request().actor(),"history",original);assertNull(detail.get("action"));assertNull(detail.get("taskId"));
   assertTrue(((List<?>)detail.get("facts")).stream().anyMatch(v->((List<?>)v).get(0).equals("处理时间")&&!((List<?>)v).get(1).toString().contains("未取得")));
  }
 }
 @Test void early_decisions_and_wait_recovery_keep_their_actual_source_records()throws Exception {
  setupFlow(TaskFactory.Type.RESOLVE_LEAD_DUPLICATE);allow();var task=current.selector().id();
  assertEquals(CommandOutcome.Status.SUCCEEDED,execute(prepare(Map.of("decisionCode","KEEP_SEPARATE","candidateLeadId",secondaryLead.toString(),"candidateLeadRevision",1L,"partyId",secondaryParty.toString(),"partyRevision",0L,"rationaleSummary","核对后保留独立线索"))).status());history(task);
  setupFlow(TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP);allow();task=current.selector().id();
  try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,tx->{grant(tx,"SOURCE_INTAKE_REQUEST_ACK");return null;});}
  var stop=execute(prepare(Map.of("decisionCode","REQUEST_SOURCE_INTAKE_STOP","rationaleSummary","请原来源核对停止接入")));assertEquals(CommandOutcome.Status.SUCCEEDED,stop.status());history(task);
  selectTask(TaskFactory.Type.ACK_SOURCE_INTAKE_STOP_REQUEST);task=current.selector().id();
  assertEquals(CommandOutcome.Status.SUCCEEDED,execute(prepare(Map.of("causalDecisionId",stop.resultFact().id().toString(),"causalDecisionHash",stop.resultFact().hash(),"rationaleSummary","原来源已核对"))).status());history(task);
  selectTask(TaskFactory.Type.RESOLVE_SOURCE_REQUEST);task=current.selector().id();
  assertEquals(CommandOutcome.Status.SUCCEEDED,execute(prepare(Map.of("decisionCode","END_LEAD","rationaleSummary","来源核对后结束"))).status());history(task);
  setupContact();allow();var firstContact=current.selector().id();
  assertEquals(CommandOutcome.Status.SUCCEEDED,execute(prepare(contact("NOT_CONNECTED"))).status());
  history(firstContact);selectTask(TaskFactory.Type.CONTACT_LEAD);var waiting=current.selector().id();assertNotEquals(firstContact,waiting);
  try(var c=database.apiConnection()){var detail=reads().detail(c,seed.request().actor(),"waiting",waiting);assertNull(detail.get("action"));assertFalse(detail.toString().contains("衔接异常待处理"));}
  recoverContact();
  try(var c=database.apiConnection()){assertEquals(412,assertThrows(R1ServiceReadRuntime.Failure.class,()->reads().detail(c,seed.request().actor(),"waiting",waiting)).status());}
  var invalid=execute(prepare(contact("SUSPECT_INVALID")));assertEquals(CommandOutcome.Status.SUCCEEDED,invalid.status());history(waiting);
  selectTask(TaskFactory.Type.REVIEW_LEAD_VALIDITY);task=current.selector().id();assertEquals(CommandOutcome.Status.SUCCEEDED,execute(prepare(review(invalid,"CONFIRM_INVALID"))).status());history(task);
 }
}
