package io.github.windyzhu3.ontologylaw.opportunity;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import io.github.windyzhu3.ontologylaw.lead.ContactFlowFixture;
import io.github.windyzhu3.ontologylaw.opportunity.OpportunityOwnerExceptionService.*;
import io.github.windyzhu3.ontologylaw.responsibility.TaskFactory;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationIdentityReader;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Real migrated PostgreSQL verifies version history, serialization and caller rollback. */
class OpportunityOwnerExceptionPersistenceIT extends ContactFlowFixture {
    private final AtomicReference<Set<Reason>> reasons=new AtomicReference<>(Set.of(Reason.OWNER_INACTIVE));
    private OpportunityOwnerExceptionService service() {
        return OpportunityOwnerExceptionService.databaseBacked((c,t,o,r,now)->new Observation(reasons.get(),null,null,null),
                (c,t,o,receiver,task,waitReceipt)->{throw new SQLException("Unconfigured receiver guard");},
                (c,t,h,o,r,task,waitReceipt,receiver,newTask,actor,zone)->{throw new SQLException("Unconfigured task port");});
    }
    private Subject opening()throws Exception {
        setupContact(); reasons.set(Set.of(Reason.OWNER_INACTIVE));
        var result=execute(prepare(contact("CONNECTED_VALID"))); assertEquals(CommandOutcome.Status.SUCCEEDED,result.status());
        try(var c=database.apiConnection()) {return inTransaction(c,Capability.QUERY,x->EventOpportunityReader.databaseBacked().forContact(x,seed.tenant(),result.resultFact().id()).selector());}
    }
    private Snapshot observe(Subject opportunity)throws Exception {
        try(var c=database.apiConnection()){return inTransaction(c,Capability.COMMAND,x->service().observe(x,seed.tenant(),opportunity).orElse(null));}
    }
    private Snapshot validatedObservation(Subject opportunity)throws Exception {
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"SALES_OPPORTUNITY_OWNER");grant(x,"OPPORTUNITY_OWNER_EXCEPTION_READ");grant(x,"OPPORTUNITY_OWNER_EXCEPTION_RESOLVE");return null;});}
        var actor=service("OPPORTUNITY_OWNER_EXCEPTION_DISCOVER");var protection=OpportunityProgressProtection.aesGcm(t->new javax.crypto.spec.SecretKeySpec(new byte[32],"AES"));
        var runtime=io.github.windyzhu3.ontologylaw.api.R2OpportunityCommandRuntime.fromSourcePolicy(policies,this.protection,protection,null,"OWNER_VALIDATION_IT");
        var command=new CommandEnvelope(CommandEnvelope.Type.OBSERVE_OPPORTUNITY_OWNER_EXCEPTION,UUID.randomUUID(),UUID.randomUUID(),actor,Map.of("opportunityId",opportunity.id().toString(),"expectedOpportunityRevision",opportunity.revision()));
        CommandOutcome result;try(var c=database.apiConnection()){result=assertInstanceOf(CommandOutcome.class,runtime.execute(c,command));assertEquals(CommandOutcome.Status.SUCCEEDED,result.status());}
        try(var c=database.apiConnection()){return inTransaction(c,Capability.QUERY,x->service().read(x,seed.tenant(),result.resultFact()));}
    }
    private Decision decision(Snapshot s){return new Decision(s.selector(),s.opportunity(),s.responsibility().basis(),s.task(),s.waitReceipt(),seed.appointment(),"Arrange a valid receiver");}
    @Test void repeated_observation_preserves_one_cycle_and_immutable_exact_history()throws Exception {
        var opportunity=opening(); var first=observe(opportunity);
        reasons.set(Set.of(Reason.SUPERVISOR_UNRESOLVED,Reason.OWNER_DENIED)); var second=observe(opportunity);
        assertEquals(first.selector().id(),second.selector().id()); assertEquals(1L,second.selector().revision());
        assertEquals(first.firstObservedAt(),second.firstObservedAt());
        try(var c=database.apiConnection()) {inTransaction(c,Capability.QUERY,x->{
            assertEquals(Set.of(Reason.OWNER_INACTIVE),service().read(x,seed.tenant(),first.selector()).reasons());
            assertNull(service().read(x,UUID.randomUUID(),first.selector()));return null;});}
        assertEquals("1",scalar("select count(*) from opportunity.owner_exception where tenant_id=? and is_current and state in ('ACTIVE','COORDINATING')",seed.tenant()));
        assertEquals("2",scalar("select count(*) from opportunity.owner_exception where tenant_id=?",seed.tenant()));
    }
    @Test void coordination_remains_active_does_not_create_tasks_and_rejects_stale_decisions()throws Exception {
        var opportunity=opening(); var first=observe(opportunity); Snapshot coordinated;
        try(var c=database.apiConnection()){coordinated=inTransaction(c,Capability.COMMAND,x->service().coordinate(x,seed.tenant(),decision(first),Instant.now().plusSeconds(3600)));}
        assertEquals(State.COORDINATING,coordinated.state());
        assertEquals(State.COORDINATING,observe(opportunity).state());
        try(var c=database.apiConnection()){assertThrows(SQLException.class,()->inTransaction(c,Capability.COMMAND,x->service().coordinate(x,seed.tenant(),decision(first),Instant.now().plusSeconds(4000))));}
        assertEquals("1",scalar("select count(*) from opportunity.owner_exception_disposition where tenant_id=?",seed.tenant()));
        assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='PROGRESS_OPPORTUNITY'",seed.tenant()));
    }
    @Test void validated_resolution_then_new_failure_creates_new_cycle_and_routing_alone_does_not()throws Exception {
        var opportunity=opening(); reasons.set(Set.of(Reason.SUPERVISOR_UNRESOLVED));assertNull(observe(opportunity));
        reasons.set(Set.of(Reason.OWNER_INACTIVE));var first=observe(opportunity);
        reasons.set(Set.of());var resolved=validatedObservation(opportunity);assertEquals(State.RESOLVED,resolved.state());
        assertEquals("OWNER_VALIDATED",resolved.resolutionKind());assertEquals("audit.audit_entry",resolved.resolution().type());assertNotNull(resolved.resolution().hash());
        assertEquals("1",scalar("select count(*) from audit.audit_entry_classified_v where tenant_id=? and audit_entry_id=? and summary_schema_code='R2_OPPORTUNITY_OWNER_VALIDATION_V1'",seed.tenant(),resolved.resolution().id()));
        reasons.set(Set.of(Reason.OWNER_INACTIVE));var next=observe(opportunity);
        assertNotEquals(first.selector().id(),next.selector().id());assertEquals(0L,next.selector().revision());
        assertEquals("2",scalar("select count(*) from opportunity.owner_exception where tenant_id=? and is_current",seed.tenant()));
    }
    @Test void caller_failure_rolls_back_revision_retirement_and_disposition()throws Exception {
        var opportunity=opening();var first=observe(opportunity);
        try(var c=database.apiConnection()){assertThrows(SQLException.class,()->inTransaction(c,Capability.COMMAND,x->{
            service().coordinate(x,seed.tenant(),decision(first),Instant.now().plusSeconds(3600));throw new SQLException("Synthetic caller failure");}));}
        assertEquals("0",scalar("select count(*) from opportunity.owner_exception_disposition where tenant_id=?",seed.tenant()));
        assertEquals("1",scalar("select count(*) from opportunity.owner_exception where tenant_id=? and is_current and revision=0",seed.tenant()));
    }
    @Test void competing_observers_wait_for_root_lock_and_share_one_cycle()throws Exception {
        var opportunity=opening();var inserted=new CountDownLatch(1);var commit=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var first=workers.submit(()->{try(var c=database.apiConnection()){return inTransaction(c,Capability.COMMAND,x->{
                var result=service().observe(x,seed.tenant(),opportunity).orElseThrow();inserted.countDown();
                try{if(!commit.await(10,TimeUnit.SECONDS))throw new SQLException("Fixture timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new SQLException(e);}return result;});}});
            try {
                assertTrue(inserted.await(10,TimeUnit.SECONDS));
                var second=workers.submit(()->observe(opportunity));
                assertThrows(TimeoutException.class,()->second.get(200,TimeUnit.MILLISECONDS));
                commit.countDown();assertEquals(first.get(10,TimeUnit.SECONDS).selector().id(),second.get(10,TimeUnit.SECONDS).selector().id());
            }finally{commit.countDown();}
        }
        assertEquals("1",scalar("select count(*) from opportunity.owner_exception where tenant_id=? and is_current",seed.tenant()));
    }
    @Test void frozen_responsibility_reader_rejects_wrong_tenant_and_stale_revision()throws Exception {
        var opportunity=opening();try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
            var reader=OpportunityResponsibilityReader.databaseBacked();assertEquals(new OpportunityResponsibilityReader.Responsibility(opportunity,seed.appointment()),reader.current(x,seed.tenant(),opportunity));
            assertThrows(SQLException.class,()->reader.current(x,UUID.randomUUID(),opportunity));
            assertThrows(SQLException.class,()->reader.current(x,seed.tenant(),new Subject(opportunity.type(),opportunity.id(),1L,null)));return null;});}
    }
    private UUID receiver()throws Exception {
        UUID principal=UUID.randomUUID(),appointment=UUID.randomUUID();
        mutate("insert into identity.principal (tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) values (?,?,'HUMAN','FIXTURE',?,'Receiver fixture','ACTIVE',clock_timestamp())",seed.tenant(),principal,CanonicalJson.digest(principal.toString()));
        mutate("insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'OWNER',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),appointment,principal,seed.org());
        return appointment;
    }
    private OpportunityOwnerExceptionService transferService(boolean revokeAfterTask) {
        return OpportunityOwnerExceptionService.databaseBacked((c,t,o,r,now)->new Observation(reasons.get(),null,null,null),
                (c,t,o,receiver,task,waitReceipt)->{
                    if(!AuthorizationIdentityReader.databaseBacked().owner(c,t,receiver,Instant.now()).active())throw new SQLException("Receiver expired");
                },(c,t,h,o,r,task,waitReceipt,receiver,newTask,actor,zone)->{
                    var result=TaskFactory.databaseBacked().handoffOpportunityTask(c,t,h,o,r.basis(),task,waitReceipt,receiver,newTask,actor,zone);
                    if(revokeAfterTask)sql(c,"update identity.appointment set state='SUSPENDED',revision=revision+1 where tenant_id=? and appointment_id=?",t,receiver);
                    return new TaskHandoffResult(result.task().selector(),result.originalDueAt(),result.originalWait(),result.newWait());
                });
    }
    @Test void initial_transfer_persists_exact_chain_and_preserves_frozen_owner()throws Exception {
        var opportunity=opening();var exception=observe(opportunity);var receiver=receiver();TransferResult result;
        try(var c=database.apiConnection()){result=inTransaction(c,Capability.COMMAND,x->transferService(false).transfer(x,seed.tenant(),decision(exception),receiver,ZoneId.of("Asia/Shanghai")));}
        assertTrue(result.changed());assertEquals(State.RESOLVED,result.exception().state());
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
            assertEquals(new OpportunityResponsibilityReader.Responsibility(result.handoff(),receiver),OpportunityResponsibilityReader.databaseBacked().current(x,seed.tenant(),opportunity));
            var task=TaskFactory.databaseBacked().read(x,seed.tenant(),result.newTask().id());assertEquals("OPEN",task.state());assertEquals(receiver,task.owner());return null;});}
        assertEquals(seed.appointment().toString(),scalar("select owner_appointment_id from opportunity.opportunity where tenant_id=? and opportunity_id=?",seed.tenant(),opportunity.id()));
        assertEquals("1",scalar("select count(*) from opportunity.responsibility_handoff where tenant_id=?",seed.tenant()));
        try(var c=database.apiConnection()){assertThrows(SQLException.class,()->inTransaction(c,Capability.COMMAND,x->transferService(false).transfer(x,seed.tenant(),decision(exception),receiver,ZoneId.of("Asia/Shanghai"))));}
    }
    @Test void final_receiver_recheck_failure_rolls_back_task_handoff_disposition_and_resolution()throws Exception {
        var opportunity=opening();var exception=observe(opportunity);var receiver=receiver();
        try(var c=database.apiConnection()){assertThrows(SQLException.class,()->inTransaction(c,Capability.COMMAND,x->transferService(true).transfer(x,seed.tenant(),decision(exception),receiver,ZoneId.of("Asia/Shanghai"))));}
        assertEquals("0",scalar("select count(*) from opportunity.responsibility_handoff where tenant_id=?",seed.tenant()));
        assertEquals("0",scalar("select count(*) from opportunity.owner_exception_disposition where tenant_id=?",seed.tenant()));
        assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='PROGRESS_OPPORTUNITY'",seed.tenant()));
        assertEquals("ACTIVE",scalar("select state from opportunity.owner_exception where tenant_id=? and is_current",seed.tenant()));
    }
    @Test void closed_opportunity_keeps_exact_handoff_chain_without_accepting_stale_current_selector()throws Exception {
        var opportunity=opening();var exception=observe(opportunity);var receiver=receiver();TransferResult transferred;
        try(var c=database.apiConnection()){transferred=inTransaction(c,Capability.COMMAND,x->transferService(false).transfer(x,seed.tenant(),decision(exception),receiver,ZoneId.of("Asia/Shanghai")));}
        mutate("update opportunity.opportunity set close_outcome_code='LOST',closed_at=clock_timestamp(),revision=revision+1 where tenant_id=? and opportunity_id=?",seed.tenant(),opportunity.id());
        var closed=new Subject(opportunity.type(),opportunity.id(),1L,null);
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
            var reader=OpportunityResponsibilityReader.databaseBacked();
            assertEquals(new OpportunityResponsibilityReader.Responsibility(transferred.handoff(),receiver),reader.current(x,seed.tenant(),closed));
            assertThrows(SQLException.class,()->reader.current(x,seed.tenant(),opportunity));return null;
        });}
    }
    @Test void unresolved_routing_and_inconsistent_source_cannot_be_hidden_by_owner_recovery_or_transfer()throws Exception {
        var opportunity=opening();observe(opportunity);
        reasons.set(Set.of(Reason.SUPERVISOR_UNRESOLVED));assertEquals(State.ACTIVE,observe(opportunity).state());
        reasons.set(Set.of(Reason.SOURCE_INCONSISTENT));var broken=observe(opportunity);var receiver=receiver();
        try(var c=database.apiConnection()){assertThrows(SQLException.class,()->inTransaction(c,Capability.COMMAND,x->transferService(false).transfer(x,seed.tenant(),decision(broken),receiver,ZoneId.of("Asia/Shanghai"))));}
        assertEquals("0",scalar("select count(*) from opportunity.responsibility_handoff where tenant_id=?",seed.tenant()));
    }
    @Test void existing_closed_fact_terminates_cycle_without_rewriting_its_original_opportunity_version()throws Exception {
        var opportunity=opening();var original=observe(opportunity);
        mutate("update opportunity.opportunity set close_outcome_code='LOST',closed_at=clock_timestamp(),revision=revision+1 where tenant_id=? and opportunity_id=?",seed.tenant(),opportunity.id());
        var closed=new Subject(opportunity.type(),opportunity.id(),1L,null);var terminated=observe(closed);
        assertEquals(State.NO_LONGER_APPLICABLE,terminated.state());assertEquals(original.opportunity(),terminated.opportunity());
        assertEquals(closed,terminated.resolution());assertEquals("OPPORTUNITY_CLOSED",terminated.resolutionKind());assertNull(observe(closed));
    }
}
