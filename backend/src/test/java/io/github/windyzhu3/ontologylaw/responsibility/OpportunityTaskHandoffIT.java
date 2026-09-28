package io.github.windyzhu3.ontologylaw.responsibility;
import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.testing.PostgresIntegrationTest;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
/** Exercises the Task owner before composition commit; exact cross-owner facts are tested by Opportunity. */
class OpportunityTaskHandoffIT extends PostgresIntegrationTest {
 @Test void openHandoffCancelsAccuratelyAndPreservesDeadlineAndInitialIdentity()throws Exception {
  var s=AuthorizationServiceIT.seed(database);var repo=TaskFactory.databaseBacked();var zone=ZoneId.of("Asia/Shanghai");
  try(var c=database.adminConnection()){
   c.setAutoCommit(false);c.setTransactionIsolation(java.sql.Connection.TRANSACTION_READ_COMMITTED);
   try {
    UUID receiver=UUID.randomUUID();
    AuthorizationServiceIT.sql(c,"insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) select tenant_id,?,principal_id,organization_unit_id,'OWNER',effective_from,'ACTIVE',clock_timestamp() from identity.appointment where tenant_id=? and appointment_id=?",receiver,s.tenant(),s.appointment());
    Subject opportunity=new Subject("opportunity.opportunity",UUID.randomUUID(),0L,null);
    var original=repo.createInitialOpportunity(c,s.tenant(),s.appointment(),opportunity,zone,Instant.parse("2026-01-05T01:00:00Z"));
    Instant deadline;try(var p=c.prepareStatement("select original_sla_due_at from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?")){p.setObject(1,s.tenant());p.setObject(2,original.selector().id());try(var r=p.executeQuery()){assertTrue(r.next());deadline=r.getObject(1,OffsetDateTime.class).toInstant();}}
    Subject handoff=new Subject("opportunity.responsibility_handoff",UUID.randomUUID(),0L,null);
    var result=repo.handoffOpportunityTask(c,s.tenant(),handoff,opportunity,opportunity,original.selector(),null,receiver,UUID.randomUUID(),s.appointment(),zone);
    assertEquals("CANCELLED",repo.read(c,s.tenant(),original.selector().id()).state());assertNull(repo.read(c,s.tenant(),original.selector().id()).completion());
    try(var p=c.prepareStatement("select cancellation_reason_code,cancellation_fact_type,cancellation_fact_id,cancellation_fact_revision from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?")) {
     p.setObject(1,s.tenant());p.setObject(2,original.selector().id());
     try(var r=p.executeQuery()){assertTrue(r.next());assertEquals("R2_OPPORTUNITY_HANDOFF_V1",r.getString(1));assertEquals(handoff.type(),r.getString(2));assertEquals(handoff.id(),r.getObject(3,UUID.class));assertEquals(0,r.getLong(4));}
    }
    assertEquals("OPEN",result.task().state());assertEquals(receiver,result.task().owner());assertEquals(deadline,result.originalDueAt());assertNull(result.newWait());
    assertEquals(original.selector().id(),OpportunityMaintenanceTasks.databaseBacked().initial(c,s.tenant(),opportunity.id()).selector().id());
    assertThrows(RuntimeException.class,()->repo.handoffOpportunityTask(c,s.tenant(),handoff,opportunity,opportunity,original.selector(),null,receiver,UUID.randomUUID(),s.appointment(),zone));
   } finally {c.rollback();}
  }
 }
 @Test void initialHandoffUsesActualBusinessClockAndCannotCreateAnotherRoot()throws Exception {
  var s=AuthorizationServiceIT.seed(database);var repo=TaskFactory.databaseBacked();var zone=ZoneId.of("Asia/Shanghai");
  try(var c=database.adminConnection()){
   c.setAutoCommit(false);c.setTransactionIsolation(java.sql.Connection.TRANSACTION_READ_COMMITTED);
   try {
    Subject opportunity=new Subject("opportunity.opportunity",UUID.randomUUID(),0L,null);Subject handoff=new Subject("opportunity.responsibility_handoff",UUID.randomUUID(),0L,null);
    var result=repo.handoffOpportunityTask(c,s.tenant(),handoff,opportunity,opportunity,null,null,s.appointment(),UUID.randomUUID(),s.appointment(),zone);
    assertEquals(R1BusinessTime.due(result.task().createdAt(),14400,zone),result.originalDueAt());assertEquals("OPEN",result.task().state());
    assertTrue(OpportunityMaintenanceTasks.databaseBacked().initialExists(c,s.tenant(),opportunity.id()));
    assertThrows(RuntimeException.class,()->repo.handoffOpportunityTask(c,s.tenant(),handoff,opportunity,opportunity,null,null,s.appointment(),UUID.randomUUID(),s.appointment(),zone));
   }finally{c.rollback();}
  }
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
 void waitingHandoffRetainsWaitAndOnlyGuardedRecoveryOpensSameCard(boolean overdue)throws Exception {
  var s=AuthorizationServiceIT.seed(database);var repo=TaskFactory.databaseBacked();var zone=ZoneId.of("Asia/Shanghai");
  try(var c=database.adminConnection()){
   c.setAutoCommit(false);c.setTransactionIsolation(java.sql.Connection.TRANSACTION_READ_COMMITTED);
   try {
    UUID receiver=UUID.randomUUID();
    AuthorizationServiceIT.sql(c,"insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) select tenant_id,?,principal_id,organization_unit_id,'OWNER',effective_from,'ACTIVE',clock_timestamp() from identity.appointment where tenant_id=? and appointment_id=?",receiver,s.tenant(),s.appointment());
    Subject opportunity=new Subject("opportunity.opportunity",UUID.randomUUID(),0L,null);Instant created=overdue?Instant.parse("2026-01-05T01:00:00Z"):repo.now(c),due=created.plusSeconds(3600);
    var initial=repo.createInitialOpportunity(c,s.tenant(),s.appointment(),opportunity,zone,created);
    Subject progress=new Subject("opportunity.opportunity_progress",UUID.randomUUID(),null,Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
    repo.complete(c,s.tenant(),initial,progress,created);
    var waiting=repo.createOpportunityFollowup(c,s.tenant(),repo.read(c,s.tenant(),initial.selector().id()),progress,zone,created,due);
    var oldWait=EventResponsibilityReader.databaseBacked().latestWait(c,s.tenant(),waiting.selector().id());
    var result=repo.handoffOpportunityTask(c,s.tenant(),new Subject("opportunity.responsibility_handoff",UUID.randomUUID(),0L,null),opportunity,opportunity,waiting.selector(),oldWait.selector(),receiver,UUID.randomUUID(),s.appointment(),zone);
    assertEquals("WAITING",result.task().state());assertEquals(oldWait.selector(),result.originalWait());
    var inherited=EventResponsibilityReader.databaseBacked().latestWait(c,s.tenant(),result.task().selector().id());assertEquals(due,inherited.resumeDue());assertEquals("R2_OPPORTUNITY_HANDOFF_WAIT_V1",inherited.profile());
    assertEquals(progress,OpportunityMaintenanceTasks.databaseBacked().waitProgress(c,s.tenant(),result.task().selector().id()));
    var again=repo.handoffOpportunityTask(c,s.tenant(),new Subject("opportunity.responsibility_handoff",UUID.randomUUID(),0L,null),opportunity,CurrentTaskReader.databaseBacked().read(c,s.tenant(),result.task().selector().id()).responsibilityBasis(),result.task().selector(),result.newWait(),s.appointment(),UUID.randomUUID(),s.appointment(),zone);
    assertEquals("WAITING",again.task().state());assertEquals(result.originalDueAt(),again.originalDueAt());
    assertEquals(progress,OpportunityMaintenanceTasks.databaseBacked().waitProgress(c,s.tenant(),again.task().selector().id()));
    if(overdue){
     var reopened=repo.reopenOpportunityFollowup(c,s.tenant(),again.task().selector(),opportunity,s.appointment(),again.newWait(),progress,due);
     assertEquals("OPEN",reopened.state());assertEquals(again.task().selector().id(),reopened.selector().id());assertEquals(1L,reopened.selector().revision());
    }else assertThrows(RuntimeException.class,()->repo.reopenOpportunityFollowup(c,s.tenant(),again.task().selector(),opportunity,s.appointment(),again.newWait(),progress,due));
   }finally{c.rollback();}
  }
 }}


