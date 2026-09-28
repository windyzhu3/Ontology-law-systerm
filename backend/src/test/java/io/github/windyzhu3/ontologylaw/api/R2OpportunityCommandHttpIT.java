package io.github.windyzhu3.ontologylaw.api;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.time.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
class R2OpportunityCommandHttpIT extends R1HttpFixture {
    @Test void http_draft_confirm_replay_and_receipt_form_one_progress_transaction()throws Exception {
        setupContact();var contact=execute(prepare(contact("CONNECTED_VALID")));
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{var opp=EventOpportunityReader.databaseBacked().forContact(x,seed.tenant(),contact.resultFact().id()).selector();grant(x,"SALES_OPPORTUNITY_OWNER");current=TaskFactory.databaseBacked().createInitialOpportunity(x,seed.tenant(),seed.appointment(),opp,ZoneId.of("Asia/Shanghai"),businessAt);return null;});}
        opportunityProtection=OpportunityProgressProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES"));
        var values=Map.<String,Object>of("progressTypeCode","PHONE_CONNECTED","progressSummary","Client confirmed scope","occurredAt",businessAt.toString(),"nextCheckAt",Instant.now().plusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString());
        try(var http=new HttpHarness()){
            var currentResponse=http.request("GET","/api/v1/workcards/current",null,Map.of());
            assertEquals(200,currentResponse.statusCode(),currentResponse.body());
            var card=(Map<?,?>)http.body(currentResponse).get("currentCard");assertNotNull(card);
            assertEquals("PROGRESS_OPPORTUNITY",card.get("taskType"));assertEquals("OPPORTUNITY",((Map<?,?>)card.get("subject")).get("subjectType"));
            String base="/api/v1/tasks/"+current.selector().id();UUID save=UUID.randomUUID();
            var draftBody=Map.of("actionCode","RECORD_OPPORTUNITY_PROGRESS","schemaVersion",1,"values",values);
            var saved=http.request("PUT",base+"/opportunity-progress-draft",draftBody,Map.of("Idempotency-Key",save.toString(),"If-None-Match","*"));
            assertEquals(201,saved.statusCode(),saved.body());var response=http.body(saved);var draft=(Map<?,?>)response.get("draft");var tags=(Map<?,?>)response.get("preconditions");
            assertEquals("RECORD_OPPORTUNITY_PROGRESS",draft.get("actionCode"));assertEquals(values,draft.get("values"));assertEquals("OPEN",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),current.selector().id()));
            var savedCardResponse=http.request("GET","/api/v1/workcards/current",null,Map.of());
            assertEquals(200,savedCardResponse.statusCode(),savedCardResponse.body());
            var savedCard=(Map<?,?>)http.body(savedCardResponse).get("currentCard");
            assertEquals(values,((Map<?,?>)savedCard.get("commandForm")).get("values"));
            assertEquals(draft.get("draftId"),((Map<?,?>)savedCard.get("actionDraft")).get("draftId"));
            var body=new TreeMap<String,Object>(values);body.put("draftId",draft.get("draftId"));body.put("expectedDraftRevision",draft.get("draftRevision"));body.put("draftDigest",draft.get("digest"));UUID key=UUID.randomUUID();
            var headers=Map.of("Idempotency-Key",key.toString(),"If-Match",(String)tags.get("taskETag"));
            var invalid=new TreeMap<String,Object>(body);invalid.put("businessCategory","EXECUTION");
            var rejected=http.request("POST",base+"/commands/record-opportunity-progress",invalid,Map.of("Idempotency-Key",UUID.randomUUID().toString(),"If-Match",(String)tags.get("taskETag")));assertEquals(400,rejected.statusCode(),rejected.body());
            var mismatch=new TreeMap<String,Object>(body);mismatch.put("progressSummary","Unconfirmed changes");
            var notConfirmed=http.request("POST",base+"/commands/record-opportunity-progress",mismatch,Map.of("Idempotency-Key",UUID.randomUUID().toString(),"If-Match",(String)tags.get("taskETag")));assertEquals(409,notConfirmed.statusCode(),notConfirmed.body());assertEquals("DRAFT_DIGEST_MISMATCH",http.body(notConfirmed).get("code"));
            var confirmed=http.request("POST",base+"/commands/record-opportunity-progress",body,headers);assertEquals(200,confirmed.statusCode(),confirmed.body());
            var after=http.request("GET","/api/v1/workcards/current",null,Map.of());
            assertEquals(200,after.statusCode(),after.body());assertNull(http.body(after).get("currentCard"));
            var repeated=http.request("POST",base+"/commands/record-opportunity-progress",body,headers);assertEquals(200,repeated.statusCode(),repeated.body());assertEquals(http.body(confirmed),http.body(repeated));
            var recovered=http.request("GET","/api/v1/commands/"+key+"/receipt",null,Map.of());assertEquals(200,recovered.statusCode());assertEquals(http.body(confirmed),http.body(recovered));
            var savedAgain=http.request("PUT",base+"/opportunity-progress-draft",draftBody,Map.of("Idempotency-Key",save.toString(),"If-None-Match","*"));assertEquals(201,savedAgain.statusCode(),savedAgain.body());assertEquals(false,((Map<?,?>)http.body(savedAgain).get("draft")).get("editable"));
            assertEquals("1",scalar("select count(*) from opportunity.opportunity_progress where tenant_id=?",seed.tenant()));assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and predecessor_task_occurrence_id=? and state='WAITING'",seed.tenant(),current.selector().id()));
        }
    }
}
