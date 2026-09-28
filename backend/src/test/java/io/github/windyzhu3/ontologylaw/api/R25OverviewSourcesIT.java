package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.lead.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
class R25OverviewSourcesIT extends ContactFlowFixture {
 @Test void owner_period_scans_keep_original_creation_time_and_exact_due_responsibilities()throws Exception {
  setupContact();var lead=current.lead().id();var contact=execute(prepare(contact("CONNECTED_VALID")));assertEquals(CommandOutcome.Status.SUCCEEDED,contact.status());
  var start=Instant.parse("2026-08-01T00:00:00Z");var end=Instant.parse("2026-09-01T00:00:00Z");
  try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
   var l=LeadOverviewReader.databaseBacked();var rows=l.scan(x,seed.tenant(),start,end,null,100);assertEquals(1,rows.size());assertEquals(lead,rows.getFirst().lead().id());assertEquals(businessAt.minusSeconds(60),rows.getFirst().occurredAt());
   assertTrue(l.scan(x,UUID.randomUUID(),start,end,null,100).isEmpty());assertTrue(l.scan(x,seed.tenant(),end,end.plusSeconds(1),null,100).isEmpty());assertTrue(l.scan(x,seed.tenant(),start,end,lead,100).isEmpty());assertThrows(IllegalArgumentException.class,()->l.scan(x,seed.tenant(),start,end,null,101));
   var opportunities=OpportunityOverviewReader.databaseBacked().scan(x,seed.tenant(),start,end,null,100);assertEquals(1,opportunities.size());var opening=EventOpportunityReader.databaseBacked().forContact(x,seed.tenant(),contact.resultFact().id());assertEquals(opening.selector(),opportunities.getFirst().opportunity());
   assertTrue(OpportunityOverviewReader.databaseBacked().scan(x,UUID.randomUUID(),start,end,null,100).isEmpty());
   return null;
  });}
  setupContact();assertEquals(CommandOutcome.Status.SUCCEEDED,execute(prepare(contact("NOT_CONNECTED"))).status());selectTask(TaskFactory.Type.CONTACT_LEAD);var taskId=current.selector().id();
  try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
   var task=CurrentTaskReader.databaseBacked().read(x,seed.tenant(),taskId);var reader=ResponsibilityOverviewReader.databaseBacked();assertTrue(reader.overdue(x,seed.tenant(),task.slaDueAt().minusNanos(1000),null,100).isEmpty());var overdue=reader.overdue(x,seed.tenant(),task.slaDueAt().plusNanos(1000),null,100);assertEquals(1,overdue.size());assertEquals("WAITING",overdue.getFirst().state());assertEquals(task.selector(),overdue.getFirst().selector());assertTrue(reader.overdue(x,UUID.randomUUID(),end,null,100).isEmpty());return null;
  });}
 }
}
