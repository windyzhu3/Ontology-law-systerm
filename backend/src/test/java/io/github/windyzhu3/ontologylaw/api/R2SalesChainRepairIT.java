package io.github.windyzhu3.ontologylaw.api;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import io.github.windyzhu3.ontologylaw.contract.OpportunityContractReader;
import java.util.*;
import java.time.*;
import org.junit.jupiter.api.Test;

/** Real contract preparation fixture plus synthetic pre-fix extra ordinary task. */
class R2SalesChainRepairIT extends R2ContractTerminationRuntimeIT {
    @Test void repaired_takeover_exception_resolves_through_audited_observation_and_replays()throws Exception {
        historicalExtra();authorize(actor(),"OPPORTUNITY_OWNER_EXCEPTION_READ");authorize(actor(),"OPPORTUNITY_OWNER_EXCEPTION_RESOLVE");
        var observer=service("OPPORTUNITY_OWNER_EXCEPTION_DISCOVER");
        var first=new CommandEnvelope(CommandEnvelope.Type.OBSERVE_OPPORTUNITY_OWNER_EXCEPTION,UUID.randomUUID(),UUID.randomUUID(),observer,Map.of("opportunityId",opportunity.id().toString(),"expectedOpportunityRevision",opportunity.revision()));
        var before=run(first);assertEquals(CommandOutcome.Status.SUCCEEDED,before.status(),before.rejectionCode());
        assertEquals(CommandOutcome.Status.SUCCEEDED,run(repair()).status());
        var next=new CommandEnvelope(first.type(),UUID.randomUUID(),UUID.randomUUID(),observer,first.payload());
        var resolved=run(next);assertEquals(CommandOutcome.Status.SUCCEEDED,resolved.status(),resolved.rejectionCode());assertEquals(resolved,run(next));
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
            var old=R2OpportunityOwnerExceptionAssembly.service().read(x,seed.tenant(),before.resultFact());
            var current=R2OpportunityOwnerExceptionAssembly.service().read(x,seed.tenant(),resolved.resultFact());
            assertEquals(io.github.windyzhu3.ontologylaw.opportunity.OpportunityOwnerExceptionService.State.ACTIVE,old.state());
            assertEquals(io.github.windyzhu3.ontologylaw.opportunity.OpportunityOwnerExceptionService.State.RESOLVED,current.state());
            assertEquals("OWNER_VALIDATED",current.resolutionKind());assertEquals("audit.audit_entry",current.resolution().type());assertNotNull(current.resolution().hash());return null;
        });}
    }
    @Test void takeover_still_checks_owner_authority_and_cannot_recreate_ordinary_work()throws Exception {
        start();
        mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and grantee_appointment_id=? and authority_code='SALES_OPPORTUNITY_OWNER'",seed.tenant(),seed.appointment());
        io.github.windyzhu3.ontologylaw.opportunity.OpportunityOwnerExceptionService.Snapshot snapshot;
        try(var c=database.apiConnection()){snapshot=inTransaction(c,Capability.COMMAND,x->R2OpportunityOwnerExceptionAssembly.service().observe(x,seed.tenant(),opportunity).orElseThrow());}
        assertTrue(snapshot.reasons().contains(io.github.windyzhu3.ontologylaw.opportunity.OpportunityOwnerExceptionService.Reason.OWNER_AUTHORITY_MISSING));
        assertFalse(snapshot.reasons().contains(io.github.windyzhu3.ontologylaw.opportunity.OpportunityOwnerExceptionService.Reason.SOURCE_INCONSISTENT));
        var receiver=supervisor();authorize(receiver,"SALES_OPPORTUNITY_OWNER");
        var decision=new io.github.windyzhu3.ontologylaw.opportunity.OpportunityOwnerExceptionService.Decision(snapshot.selector(),opportunity,snapshot.responsibility().basis(),null,null,seed.appointment(),"不能重建普通跟进");
        try(var c=database.apiConnection()){
            var failure=assertThrows(java.sql.SQLException.class,()->inTransaction(c,Capability.COMMAND,x->R2OpportunityOwnerExceptionAssembly.service().transfer(x,seed.tenant(),decision,receiver.appointmentId(),ZoneId.of("Asia/Shanghai"))));
            assertEquals("40001",failure.getSQLState());
        }
        assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and business_purpose_code='PROGRESS_OPPORTUNITY' and state in ('OPEN','WAITING')",seed.tenant(),opportunity.id()));
        assertEquals("0",scalar("select count(*) from opportunity.responsibility_handoff where tenant_id=?",seed.tenant()));
    }
    @Test void contract_takeover_uses_its_exact_facts_without_a_false_ordinary_lineage_error()throws Exception {
        start();
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
            var owner=io.github.windyzhu3.ontologylaw.opportunity.OpportunityResponsibilityReader.databaseBacked().current(x,seed.tenant(),opportunity);
            var state=R2OpportunityOwnerExceptionAssembly.taskState(x,seed.tenant(),opportunity,owner);
            assertTrue(state.lineageValid(),"A legitimate contract responsibility must not be treated as a broken ordinary task");
            assertNull(state.task());
            var takeover=OpportunityContractReader.databaseBacked().takeoverFacts(x,seed.tenant(),opportunity.id());
            assertFalse(takeover.isEmpty());assertTrue(state.protectedSources().containsAll(takeover));
            return null;
        });}
    }
    @Test void contract_takeover_does_not_hide_an_extra_ordinary_task()throws Exception {
        historicalExtra();
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
            var owner=io.github.windyzhu3.ontologylaw.opportunity.OpportunityResponsibilityReader.databaseBacked().current(x,seed.tenant(),opportunity);
            assertFalse(R2OpportunityOwnerExceptionAssembly.taskState(x,seed.tenant(),opportunity,owner).lineageValid());return null;
        });}
        assertEquals(CommandOutcome.Status.SUCCEEDED,run(repair()).status());
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
            var owner=io.github.windyzhu3.ontologylaw.opportunity.OpportunityResponsibilityReader.databaseBacked().current(x,seed.tenant(),opportunity);
            assertTrue(R2OpportunityOwnerExceptionAssembly.taskState(x,seed.tenant(),opportunity,owner).lineageValid());return null;
        });}
    }
    private TaskFactory.Task ordinary;
    private void historicalExtra()throws Exception {
        start();UUID id=UUID.randomUUID();
        // Historical fixture only: reproduce the pre-F02 erroneous ordinary task. Production creation guards stay enabled.
        mutate("insert into responsibility.task_occurrence(tenant_id,task_occurrence_id,owner_appointment_id,business_purpose_code,primary_command_code,expected_completion_fact_type,original_sla_code,original_sla_seconds,original_sla_due_at,state,created_at,subject_type,subject_id,subject_revision) values(?,?,?,'PROGRESS_OPPORTUNITY','RECORD_OPPORTUNITY_PROGRESS','opportunity.opportunity_progress','R2_BUSINESS_4H_V1',14400,clock_timestamp()+interval '4 hours','OPEN',clock_timestamp(),'opportunity.opportunity',?,?)",seed.tenant(),id,seed.appointment(),opportunity.id(),opportunity.revision());
        try(var c=database.apiConnection()){ordinary=inTransaction(c,Capability.QUERY,x->TaskFactory.databaseBacked().read(x,seed.tenant(),id));}
    }
    private CommandEnvelope repair()throws Exception {
        var body=new TreeMap<String,Object>();body.put("opportunity",CommandScope.selector(opportunity));body.put("task",CommandScope.selector(ordinary.selector()));body.put("ownerAppointmentId",ordinary.owner().toString());body.put("draft",null);
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{body.put("basis",CommandScope.selector(io.github.windyzhu3.ontologylaw.opportunity.OpportunityResponsibilityReader.databaseBacked().current(x,seed.tenant(),opportunity).basis()));body.put("takeoverFacts",OpportunityContractReader.databaseBacked().takeoverFacts(x,seed.tenant(),opportunity.id()).stream().map(CommandScope::selector).toList());return null;});}
        return new CommandEnvelope(CommandEnvelope.Type.valueOf("REPAIR_SUPERSEDED_OPPORTUNITY_TASK"),UUID.randomUUID(),UUID.randomUUID(),service("OPPORTUNITY_TASK_RECOVER"),body);
    }
    @Test void repair_cancels_only_wrong_ordinary_task_and_replays_exact_receipt()throws Exception {
        historicalExtra();var preserved=scalar("select string_agg(task_occurrence_id::text||':'||revision||':'||state,',' order by task_occurrence_id) from responsibility.task_occurrence where tenant_id=? and subject_id=? and business_purpose_code<>'PROGRESS_OPPORTUNITY'",seed.tenant(),opportunity.id());
        var command=repair();var receipt=run(command);assertEquals(CommandOutcome.Status.SUCCEEDED,receipt.status(),receipt.rejectionCode());assertEquals(receipt,run(command));
        assertEquals("CANCELLED",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),ordinary.selector().id()));
        assertEquals(preserved,scalar("select string_agg(task_occurrence_id::text||':'||revision||':'||state,',' order by task_occurrence_id) from responsibility.task_occurrence where tenant_id=? and subject_id=? and business_purpose_code<>'PROGRESS_OPPORTUNITY'",seed.tenant(),opportunity.id()));
        assertEquals("0",scalar("select count(*) from opportunity.opportunity_progress where tenant_id=?",seed.tenant()));
    }
    @Test void repair_skips_a_saved_draft_without_erasing_it()throws Exception {
        historicalExtra();var command=repair();
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{ActionDraftService.databaseBacked().save(x,seed.tenant(),ordinary,null,Map.of("summary","Synthetic in-progress draft"),seed.appointment(),TaskFactory.databaseBacked().now(x));return null;});}
        assertEquals("STALE_DRAFT",assertThrows(CommandHandler.Rejected.class,()->run(command)).code());
        assertEquals("OPEN",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),ordinary.selector().id()));
        assertEquals("DRAFT",scalar("select state from responsibility.action_draft where tenant_id=? and task_occurrence_id=?",seed.tenant(),ordinary.selector().id()));
    }
    @Test void repair_rejects_an_incorrect_downstream_selector()throws Exception {
        historicalExtra();var original=repair();var p=new TreeMap<String,Object>((Map<String,Object>)original.payload());p.put("takeoverFacts",List.of(CommandScope.selector(new Subject("contract.contract",UUID.randomUUID(),0L,null))));
        var wrong=new CommandEnvelope(original.type(),UUID.randomUUID(),UUID.randomUUID(),original.actor(),p);
        assertEquals("STALE_SUBJECT",assertThrows(CommandHandler.Rejected.class,()->run(wrong)).code());
        assertEquals("OPEN",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),ordinary.selector().id()));
    }
    @Test void repair_waiting_for_user_transaction_observes_new_draft_and_skips()throws Exception {
        historicalExtra();var command=repair();var started=new java.util.concurrent.CountDownLatch(1);var pid=new java.util.concurrent.atomic.AtomicInteger();
        var future=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<String>>();
        try(var pool=java.util.concurrent.Executors.newSingleThreadExecutor()){
            try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
                io.github.windyzhu3.ontologylaw.opportunity.OpportunityCommandReader.databaseBacked(cipher).lock(x,seed.tenant(),opportunity.id());
                future.set(pool.submit(()->{try(var next=database.apiConnection()){
                    try(var ps=next.prepareStatement("select pg_backend_pid()");var row=ps.executeQuery()){row.next();pid.set(row.getInt(1));}started.countDown();
                    var actual=R2ContractServices.create(protectedBodies,cipher,null);
                    var runtime=R2OpportunityCommandRuntime.fromSourcePolicy(policies,protection,cipher,null,"F08_CONCURRENT_REPAIR",actual);
                    return assertThrows(CommandHandler.Rejected.class,()->runtime.execute(next,command)).code();
                }}));
                try{assertTrue(started.await(20,java.util.concurrent.TimeUnit.SECONDS));long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);boolean blocked=false;
                    while(System.nanoTime()<deadline){try(var ps=x.prepareStatement("select cardinality(pg_blocking_pids(?))");){ps.setInt(1,pid.get());try(var row=ps.executeQuery()){row.next();if(row.getInt(1)>0){blocked=true;break;}}}Thread.sleep(20);}assertTrue(blocked,"Expected observed root lock wait");
                }catch(InterruptedException ex){Thread.currentThread().interrupt();throw new java.sql.SQLException(ex);}
                ActionDraftService.databaseBacked().save(x,seed.tenant(),ordinary,null,Map.of("summary","User saved during repair wait"),seed.appointment(),TaskFactory.databaseBacked().now(x));return null;
            });}
            assertEquals("STALE_DRAFT",future.get().get(20,java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals("OPEN",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),ordinary.selector().id()));
    }
    @Test void repair_replay_rechecks_service_authority()throws Exception {
        historicalExtra();var command=repair();assertEquals(CommandOutcome.Status.SUCCEEDED,run(command).status());
        mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and grantee_appointment_id=? and authority_code='OPPORTUNITY_TASK_RECOVER'",seed.tenant(),command.actor().appointmentId());
        assertEquals("NOT_AUTHORIZED",assertThrows(CommandHandler.Rejected.class,()->run(command)).code());
    }
}
