package io.github.windyzhu3.ontologylaw.query;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.identity.WorkcardOwnerReader.*;
import io.github.windyzhu3.ontologylaw.lead.CurrentLeadReader;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.time.*;
import java.util.*;
class OpportunityResponsibilityProjectionTest {
 @Test void newOwnerSeesOwnEmptyDraftCardWithExactProtectedHandoffBasis() {
  var actor=new Actor(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,null);
  var opportunity=new Subject("opportunity.opportunity",UUID.randomUUID(),0L,null);var basis=new Subject("opportunity.responsibility_handoff",UUID.randomUUID(),0L,null);
  var task=new CurrentTaskReader.Task(new Subject("responsibility.task_occurrence",UUID.randomUUID(),0L,null),actor.appointmentId(),TaskFactory.Type.PROGRESS_OPPORTUNITY,opportunity,"OPEN",Instant.EPOCH,"R2_BUSINESS_4H_V1",14400,Instant.EPOCH.plusSeconds(14400),null,basis);
  var lead=mock(CurrentLeadReader.Lead.class);when(lead.selector()).thenReturn(new Subject("lead.lead",UUID.randomUUID(),0L,null));
  var org=new Subject("identity.organization_unit",UUID.randomUUID(),0L,null);
  var owner=new Owner(new Appointment(new Subject("identity.appointment",actor.appointmentId(),0L,null),actor.principalId(),org.id(),"OWNER","ACTIVE",Instant.EPOCH,null),new Principal(new Subject("identity.principal",actor.principalId(),0L,null),"Receiver","HUMAN","ACTIVE"),new Organization(org,"TEAM","Team","ACTIVE"));
  var header=new OpportunityCommandReader.Header(opportunity,UUID.randomUUID(),false);
  var current=new OpportunityResponsibilityReader.Responsibility(basis,actor.appointmentId());
  var result=new OpportunityWorkCardQuery().project(actor,Instant.EPOCH,new OpportunityWorkCardQuery.Data(task,header,lead,owner,null,current));
  assertTrue(result.sources().contains(basis));assertNull(result.card().get("actionDraft"));
  assertEquals(R1ResourceTags.task(actor,task.selector(),"OPEN",basis),((Map<?,?>)result.card().get("preconditions")).get("taskETag"));
  assertThrows(IllegalArgumentException.class,()->new OpportunityWorkCardQuery().project(actor,Instant.EPOCH,new OpportunityWorkCardQuery.Data(task,header,lead,owner,null,new OpportunityResponsibilityReader.Responsibility(opportunity,actor.appointmentId()))));
 }
}
