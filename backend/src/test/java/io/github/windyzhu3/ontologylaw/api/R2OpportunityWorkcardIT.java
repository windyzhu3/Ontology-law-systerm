package io.github.windyzhu3.ontologylaw.api;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import io.github.windyzhu3.ontologylaw.lead.CurrentLeadReader;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import io.github.windyzhu3.ontologylaw.execution.PublicFactReferences;
import java.time.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class R2OpportunityWorkcardIT extends R1HttpFixture {
    private Subject sourceLead;
    private void setupOpportunity()throws Exception {
        setupContact();var result=execute(prepare(contact("CONNECTED_VALID")));
        opportunityProtection=OpportunityProgressProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES"));
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            var event=EventOpportunityReader.databaseBacked().forContact(x,seed.tenant(),result.resultFact().id());
            sourceLead=CurrentLeadReader.databaseBacked(protection).selector(x,seed.tenant(),event.leadId());
            grant(x,"SALES_OPPORTUNITY_OWNER");
            current=TaskFactory.databaseBacked().createInitialOpportunity(x,seed.tenant(),seed.appointment(),event.selector(),ZoneId.of("Asia/Shanghai"),businessAt);
            return null;
        });}
    }
    private CurrentWorkCardDisclosureService.Response read(String tag,UUID selected)throws Exception {
        try(var c=database.apiConnection()) {
            return new CurrentWorkCardDisclosureService(protection,policies,"R2_CARD_IT",opportunityProtection).read(c,seed.request().actor(),UUID.randomUUID(),tag,selected);
        }
    }
    @Test void opportunity_eligibility_path_is_not_read_again_for_the_selected_card()throws Exception {
        setupOpportunity();
        try(var c=database.apiConnection()) {
            var probe=new ReadConnectionProbe(c);
            var response=new CurrentWorkCardDisclosureService(protection,policies,"PATH_REUSE_IT",opportunityProtection).read(probe.connection(),seed.request().actor(),UUID.randomUUID(),null,current.selector().id());
            assertEquals(200,response.status());
            long headers=probe.statements.stream().filter(sql->sql.startsWith("select ")&&sql.contains("owner_appointment_id")&&sql.contains("closed_at")&&sql.contains("from \"opportunity\".\"opportunity\"")).count();
            assertEquals(1,headers,"opportunity header must be read once per task");
        }
    }
    @Test void typed_opportunity_card_is_audited_selectable_and_cache_revalidated()throws Exception {
        setupOpportunity();long before=auditCount();
        var response=read(null,current.selector().id());assertEquals(200,response.status(),response.errorCode());
        var card=(Map<?,?>)response.body().get("currentCard");assertNotNull(card);
        assertEquals("PROGRESS_OPPORTUNITY",card.get("taskType"));assertEquals("OPPORTUNITY_PROGRESS",card.get("expectedCompletionFact"));
        var subject=(Map<?,?>)card.get("subject");assertEquals("OPPORTUNITY",subject.get("subjectType"));
        assertEquals(current.subject().revision(),((Number)subject.get("subjectRevision")).longValue());
        assertFalse(card.toString().contains(current.subject().id().toString()));assertFalse(card.toString().contains(sourceLead.id().toString()));
        assertEquals(Map.of(),((Map<?,?>)card.get("commandForm")).get("values"));
        var mine=(Map<?,?>)((List<?>)response.body().get("myTasks")).getFirst();
        assertEquals(PublicFactReferences.reference(seed.request().actor(),"opportunity.opportunity",current.subject().id()),mine.get("subjectFactRef"));
        assertEquals(subject.get("title"),mine.get("subjectTitle"));assertEquals(before+6,auditCount());
        assertEquals("6",scalar("select count(*) from audit.audit_entry_classified_v where tenant_id=? and summary_schema_code='R2_CURRENT_WORKCARD_DISCLOSURE_AUDIT_V1'",seed.tenant()));
        var repeated=read(response.etag(),current.selector().id());assertEquals(304,repeated.status());assertNull(repeated.body());assertEquals(before+12,auditCount());
        try(var c=database.apiConnection()) {
            var legacy=new CurrentWorkCardDisclosureService(protection,policies,"R1_CARD_IT").read(c,seed.request().actor(),UUID.randomUUID(),null);
            assertEquals(200,legacy.status());assertNull(legacy.body().get("currentCard"));
        }
    }
    @Test void source_lead_deny_removes_opportunity_card_and_task_summary()throws Exception {
        setupOpportunity();var initial=read(null,null);assertNotNull(initial.body().get("currentCard"));
        deny(sourceLead,"SALES_OPPORTUNITY_OWNER");
        var hidden=read(initial.etag(),current.selector().id());assertEquals(200,hidden.status());
        assertNull(hidden.body().get("currentCard"));assertEquals(List.of(),hidden.body().get("myTasks"));
        assertNotNull(hidden.body().get("selectionNotice"));assertFalse(hidden.body().toString().contains(current.selector().id().toString()));
    }
    @Test void selecting_r1_task_keeps_opportunity_recommendation_and_its_typed_summary()throws Exception {
        setupOpportunity();
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"LEAD_INGRESS_COMPLETE");return null;});}
        var second=addTask(UUID.randomUUID(),Instant.now(),Instant.now().plusSeconds(86400),"OPEN");
        var response=read(null,second.selector().id());assertEquals(200,response.status(),response.errorCode());
        assertEquals(second.selector().id().toString(),((Map<?,?>)response.body().get("currentCard")).get("taskId"));
        assertEquals(current.selector().id().toString(),response.body().get("recommendedTaskId"));
        var mine=(List<?>)response.body().get("myTasks");assertEquals(2,mine.size());
        var opportunity=(Map<?,?>)mine.getFirst();assertEquals("PROGRESS_OPPORTUNITY",((Map<?,?>)opportunity.get("businessPurpose")).get("code"));
        assertEquals(PublicFactReferences.reference(seed.request().actor(),"opportunity.opportunity",current.subject().id()),opportunity.get("subjectFactRef"));
        deny(current.subject(),"SALES_OPPORTUNITY_OWNER");
        var hidden=read(null,current.selector().id());assertEquals(second.selector().id().toString(),hidden.body().get("recommendedTaskId"));
        assertEquals(1,((List<?>)hidden.body().get("myTasks")).size());assertNotNull(hidden.body().get("selectionNotice"));
    }
    @Test void cancelled_task_is_not_disclosed_or_counted_as_waiting()throws Exception {
        setupOpportunity();cancelCurrent();
        var hidden=read(null,current.selector().id());assertEquals(200,hidden.status(),hidden.errorCode());
        assertNull(hidden.body().get("currentCard"));assertEquals(List.of(),hidden.body().get("myTasks"));assertEquals(0,hidden.body().get("waitingCount"));
    }
    @Test void draft_is_authorized_and_read_without_requiring_a_future_followup_now()throws Exception {
        setupOpportunity();
        var values=Map.<String,Object>of("progressTypeCode","PHONE_CONNECTED","progressSummary","Confirmed client scope","occurredAt",businessAt.toString(),"nextCheckAt",businessAt.plusSeconds(86400).toString());
        ActionDraftService.Draft draft;
        try(var c=database.apiConnection()){draft=inTransaction(c,Capability.COMMAND,x->ActionDraftService.databaseBacked().save(x,seed.tenant(),current,null,values,seed.appointment(),businessAt).draft());}
        var response=read(null,null);assertEquals(200,response.status(),response.errorCode());
        var card=(Map<?,?>)response.body().get("currentCard");assertEquals(values,((Map<?,?>)card.get("actionDraft")).get("values"));
        assertEquals(values,((Map<?,?>)card.get("commandForm")).get("values"));
        deny(draft.selector(),"SALES_OPPORTUNITY_OWNER");
        assertNull(read(null,null).body().get("currentCard"));
    }
    @Test void completed_task_is_not_offered_after_progress_confirmation()throws Exception {
        setupOpportunity();
        var values=Map.<String,Object>of("progressTypeCode","MEETING","progressSummary","Confirmed next meeting","occurredAt",businessAt.toString(),"nextCheckAt",Instant.now().plusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString());
        try(var http=new HttpHarness()) {
            String base="/api/v1/tasks/"+current.selector().id();
            var saved=http.request("PUT",base+"/opportunity-progress-draft",Map.of("actionCode","RECORD_OPPORTUNITY_PROGRESS","schemaVersion",1,"values",values),Map.of("Idempotency-Key",UUID.randomUUID().toString(),"If-None-Match","*"));assertEquals(201,saved.statusCode(),saved.body());
            var body=http.body(saved);var draft=(Map<?,?>)body.get("draft");var tags=(Map<?,?>)body.get("preconditions");
            var confirm=new TreeMap<String,Object>(values);confirm.put("draftId",draft.get("draftId"));confirm.put("expectedDraftRevision",draft.get("draftRevision"));confirm.put("draftDigest",draft.get("digest"));
            var done=http.request("POST",base+"/commands/record-opportunity-progress",confirm,Map.of("Idempotency-Key",UUID.randomUUID().toString(),"If-Match",(String)tags.get("taskETag")));assertEquals(200,done.statusCode(),done.body());
        }
        var waiting=read(null,current.selector().id());assertEquals(200,waiting.status(),waiting.errorCode());
        assertNull(waiting.body().get("currentCard"));assertEquals(1,waiting.body().get("waitingCount"));assertEquals(List.of(),waiting.body().get("myTasks"));
        try(var c=database.apiConnection()){
            inTransaction(c,Capability.COMMAND,x->{grant(x,"TEAM_TASK_READ");return null;});
            var service=new R2TeamManagementReadService(new byte[32],protection,opportunityProtection,io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked("TEAM_PROGRESS_HISTORY"));
            var detail=service.detail(c,seed.request().actor(),"history",current.selector().id());
            var fields=(List<?>)detail.get("facts");assertTrue(fields.contains(List.of("原因或说明","Confirmed next meeting")),"History must use the exact confirmed progress, not the new waiting task");
            var actor=inTransaction(c,Capability.QUERY,x->io.github.windyzhu3.ontologylaw.identity.WorkcardOwnerReader.databaseBacked().read(x,seed.tenant(),seed.appointment()));
            assertTrue(fields.contains(List.of("当时确认人",actor.principal().displayName()+" · "+actor.organization().displayName())));
            var originalTime=fields.stream().map(v->(List<?>)v).filter(v->v.get(0).equals("处理时间")).findFirst().orElseThrow();assertDoesNotThrow(()->java.time.Instant.parse((String)originalTime.get(1)));assertNull(detail.get("action"));
            var completion=inTransaction(c,Capability.QUERY,x->CurrentTaskReader.databaseBacked().read(x,seed.tenant(),current.selector().id()).completion());
            deny(completion,"TEAM_TASK_READ");
            var bomb=new OpportunityProgressProtection(){public byte[] encrypt(UUID t,UUID o,UUID p,String body){throw new AssertionError();}public String decrypt(UUID t,UUID o,UUID p,byte[] body){throw new AssertionError("Denied progress history was decrypted");}};
            var denied=new R2TeamManagementReadService(new byte[32],protection,bomb,io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked("TEAM_PROGRESS_DENY"));
            assertThrows(io.github.windyzhu3.ontologylaw.execution.R1ServiceReadRuntime.Failure.class,()->denied.detail(c,seed.request().actor(),"history",current.selector().id()));
        }
    }
    @Test void guarded_due_reopening_returns_followup_to_public_workcard_with_fresh_draft()throws Exception {
        setupOpportunity();OpportunityProgressService.Result progress;
        var due=businessAt.plusSeconds(86400);
        try(var c=database.apiConnection()){progress=inTransaction(c,Capability.COMMAND,x->R2OpportunityProgressServices.create(opportunityProtection)
                .record(x,seed.request().actor(),current.subject(),current.selector(),new OpportunityProgressInput("MEETING","已约定再次沟通",businessAt,due),ZoneId.of("Asia/Shanghai"),businessAt.plusSeconds(60)));}
        assertNull(read(null,null).body().get("currentCard"));
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            var wait=EventResponsibilityReader.databaseBacked().latestWait(x,seed.tenant(),progress.nextTask().id());
            R2OpportunityFollowupServices.create(opportunityProtection).reopen(x,seed.tenant(),current.subject(),progress.nextTask(),wait.selector(),progress.progress(),due);return null;
        });}
        try(var http=new HttpHarness()){
            var response=http.request("GET","/api/v1/workcards/current",null,Map.of());assertEquals(200,response.statusCode(),response.body());
            var body=http.body(response);assertFalse(body.containsKey("selectionNotice"),"Absent optional text must remain absent over real HTTP");var card=(Map<?,?>)body.get("currentCard");assertNotNull(card);
            assertEquals(progress.nextTask().id().toString(),card.get("taskId"));assertEquals("PROGRESS_OPPORTUNITY",card.get("taskType"));
            assertNull(card.get("actionDraft"));assertEquals(Map.of(),((Map<?,?>)card.get("commandForm")).get("values"));assertEquals(0,body.get("waitingCount"));
        }
    }
}
