package io.github.windyzhu3.ontologylaw.opportunity;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import io.github.windyzhu3.ontologylaw.lead.ContactFlowFixture;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.time.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class OpportunityProgressIT extends ContactFlowFixture {
    private final ZoneId zone=ZoneId.of("Asia/Shanghai");
    private final OpportunityProgressProtection cipher=OpportunityProgressProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES"));
    private Subject opportunity;
    private TaskFactory.Task first;
    private OpportunityProgressInput progress(){return new OpportunityProgressInput("PHONE_CONNECTED","已确认服务范围，约定补交材料",businessAt,businessAt.plusSeconds(86400));}
    private void setupProgress()throws Exception{
        setupContact();var contact=execute(prepare(contact("CONNECTED_VALID")));assertEquals(CommandOutcome.Status.SUCCEEDED,contact.status());
        try(var c=database.apiConnection()){
            inTransaction(c,Capability.COMMAND,x->{
                opportunity=EventOpportunityReader.databaseBacked().forContact(x,seed.tenant(),contact.resultFact().id()).selector();
                grant(x,"SALES_OPPORTUNITY_OWNER");
                first=TaskFactory.databaseBacked().createInitialOpportunity(x,seed.tenant(),seed.appointment(),opportunity,zone,businessAt);
                return null;
            });
        }
    }
    private OpportunityProgressService.Result record()throws Exception{
        try(var c=database.apiConnection()){return inTransaction(c,Capability.COMMAND,x->io.github.windyzhu3.ontologylaw.api.R2OpportunityProgressServices.create(cipher)
            .record(x,seed.request().actor(),opportunity,first.selector(),progress(),zone,businessAt.plusSeconds(60)));}
    }
    @Test void r1_contact_progress_completes_one_task_and_preserves_a_distinct_waiting_successor()throws Exception{
        setupProgress();var result=record();
        assertNotEquals(first.selector().id(),result.nextTask().id());
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
            var reader=OpportunityCommandReader.databaseBacked(cipher);
            assertEquals(opportunity,reader.header(x,seed.tenant(),opportunity.id()).selector());
            var persisted=reader.progress(x,seed.tenant(),result.progress().id());
            assertEquals(result.progress(),persisted.selector());assertEquals(opportunity.id(),persisted.opportunity());
            assertTrue(persisted.canonicalBody().contains(progress().summary()));
            assertFalse(persisted.toString().contains(progress().summary()));
            assertNull(reader.progress(x,UUID.randomUUID(),result.progress().id()));
            byte[] wrongKey=new byte[32];wrongKey[0]=1;
            assertThrows(java.sql.SQLException.class,()->OpportunityCommandReader.databaseBacked(OpportunityProgressProtection.aesGcm(t->new SecretKeySpec(wrongKey,"AES"))).progress(x,seed.tenant(),result.progress().id()));
            var wrongBody=new OpportunityProgressProtection(){
                public byte[] encrypt(UUID t,UUID o,UUID p,String b){throw new AssertionError();}
                public String decrypt(UUID t,UUID o,UUID p,byte[] b){return "{}";}
            };
            assertThrows(java.sql.SQLException.class,()->OpportunityCommandReader.databaseBacked(wrongBody).progress(x,seed.tenant(),result.progress().id()));
            var tasks=TaskFactory.databaseBacked();var done=tasks.read(x,seed.tenant(),first.selector().id());
            assertEquals("DONE",done.state());assertEquals(result.progress(),done.completion());
            var next=tasks.read(x,seed.tenant(),result.nextTask().id());assertEquals("WAITING",next.state());assertEquals(opportunity,next.subject());
            var originalView=CurrentTaskReader.databaseBacked().read(x,seed.tenant(),first.selector().id());
            var nextView=CurrentTaskReader.databaseBacked().read(x,seed.tenant(),result.nextTask().id());
            assertEquals(R1BusinessTime.due(businessAt,14400,zone),originalView.slaDueAt());
            assertEquals(R1BusinessTime.due(progress().nextCheckAt(),14400,zone),nextView.slaDueAt());
            var wait=EventResponsibilityReader.databaseBacked().latestWait(x,seed.tenant(),next.selector().id());
            assertEquals(progress().nextCheckAt(),wait.resumeDue());assertEquals("R2_OPPORTUNITY_FOLLOWUP_V1",wait.profile());
            try(var p=x.prepareStatement("select progress_body_ciphertext from opportunity.opportunity_progress where tenant_id=? and opportunity_progress_id=?")){
                p.setObject(1,seed.tenant());p.setObject(2,result.progress().id());try(var r=p.executeQuery()){assertTrue(r.next());
                    String body=cipher.decrypt(seed.tenant(),opportunity.id(),result.progress().id(),r.getBytes(1));assertTrue(body.contains(progress().summary()));
                    assertEquals(result.progress().hash(),Base64.getUrlEncoder().withoutPadding().encodeToString(CanonicalJson.digest(body)));
                }
            }
            return null;
        });}
        assertThrows(OpportunityProgressService.Blocked.class,this::record);
        assertEquals("1",scalar("select count(*) from opportunity.opportunity_progress where tenant_id=?",seed.tenant()));
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            assertEquals(first.selector().id(),TaskFactory.databaseBacked().createInitialOpportunity(x,seed.tenant(),seed.appointment(),opportunity,zone,businessAt).selector().id());return null;
        });}
    }
    @Test void invalid_next_time_does_not_close_original_task()throws Exception{
        setupProgress();try(var c=database.apiConnection()){
            assertThrows(IllegalArgumentException.class,()->inTransaction(c,Capability.COMMAND,x->io.github.windyzhu3.ontologylaw.api.R2OpportunityProgressServices.create(cipher)
                .record(x,seed.request().actor(),opportunity,first.selector(),new OpportunityProgressInput("MEETING","有效沟通",businessAt,businessAt),zone,businessAt)));
        }
        assertEquals("OPEN",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),first.selector().id()));
        assertEquals("0",scalar("select count(*) from opportunity.opportunity_progress where tenant_id=?",seed.tenant()));
    }
    @Test void outer_transaction_failure_rolls_back_fact_completion_and_successor()throws Exception{
        setupProgress();try(var c=database.apiConnection()){
            assertThrows(IllegalStateException.class,()->inTransaction(c,Capability.COMMAND,x->{
                io.github.windyzhu3.ontologylaw.api.R2OpportunityProgressServices.create(cipher).record(x,seed.request().actor(),opportunity,first.selector(),progress(),zone,businessAt.plusSeconds(60));
                throw new IllegalStateException("Fixture failure after all business writes");
            }));
        }
        assertEquals("0",scalar("select count(*) from opportunity.opportunity_progress where tenant_id=?",seed.tenant()));
        assertEquals("OPEN",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),first.selector().id()));
    }
    @Test void revoked_sales_permission_prevents_progress_and_leaves_original_responsibility()throws Exception{
        setupProgress();
        mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and grantee_appointment_id=? and authority_code='SALES_OPPORTUNITY_OWNER'",seed.tenant(),seed.appointment());
        assertEquals("FORBIDDEN",assertThrows(OpportunityProgressService.Blocked.class,this::record).code());
        assertEquals("0",scalar("select count(*) from opportunity.opportunity_progress where tenant_id=?",seed.tenant()));
        assertEquals("OPEN",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),first.selector().id()));
    }
    @Test void continuation_retry_keeps_same_wait_and_first_identity_after_multiple_progress_records()throws Exception{
        setupProgress();var initial=first;var result=record();
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            var tasks=TaskFactory.databaseBacked();var done=tasks.read(x,seed.tenant(),initial.selector().id());
            assertEquals(result.nextTask(),tasks.createOpportunityFollowup(x,seed.tenant(),done,result.progress(),zone,businessAt.plusSeconds(60),progress().nextCheckAt()).selector());
            assertEquals(result.nextTask(),tasks.createOpportunityFollowup(x,seed.tenant(),done,result.progress(),zone,progress().nextCheckAt().plusSeconds(60),progress().nextCheckAt()).selector());
            // Use the guarded business recovery; production worker dispatch remains separate.
            var wait=EventResponsibilityReader.databaseBacked().latestWait(x,seed.tenant(),result.nextTask().id());
            io.github.windyzhu3.ontologylaw.api.R2OpportunityFollowupServices.create(cipher).reopen(x,seed.tenant(),opportunity,result.nextTask(),wait.selector(),result.progress(),progress().nextCheckAt());
            first=tasks.read(x,seed.tenant(),result.nextTask().id());return null;
        });}
        businessAt=businessAt.plusSeconds(86400);var second=record();
        assertNotEquals(result.nextTask().id(),second.nextTask().id());
        assertEquals("2",scalar("select count(*) from opportunity.opportunity_progress where tenant_id=?",seed.tenant()));
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            assertEquals(initial.selector().id(),TaskFactory.databaseBacked().createInitialOpportunity(x,seed.tenant(),seed.appointment(),opportunity,zone,businessAt).selector().id());return null;
        });}
        assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and predecessor_task_occurrence_id=?",seed.tenant(),initial.selector().id()));
    }
    @Test void concurrent_confirmation_only_appends_one_progress_and_one_successor()throws Exception{
        setupProgress();
        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(2)){
            java.util.concurrent.Callable<String> attempt=()->{try{record();return "SAVED";}catch(OpportunityProgressService.Blocked blocked){return blocked.code();}};
            var results=workers.invokeAll(List.of(attempt,attempt));
            var outcomes=new ArrayList<String>();for(var result:results)outcomes.add(result.get());
            Collections.sort(outcomes);assertEquals(List.of("SAVED","STALE_TASK"),outcomes);
        }
        assertEquals("1",scalar("select count(*) from opportunity.opportunity_progress where tenant_id=?",seed.tenant()));
        assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and predecessor_task_occurrence_id=?",seed.tenant(),first.selector().id()));
    }
}
