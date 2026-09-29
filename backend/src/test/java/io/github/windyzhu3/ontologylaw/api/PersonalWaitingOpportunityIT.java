package io.github.windyzhu3.ontologylaw.api;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.lead.ContactFlowFixture;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import java.time.*;import java.util.*;import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
class PersonalWaitingOpportunityIT extends ContactFlowFixture {
 @Test void real_opportunity_wait_and_reopen_share_personal_count_and_disclosure()throws Exception {
  setupContact();var contact=execute(prepare(contact("CONNECTED_VALID")));var zone=ZoneId.of("Asia/Shanghai");var cipher=OpportunityProgressProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES"));
  try(var c=database.apiConnection()){
   var result=inTransaction(c,Capability.COMMAND,x->{var opportunity=EventOpportunityReader.databaseBacked().forContact(x,seed.tenant(),contact.resultFact().id()).selector();grant(x,"SALES_OPPORTUNITY_OWNER");var first=TaskFactory.databaseBacked().createInitialOpportunity(x,seed.tenant(),seed.appointment(),opportunity,zone,businessAt);return R2OpportunityProgressServices.create(cipher).record(x,seed.request().actor(),opportunity,first.selector(),new OpportunityProgressInput("PHONE_CONNECTED","确认后续沟通",businessAt,businessAt.plusSeconds(86400)),zone,businessAt.plusSeconds(60));});
   var service=new CurrentWorkCardDisclosureService(protection,policies,"WAIT_OPPORTUNITY_IT",cipher);var response=service.readWaiting(c,seed.request().actor(),UUID.randomUUID(),null,20,null);assertEquals(200,response.status(),response.errorCode());assertEquals(1,response.body().get("totalCount"));assertEquals(service.read(c,seed.request().actor(),UUID.randomUUID(),null).body().get("waitingCount"),response.body().get("totalCount"));
   var detail=service.readWaiting(c,seed.request().actor(),UUID.randomUUID(),result.nextTask().id(),20,null);assertEquals(200,detail.status());assertEquals("WAIT_DUE",((Map<?,?>)detail.body().get("waitingDetail")).get("state"));
   var next=inTransaction(c,Capability.QUERY,x->CurrentTaskReader.databaseBacked().read(x,seed.tenant(),result.nextTask().id()));var wait=inTransaction(c,Capability.QUERY,x->EventResponsibilityReader.databaseBacked().latestWait(x,seed.tenant(),next.selector().id()));
   inTransaction(c,Capability.COMMAND,x->R2OpportunityFollowupServices.create(cipher).reopen(x,seed.tenant(),next.subject(),next.selector(),wait.selector(),result.progress(),wait.resumeDue()));
   var ready=service.readWaiting(c,seed.request().actor(),UUID.randomUUID(),next.selector().id(),20,null);assertEquals(200,ready.status(),ready.errorCode());assertEquals(0,ready.body().get("totalCount"));assertEquals(true,((Map<?,?>)ready.body().get("waitingDetail")).get("canHandle"));
  }
 }
}
