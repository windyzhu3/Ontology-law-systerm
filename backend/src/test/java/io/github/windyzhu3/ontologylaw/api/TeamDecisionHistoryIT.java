package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.lead.WorkcardTestFixture;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import io.github.windyzhu3.ontologylaw.execution.CanonicalJson;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class TeamDecisionHistoryIT extends WorkcardTestFixture {
 @Test void decision_history_keeps_original_reason_but_does_not_infer_actual_actor_from_legacy_appointment()throws Exception {
  setupCard(TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP);
  UUID principal=UUID.randomUUID(),decider=UUID.randomUUID();
  Subject decision;
  try(var c=database.apiConnection()) {
   decision=inTransaction(c,Capability.COMMAND,x->{
    sql(x,"insert into identity.principal(tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) values (?,?,'HUMAN','M02_TEST',?,'原确认人','ACTIVE',clock_timestamp())",seed.tenant(),principal,CanonicalJson.digest(principal.toString()));
    sql(x,"insert into identity.appointment(tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'SUPERVISOR',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),decider,principal,seed.org());
    var values=Map.<String,Object>of("tenantId",seed.tenant().toString(),"subject",Map.of("type",current.subject().type(),"id",current.subject().id().toString(),"revision",current.subject().revision()),"authoritySlot","ROUTING_SUPERVISOR","decisionCode","REQUEST_SOURCE_INTAKE_STOP","rationaleSummary","合成来源待核对");
    return TaskFactory.databaseBacked().decision(x,seed.tenant(),current,decider,"LEAD_ROUTING_DISPOSITION","REQUEST_SOURCE_INTAKE_STOP","合成来源待核对",values,TaskFactory.databaseBacked().now(x));
   });
   inTransaction(c,Capability.QUERY,x->{
    var original=CurrentTaskReader.databaseBacked().decision(x,seed.tenant(),decision.id());
    assertEquals(decision,original.selector());assertEquals(decider,original.actorAppointmentId());assertNotEquals(current.owner(),original.actorAppointmentId());
    assertEquals("合成来源待核对",original.rationale());assertNotNull(original.decidedAt());
    assertNull(CurrentTaskReader.databaseBacked().decision(x,UUID.randomUUID(),decision.id()));return null;
   });
   inTransaction(c,Capability.COMMAND,x->{grant(x,"TEAM_TASK_READ");TaskFactory.databaseBacked().complete(x,seed.tenant(),current,decision,TaskFactory.databaseBacked().now(x));return null;});
   var service=new R2TeamManagementReadService(new byte[32],protection,io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked("TEAM_HISTORY"));
   var detail=service.detail(c,seed.request().actor(),"history",current.selector().id());
   var fields=(List<?>)detail.get("facts");assertTrue(fields.contains(List.of("当时确认人","本次查询未取得原确认人")),"Legacy decision appointment cannot distinguish direct from represented action");
   assertTrue(fields.contains(List.of("原因或说明","合成来源待核对")));assertNull(detail.get("action"));
   deny(decision,"TEAM_TASK_READ");assertThrows(io.github.windyzhu3.ontologylaw.execution.R1ServiceReadRuntime.Failure.class,()->service.detail(c,seed.request().actor(),"history",current.selector().id()));
  }
 }
}
