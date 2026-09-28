package io.github.windyzhu3.ontologylaw.opportunity;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.lead.ContactFlowFixture;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.time.*;import java.util.*;import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
class TeamWaitingReaderIT extends ContactFlowFixture {
 @Test void team_waiting_tracks_original_receipt_and_disappears_after_real_reopen()throws Exception{
  setupContact();var contact=execute(prepare(contact("CONNECTED_VALID")));var zone=ZoneId.of("Asia/Shanghai");var cipher=OpportunityProgressProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES"));
  try(var c=database.apiConnection()){
   var result=inTransaction(c,Capability.COMMAND,x->{var opportunity=EventOpportunityReader.databaseBacked().forContact(x,seed.tenant(),contact.resultFact().id()).selector();grant(x,"SALES_OPPORTUNITY_OWNER");var first=TaskFactory.databaseBacked().createInitialOpportunity(x,seed.tenant(),seed.appointment(),opportunity,zone,businessAt);var due=businessAt.plusSeconds(86400);return io.github.windyzhu3.ontologylaw.api.R2OpportunityProgressServices.create(cipher).record(x,seed.request().actor(),opportunity,first.selector(),new OpportunityProgressInput("PHONE_CONNECTED","确认后续沟通",businessAt,due),zone,businessAt.plusSeconds(60));});
   var reader=TeamResponsibilityReader.databaseBacked();var rows=inTransaction(c,Capability.QUERY,x->reader.scan(x,seed.tenant(),TeamResponsibilityReader.View.WAITING,null,100));var next=rows.stream().filter(t->t.selector().equals(result.nextTask())).findFirst().orElseThrow();assertEquals("WAITING",next.state());
   var wait=inTransaction(c,Capability.QUERY,x->EventResponsibilityReader.databaseBacked().latestWait(x,seed.tenant(),next.selector().id()));assertEquals(next.selector().revision().longValue(),wait.taskRevision());assertEquals(businessAt.plusSeconds(86400),wait.resumeDue());
   inTransaction(c,Capability.COMMAND,x->io.github.windyzhu3.ontologylaw.api.R2OpportunityFollowupServices.create(cipher).reopen(x,seed.tenant(),next.subject(),next.selector(),wait.selector(),result.progress(),wait.resumeDue()));
   assertTrue(inTransaction(c,Capability.QUERY,x->reader.scan(x,seed.tenant(),TeamResponsibilityReader.View.WAITING,null,100)).stream().noneMatch(t->t.selector().id().equals(next.selector().id())));
   var current=inTransaction(c,Capability.QUERY,x->CurrentTaskReader.databaseBacked().read(x,seed.tenant(),next.selector().id()));assertEquals("OPEN",current.state());assertEquals(next.slaDueAt(),current.slaDueAt());
  }
 }
}
