package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.lead.WorkcardTestFixture;
import io.github.windyzhu3.ontologylaw.responsibility.TaskFactory;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MyTaskSelectionIT extends WorkcardTestFixture {
  @Test void selects_owned_open_task_without_changing_recommendation_and_hides_denied_selection() throws Exception {
    setupCard(TaskFactory.Type.COMPLETE_LEAD_INGRESS);
    var second=addTask(UUID.randomUUID(),Instant.now(),current.createdAt().plusSeconds(7*24*3600),"OPEN");
    var service=new CurrentWorkCardDisclosureService(protection,policies,"MY_TASKS_IT");
    try(var c=database.apiConnection()) {
      var selected=service.read(c,seed.request().actor(),UUID.randomUUID(),null,second.selector().id());
      assertEquals(200,selected.status());
      assertEquals(second.selector().id().toString(),((Map<?,?>)selected.body().get("currentCard")).get("taskId"));
      assertEquals(current.selector().id().toString(),selected.body().get("recommendedTaskId"));
      assertEquals(2,((List<?>)selected.body().get("myTasks")).size());
      assertNotNull(((Map<?,?>)((List<?>)selected.body().get("myTasks")).get(0)).get("subjectTitle"));
      assertEquals(7,auditCount());
    }
    deny(second.selector(),"LEAD_INGRESS_COMPLETE");
    try(var c=database.apiConnection()) {
      var hidden=service.read(c,seed.request().actor(),UUID.randomUUID(),null,second.selector().id());
      assertEquals(current.selector().id().toString(),((Map<?,?>)hidden.body().get("currentCard")).get("taskId"));
      assertNotNull(hidden.body().get("selectionNotice"));
      assertFalse(hidden.body().toString().contains(second.selector().id().toString()));
    }
  }
  @Test void unknown_waiting_and_cancelled_selection_return_recommendation_without_disclosing_requested_id() throws Exception {
    setupCard(TaskFactory.Type.COMPLETE_LEAD_INGRESS);
    var waiting=addTask(UUID.randomUUID(),Instant.now(),Instant.now().plusSeconds(7200),"WAITING");
    for(var id:List.of(UUID.randomUUID(),waiting.selector().id())) {
      try(var c=database.apiConnection()) {
        var response=new CurrentWorkCardDisclosureService(protection,policies,"MY_TASKS_IT").read(c,seed.request().actor(),UUID.randomUUID(),null,id);
        assertEquals(200,response.status());
        assertEquals(current.selector().id().toString(),((Map<?,?>)response.body().get("currentCard")).get("taskId"));
        assertNotNull(response.body().get("selectionNotice"));
        assertFalse(response.body().toString().contains(id.toString()));
      }
    }
    cancelCurrent();
    try(var c=database.apiConnection()) {
      var response=new CurrentWorkCardDisclosureService(protection,policies,"MY_TASKS_IT").read(c,seed.request().actor(),UUID.randomUUID(),null,current.selector().id());
      assertNull(response.body().get("currentCard"));
      assertEquals(List.of(),response.body().get("myTasks"));
      assertNotNull(response.body().get("selectionNotice"));
    }
  }
  @Test void task_id_does_not_give_another_authorized_owner_access() throws Exception {
    setupCard(TaskFactory.Type.COMPLETE_LEAD_INGRESS);
    UUID principal=UUID.randomUUID(),appointment=UUID.randomUUID();
    mutate("insert into identity.principal (tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) values (?,?,'HUMAN','OTHER',?,'其他用户','ACTIVE',clock_timestamp())",seed.tenant(),principal,io.github.windyzhu3.ontologylaw.execution.CanonicalJson.digest(principal.toString()));
    mutate("insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'OWNER',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),appointment,principal,seed.org());
    mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,'LEAD_INGRESS_COMPLETE',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),appointment,seed.appointment(),seed.org());
    var actor=new io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor(seed.tenant(),principal,appointment,null,null);
    try(var c=database.apiConnection()) {
      var response=new CurrentWorkCardDisclosureService(protection,policies,"MY_TASKS_IT").read(c,actor,UUID.randomUUID(),null,current.selector().id());
      assertEquals(200,response.status());assertNull(response.body().get("currentCard"));
      assertEquals(List.of(),response.body().get("myTasks"));
      assertFalse(response.body().toString().contains(current.selector().id().toString()));
      assertEquals(0,auditCount());
    }
  }
}

