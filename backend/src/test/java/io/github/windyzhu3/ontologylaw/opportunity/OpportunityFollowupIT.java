package io.github.windyzhu3.ontologylaw.opportunity;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor;
import io.github.windyzhu3.ontologylaw.lead.ContactFlowFixture;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.time.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class OpportunityFollowupIT extends ContactFlowFixture {
    private final ZoneId zone=ZoneId.of("Asia/Shanghai");
    private final OpportunityProgressProtection cipher=OpportunityProgressProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES"));
    private Subject opportunity,progress,wait,next;
    private Instant due;
    private void setupFollowup(boolean future)throws Exception {
        setupContact();var contact=execute(prepare(contact("CONNECTED_VALID")));
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            opportunity=EventOpportunityReader.databaseBacked().forContact(x,seed.tenant(),contact.resultFact().id()).selector();
            grant(x,"SALES_OPPORTUNITY_OWNER");
            var first=TaskFactory.databaseBacked().createInitialOpportunity(x,seed.tenant(),seed.appointment(),opportunity,zone,businessAt);
            due=future?Instant.now().plusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.MICROS):businessAt.plusSeconds(86400);
            var result=io.github.windyzhu3.ontologylaw.api.R2OpportunityProgressServices.create(cipher).record(x,seed.request().actor(),opportunity,first.selector(),new OpportunityProgressInput("PHONE_CONNECTED","确认后续沟通",businessAt,due),zone,businessAt.plusSeconds(60));
            progress=result.progress();next=result.nextTask();wait=EventResponsibilityReader.databaseBacked().latestWait(x,seed.tenant(),next.id()).selector();return null;
        });}
    }
    private Subject reopen()throws Exception {
        try(var c=database.apiConnection()){return inTransaction(c,Capability.COMMAND,x->io.github.windyzhu3.ontologylaw.api.R2OpportunityFollowupServices.create(cipher).reopen(x,seed.tenant(),opportunity,next,wait,progress,due));}
    }
    @Test void due_followup_reopens_same_task_without_rewriting_sla_or_creating_another()throws Exception {
        setupFollowup(false);String sla=scalar("select original_sla_due_at from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),next.id());
        var result=reopen();assertEquals(next.id(),result.id());assertEquals(next.revision()+1,result.revision());
        assertEquals("OPEN",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),next.id()));
        assertEquals(sla,scalar("select original_sla_due_at from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),next.id()));
        assertEquals("STALE_TASK",assertThrows(CommandHandler.Rejected.class,this::reopen).code());
        assertEquals("2",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_type='opportunity.opportunity'",seed.tenant()));
    }
    @Test void future_followup_cannot_be_woken_by_future_client_cutoff()throws Exception {
        setupFollowup(true);assertEquals("STALE_TASK",assertThrows(CommandHandler.Rejected.class,this::reopen).code());
        assertEquals("WAITING",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),next.id()));
    }
    @Test void wrong_wait_identity_or_progress_digest_does_not_reopen()throws Exception {
        setupFollowup(false);var originalWait=wait;wait=new Subject(wait.type(),UUID.randomUUID(),null,wait.hash());assertEquals("STALE_TASK",assertThrows(CommandHandler.Rejected.class,this::reopen).code());wait=originalWait;
        progress=new Subject(progress.type(),progress.id(),null,"A".repeat(43));assertEquals("STALE_PROGRESS",assertThrows(OpportunityProgressService.Blocked.class,this::reopen).code());
        assertEquals("WAITING",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),next.id()));
    }
    @Test void revoked_current_owner_cannot_receive_reopened_task()throws Exception {
        setupFollowup(false);mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and grantee_appointment_id=? and authority_code='SALES_OPPORTUNITY_OWNER'",seed.tenant(),seed.appointment());
        assertEquals("FORBIDDEN",assertThrows(OpportunityProgressService.Blocked.class,this::reopen).code());
        assertEquals("WAITING",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),next.id()));
    }
    @Test void outer_failure_rolls_back_reopening()throws Exception {
        setupFollowup(false);try(var c=database.apiConnection()){assertThrows(IllegalStateException.class,()->inTransaction(c,Capability.COMMAND,x->{io.github.windyzhu3.ontologylaw.api.R2OpportunityFollowupServices.create(cipher).reopen(x,seed.tenant(),opportunity,next,wait,progress,due);throw new IllegalStateException("rollback");}));}
        assertEquals("WAITING",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),next.id()));assertNotNull(reopen());
    }
    @Test void task_object_deny_blocks_wakeup_even_with_opportunity_grant()throws Exception {
        setupFollowup(false);deny(next,"SALES_OPPORTUNITY_OWNER");
        assertEquals("FORBIDDEN",assertThrows(OpportunityProgressService.Blocked.class,this::reopen).code());
        assertEquals("WAITING",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),next.id()));
    }
    @Test void generic_r1_reopen_cannot_bypass_opportunity_due_guard()throws Exception {
        setupFollowup(true);try(var c=database.apiConnection()){assertThrows(IllegalArgumentException.class,()->inTransaction(c,Capability.COMMAND,x->{var tasks=TaskFactory.databaseBacked();return tasks.reopen(x,seed.tenant(),tasks.read(x,seed.tenant(),next.id()));}));}
    }
    @Test void concurrent_recovery_only_advances_revision_once()throws Exception {
        setupFollowup(false);
        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(2)){
            java.util.concurrent.Callable<String> attempt=()->{try{reopen();return "OPEN";}catch(CommandHandler.Rejected rejected){return rejected.code();}};
            var outcomes=new ArrayList<String>();for(var result:workers.invokeAll(List.of(attempt,attempt)))outcomes.add(result.get());
            Collections.sort(outcomes);assertEquals(List.of("OPEN","STALE_TASK"),outcomes);
        }
        assertEquals(Long.toString(next.revision()+1),scalar("select revision from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),next.id()));
    }
    @Test void transferred_due_wait_reopens_for_effective_owner_and_new_owner_records_real_progress()throws Exception {
        setupFollowup(false);var oldTask=next;var oldWait=wait;UUID principal=UUID.randomUUID(),receiver=UUID.randomUUID();
        mutate("insert into identity.principal (tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) values (?,?,'HUMAN','FIXTURE',?,'Successor fixture','ACTIVE',clock_timestamp())",seed.tenant(),principal,CanonicalJson.digest(principal.toString()));
        mutate("insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'OWNER',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),receiver,principal,seed.org());
        mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,'SALES_OPPORTUNITY_OWNER',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),receiver,seed.appointment(),seed.org());
        mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and grantee_appointment_id=? and authority_code='SALES_OPPORTUNITY_OWNER'",seed.tenant(),seed.appointment());
        var exceptions=OpportunityOwnerExceptionService.databaseBacked((c,t,o,r,now)->new OpportunityOwnerExceptionService.Observation(Set.of(OpportunityOwnerExceptionService.Reason.OWNER_AUTHORITY_MISSING),oldTask,oldWait,null),
                (c,t,o,appointment,task,receipt)->{
                    var qualification=io.github.windyzhu3.ontologylaw.identity.OpportunityOwnerExceptionAuthorityReader.databaseBacked().receiver(c,t,appointment,seed.org(),List.of(o,task,receipt,progress),TaskFactory.databaseBacked().now(c));
                    if(!qualification.authorized())throw new java.sql.SQLException("Receiver not authorized");
                },(c,t,h,o,r,task,receipt,appointment,newTask,actor,businessZone)->{
                    var result=TaskFactory.databaseBacked().handoffOpportunityTask(c,t,h,o,r.basis(),task,receipt,appointment,newTask,actor,businessZone);
                    return new OpportunityOwnerExceptionService.TaskHandoffResult(result.task().selector(),result.originalDueAt(),result.originalWait(),result.newWait());
                });
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            var exception=exceptions.observe(x,seed.tenant(),opportunity).orElseThrow();
            var decision=new OpportunityOwnerExceptionService.Decision(exception.selector(),opportunity,opportunity,oldTask,oldWait,seed.appointment(),"Transfer to existing qualified successor");
            var result=exceptions.transfer(x,seed.tenant(),decision,receiver,zone);next=result.newTask();wait=EventResponsibilityReader.databaseBacked().latestWait(x,seed.tenant(),next.id()).selector();return null;});}
        assertEquals("CANCELLED",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),oldTask.id()));
        var reopened=reopen();assertEquals(next.id(),reopened.id());
        var actor=new Actor(seed.tenant(),principal,receiver,null,null);
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            var now=TaskFactory.databaseBacked().now(x);var result=io.github.windyzhu3.ontologylaw.api.R2OpportunityProgressServices.create(cipher).record(x,actor,opportunity,reopened,
                    new OpportunityProgressInput("PHONE_CONNECTED","New owner personally confirmed progress",now,now.plusSeconds(86400)),zone,now);
            assertEquals(receiver,TaskFactory.databaseBacked().read(x,seed.tenant(),result.nextTask().id()).owner());return null;});}
        assertEquals(seed.appointment().toString(),scalar("select owner_appointment_id from opportunity.opportunity where tenant_id=? and opportunity_id=?",seed.tenant(),opportunity.id()));
        assertEquals("2",scalar("select count(*) from opportunity.opportunity_progress where tenant_id=?",seed.tenant()));
    }
}
