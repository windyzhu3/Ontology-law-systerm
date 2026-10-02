package io.github.windyzhu3.ontologylaw.identity;

import java.time.Instant;
import java.util.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ConfigurableAppointmentRolesIT extends IdentityAdminFixture {
 @Test void custom_role_lifecycle_preserves_existing_appointments_and_has_no_automatic_grants()throws Exception {
  var before=counts();var createKey=UUID.randomUUID();var body=Map.<String,Object>of("code","CUSTOM_ADVISOR","displayName","业务顾问");
  var created=execute(createKey,"CREATE_APPOINTMENT_ROLE",null,null,body);UUID role=success(created).id();
  assertTrue(execute(createKey,"CREATE_APPOINTMENT_ROLE",null,null,body).replay());
  assertEquals(before.get(3),counts().get(3));
  UUID person=principal(),org=organization();var appointment=body("principalId",person.toString(),"organizationId",org.toString(),"roleCode","CUSTOM_ADVISOR","effectiveFrom",Instant.now().minusSeconds(60).toString(),"effectiveUntil",null);
  UUID app=success(execute("CREATE_APPOINTMENT",null,null,appointment)).id();
  assertEquals("0",scalar("select count(*) from identity.authority_grant where tenant_id=? and grantee_appointment_id=?",seed.tenant(),app));
  String oldTag=tag("RENAME_APPOINTMENT_ROLE",role);
  success(execute("RENAME_APPOINTMENT_ROLE",role,oldTag,Map.of("displayName","高级顾问")));
  try(var c=database.apiConnection()) {
   c.setAutoCommit(false);
   io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.setLocalRole(c,io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.Capability.QUERY);
   var self=HumanIdentityReader.databaseBacked().self(c,new HumanIdentityReader.VerifiedHumanIdentity(seed.tenant(),person,"FIXTURE"));
   assertTrue(self.choices().getFirst().label().endsWith(" · 高级顾问"));c.rollback();
  }
  rejected("STALE_IDENTITY",execute("RENAME_APPOINTMENT_ROLE",role,oldTag,Map.of("displayName","过期覆盖")));
  var read=new IdentityAdminReadRuntime(audit,protection,candidates,"FIXTURE",directory);
  try(var c=database.apiConnection()) {
   var response=read.read(c,actor,"listAppointments",null,null,50,null,null);
   @SuppressWarnings("unchecked") var items=(List<Map<String,Object>>)response.get("items");
   assertEquals("高级顾问",items.stream().filter(i->i.get("id").equals(app.toString())).findFirst().orElseThrow().get("roleName"));
  }
  success(change("DEACTIVATE_APPOINTMENT_ROLE",role));
  rejected("IDENTITY_STATE_CONFLICT",execute("CREATE_APPOINTMENT",null,null,appointment));
  assertEquals("ACTIVE",scalar("select state from identity.appointment where tenant_id=? and appointment_id=?",seed.tenant(),app));
  success(change("SUSPEND_APPOINTMENT",app));success(change("RESUME_APPOINTMENT",app));
  try(var c=database.apiConnection()) {
   var response=read.read(c,actor,"getIdentityAdminOptions","APPOINTMENTS","ROLE",50,null,null);
   @SuppressWarnings("unchecked") var page=(Map<String,Object>)response.get("candidates");
   @SuppressWarnings("unchecked") var items=(List<Map<String,Object>>)page.get("items");
   assertTrue(items.stream().noneMatch(i->i.get("code").equals("CUSTOM_ADVISOR")));
  }
  success(change("REACTIVATE_APPOINTMENT_ROLE",role));
  success(execute("CREATE_APPOINTMENT",null,null,appointment));
  rejected("IDENTITY_BINDING_CONFLICT",execute("CREATE_APPOINTMENT_ROLE",null,null,body));
 }
 @Test void role_name_never_confers_management_and_foreign_tenant_role_is_not_found()throws Exception {
  UUID person=principal(),org=organization(),app=appointment(person,org);
  var founder=actor;actor=new Actor(seed.tenant(),person,app,null,null);
  var denied=assertThrows(IdentityCommands.Failure.class,()->execute("CREATE_APPOINTMENT_ROLE",null,null,Map.of("code","ADMIN","displayName","系统管理员")));
  assertEquals("NOT_AUTHORIZED",denied.code());actor=founder;
  var foreign=AuthorizationServiceIT.seed(database,"HUMAN","LEAD_CAPTURE");
  UUID foreignRole=UUID.fromString(scalar("select appointment_role_id from identity.appointment_role where tenant_id=? and role_code='OWNER'",foreign.tenant()));
  var missing=assertThrows(IdentityCommands.Failure.class,()->execute("RENAME_APPOINTMENT_ROLE",foreignRole,"\"identity."+"a".repeat(43)+"\"",Map.of("displayName","Other tenant")));
  assertEquals("NOT_FOUND",missing.code());
 }
}
