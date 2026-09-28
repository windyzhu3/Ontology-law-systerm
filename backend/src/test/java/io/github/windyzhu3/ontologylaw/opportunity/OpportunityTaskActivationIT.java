package io.github.windyzhu3.ontologylaw.opportunity;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;

import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import io.github.windyzhu3.ontologylaw.lead.ContactFlowFixture;
import io.github.windyzhu3.ontologylaw.lead.R1EventReaders;
import io.github.windyzhu3.ontologylaw.lead.CurrentLeadReader;
import io.github.windyzhu3.ontologylaw.lead.LeadProtection;
import io.github.windyzhu3.ontologylaw.responsibility.CurrentTaskReader;
import io.github.windyzhu3.ontologylaw.responsibility.TaskFactory;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class OpportunityTaskActivationIT extends ContactFlowFixture {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private Subject openOpportunity() throws Exception {
        setupContact();
        var result = execute(prepare(contact("CONNECTED_VALID")));
        assertEquals(CommandOutcome.Status.SUCCEEDED, result.status());
        try (var c = database.apiConnection()) {
            return inTransaction(c, Capability.QUERY, x -> EventOpportunityReader.databaseBacked()
                    .forContact(x, seed.tenant(), result.resultFact().id()).selector());
        }
    }

    @Test void current_task_reader_accepts_exact_opportunity_contract_and_keeps_lead_history() throws Exception {
        var opportunity = openOpportunity();
        UUID taskId = UUID.randomUUID();
        try (var c = database.apiConnection()) {
            inTransaction(c, Capability.COMMAND, x -> {
                sql(x, "insert into responsibility.task_occurrence (tenant_id,task_occurrence_id,owner_appointment_id,business_purpose_code,primary_command_code,expected_completion_fact_type,original_sla_code,original_sla_seconds,original_sla_due_at,state,created_at,subject_type,subject_id,subject_revision) values (?,?,?,'PROGRESS_OPPORTUNITY','RECORD_OPPORTUNITY_PROGRESS','opportunity.opportunity_progress','R2_BUSINESS_4H_V1',14400,clock_timestamp()+interval '4 hours','OPEN',clock_timestamp(),'opportunity.opportunity',?,0)",
                        seed.tenant(), taskId, seed.appointment(), opportunity.id());
                var read = assertDoesNotThrow(() -> CurrentTaskReader.databaseBacked().read(x, seed.tenant(), taskId));
                assertEquals(opportunity, read.subject());
                assertEquals("PROGRESS_OPPORTUNITY", read.type().name());
                assertEquals("DONE", CurrentTaskReader.databaseBacked().read(x, seed.tenant(), current.selector().id()).state());
                assertEquals("lead.lead", TaskFactory.databaseBacked().read(x, seed.tenant(), current.selector().id()).lead().type());
                return null;
            });
        }
    }

    private OpportunityTaskActivationService.OpeningSourceReader openingSources() {
        return (c, tenant, assignmentId, contactId) -> {
            var facts = R1EventReaders.databaseBacked();
            var contact = facts.contact(c, tenant, contactId);
            var assignment = facts.assignment(c, tenant, assignmentId);
            var task = contact == null ? null : facts.task(c, tenant, contact.taskId());
            return new OpportunityTaskActivationService.OpeningSources(
                    contact == null ? null : new OpportunityTaskActivationService.Contact(contact.selector(), contact.leadId(), contact.assignmentId(), contact.taskId(), contact.code()),
                    assignment == null ? null : new OpportunityTaskActivationService.Assignment(assignment.selector(), assignment.leadId(), assignment.owner()),
                    task == null ? null : new OpportunityTaskActivationService.ContactTask(task.selector(), task.lead(), task.owner(), task.purpose(), task.primaryCommand(), task.state(), task.completion()));
        };
    }

    private OpportunityTaskActivationService activation() {
        return OpportunityTaskActivationService.databaseBacked(openingSources(), (c, tenant, owner, subject, zone, now) -> TaskFactory.databaseBacked()
                .createInitialOpportunity(c, tenant, owner, subject, zone, now).selector());
    }

    private Subject activate(Subject opportunity) throws Exception {
        try (var c = database.apiConnection()) {
            return inTransaction(c, Capability.COMMAND, x -> activation().activate(x, seed.tenant(), opportunity, ZONE, businessAt));
        }
    }

    private void grantOpportunity() throws Exception {
        try (var c = database.apiConnection()) {
            inTransaction(c, Capability.COMMAND, x -> { grant(x, "SALES_OPPORTUNITY_OWNER"); return null; });
        }
    }

    private String initialCount() throws Exception {
        return scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='PROGRESS_OPPORTUNITY'", seed.tenant());
    }

    @Test void new_and_already_delivered_openings_share_one_durable_initial_handoff() throws Exception {
        for (boolean delivered : List.of(false, true)) {
            var opportunity = openOpportunity();
            grantOpportunity();
            var queue = R1ProjectionOutboxPort.databaseBacked(database::workerConnection);
            if (delivered) for (var claim : queue.claim(seed.tenant(), "HISTORY_FIXTURE", 4)) assertTrue(queue.ack(claim));
            var before = queue.counts(seed.tenant(), 100);
            assertEquals("0", initialCount(), "R1 opening must not activate R2 before integration");
            var first = assertDoesNotThrow(() -> activate(opportunity));
            var again = activate(opportunity);
            assertEquals(first, again);
            assertEquals("1", initialCount());
            assertEquals(before, queue.counts(seed.tenant(), 100), "Handoff must not reset R1 queue progress");
            try (var c = database.apiConnection()) {
                inTransaction(c, Capability.QUERY, x -> {
                    var task = CurrentTaskReader.databaseBacked().read(x, seed.tenant(), first.id());
                    assertEquals(opportunity, task.subject());
                    assertEquals(seed.appointment(), task.owner());
                    assertEquals("OPEN", task.state());
                    assertEquals("R2_BUSINESS_4H_V1", task.slaCode());
                    assertEquals(Instant.parse("2026-08-03T05:00:00Z"), task.slaDueAt());
                    return null;
                });
            }
        }
    }

    @Test void dedicated_factory_serializes_concurrent_creations_without_an_opportunity_caller_lock() throws Exception {
        var opportunity = openOpportunity();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var inserted = new CountDownLatch(1);
            var commit = new CountDownLatch(1);
            var secondStarted = new CountDownLatch(1);
            var secondPid = new java.util.concurrent.atomic.AtomicInteger();
            var first = workers.submit(() -> {
                try (var c = database.apiConnection()) {
                    return inTransaction(c, Capability.COMMAND, x -> {
                        var task = TaskFactory.databaseBacked().createInitialOpportunity(x, seed.tenant(), seed.appointment(), opportunity, ZONE, businessAt);
                        inserted.countDown();
                        try { assertTrue(commit.await(10, TimeUnit.SECONDS)); }
                        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new SQLException("Fixture interrupted", failure); }
                        return task.selector();
                    });
                }
            });
            try {
                assertTrue(inserted.await(10, TimeUnit.SECONDS));
                var second = workers.submit(() -> {
                    try (var c = database.apiConnection()) {
                        return inTransaction(c, Capability.COMMAND, x -> {
                            try (var p = x.prepareStatement("select pg_backend_pid()"); var r = p.executeQuery()) {
                                assertTrue(r.next()); secondPid.set(r.getInt(1));
                            }
                            secondStarted.countDown();
                            return TaskFactory.databaseBacked().createInitialOpportunity(x, seed.tenant(), seed.appointment(), opportunity, ZONE, businessAt.plusSeconds(60)).selector();
                        });
                    }
                });
                assertTrue(secondStarted.await(10, TimeUnit.SECONDS));
                awaitDatabaseLock(secondPid.get());
                commit.countDown();
                assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
                assertEquals("1", initialCount());
            } finally { commit.countDown(); }
        }
    }

    private void awaitDatabaseLock(int pid) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try (var c = database.adminConnection(); var p = c.prepareStatement("select exists(select 1 from pg_locks where pid=? and not granted)")) {
            p.setInt(1, pid);
            do {
                try (var r = p.executeQuery()) { assertTrue(r.next()); if (r.getBoolean(1)) return; }
            } while (System.nanoTime() < until);
        }
        fail("Concurrent initial creation must wait for the first transaction before reading its durable identity");
    }

    @Test void outer_transaction_failure_rolls_back_handoff_and_retry_creates_one_task() throws Exception {
        var opportunity = openOpportunity();
        grantOpportunity();
        try (var c = database.apiConnection()) {
            assertThrows(SQLException.class, () -> inTransaction(c, Capability.COMMAND, x -> {
                assertNotNull(activation().activate(x, seed.tenant(), opportunity, ZONE, businessAt));
                throw new SQLException("Synthetic caller failure", "XX000");
            }));
        }
        assertEquals("0", initialCount());
        assertNotNull(activate(opportunity));
        assertEquals("1", initialCount());
    }

    @Test void exact_opportunity_deny_overrides_the_owners_authority_grant() throws Exception {
        var opportunity = openOpportunity();
        grantOpportunity();
        mutate("insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values (?,?,?,?,'SALES_OPPORTUNITY_OWNER','DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),'opportunity.opportunity',?,0)", seed.tenant(), UUID.randomUUID(), seed.principal(), seed.appointment(), opportunity.id());
        assertEquals("OPPORTUNITY_OWNER_UNAVAILABLE", assertThrows(OpportunityTaskActivationService.Blocked.class, () -> activate(opportunity)).code());
        assertEquals("0", initialCount());
    }

    @Test void permission_revoked_after_ensure_is_rejected_and_the_new_responsibility_rolls_back() throws Exception {
        var opportunity = openOpportunity();
        grantOpportunity();
        var created = new java.util.concurrent.atomic.AtomicReference<Subject>();
        var service = OpportunityTaskActivationService.databaseBacked(openingSources(), (c, tenant, owner, subject, zone, now) -> {
            var task = TaskFactory.databaseBacked().createInitialOpportunity(c, tenant, owner, subject, zone, now);
            created.set(task.selector());
            sql(c, "update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE_FINAL_RECHECK',revision=revision+1 where tenant_id=? and grantee_appointment_id=? and authority_code='SALES_OPPORTUNITY_OWNER'", tenant, owner);
            return task.selector();
        });
        try (var c = database.apiConnection()) {
            var failure = assertThrows(OpportunityTaskActivationService.Blocked.class,
                    () -> inTransaction(c, Capability.COMMAND, x -> service.activate(x, seed.tenant(), opportunity, ZONE, businessAt)));
            assertEquals("OPPORTUNITY_OWNER_UNAVAILABLE", failure.code());
        }
        assertNotNull(created.get(), "The failure must follow actual responsibility creation");
        assertEquals("0", initialCount());
        assertNotNull(activate(opportunity), "The whole failed transaction, including synthetic revocation, rolled back");
        assertEquals("1", initialCount());
    }

    @Test void completed_or_cancelled_initial_task_is_never_recreated() throws Exception {
        for (String state : List.of("DONE", "CANCELLED")) {
            var opportunity = openOpportunity();
            grantOpportunity();
            var initial = activate(opportunity);
            try (var c = database.apiConnection()) {
                inTransaction(c, Capability.COMMAND, x -> {
                    if (state.equals("CANCELLED")) {
                        sql(x, "update responsibility.task_occurrence set state='CANCELLED',cancelled_at=clock_timestamp(),cancellation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and task_occurrence_id=?", seed.tenant(), initial.id());
                    } else {
                        UUID progress = UUID.randomUUID();
                        sql(x, "insert into opportunity.opportunity_progress (tenant_id,opportunity_progress_id,opportunity_id,progress_no,progress_type_code,progress_contract_code,progress_contract_version,progress_digest,occurred_at,created_at) values (?,?,?,1,'FIXTURE','FIXTURE',1,?,clock_timestamp(),clock_timestamp())", seed.tenant(), progress, opportunity.id(), new byte[32]);
                        var task = TaskFactory.databaseBacked().read(x, seed.tenant(), initial.id());
                        TaskFactory.databaseBacked().complete(x, seed.tenant(), task, new Subject("opportunity.opportunity_progress", progress, null, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"), businessAt.plusSeconds(1));
                    }
                    return null;
                });
            }
            var replay = activate(opportunity);
            assertEquals(initial.id(), replay.id());
            assertEquals(1L, replay.revision());
            assertEquals("1", initialCount());
            assertEquals(state, scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?", seed.tenant(), initial.id()));
        }
    }

    @Test void missing_authority_or_inactive_owner_blocks_without_an_orphan_task_and_can_be_retried() throws Exception {
        var opportunity = openOpportunity();
        assertEquals("OPPORTUNITY_OWNER_UNAVAILABLE", assertThrows(OpportunityTaskActivationService.Blocked.class, () -> activate(opportunity)).code());
        assertEquals("0", initialCount());
        grantOpportunity();
        mutate("update identity.appointment set state='SUSPENDED',revision=revision+1 where tenant_id=? and appointment_id=?", seed.tenant(), seed.appointment());
        assertEquals("OPPORTUNITY_OWNER_UNAVAILABLE", assertThrows(OpportunityTaskActivationService.Blocked.class, () -> activate(opportunity)).code());
        assertEquals("0", initialCount());
        mutate("update identity.appointment set state='ACTIVE',revision=revision+1 where tenant_id=? and appointment_id=?", seed.tenant(), seed.appointment());
        assertNotNull(activate(opportunity));
        assertEquals("1", initialCount());
    }

    @Test void wrong_tenant_wrong_subject_and_unsafe_generic_creation_cannot_create_a_task() throws Exception {
        var opportunity = openOpportunity();
        grantOpportunity();
        assertThrows(IllegalArgumentException.class, () -> activate(new Subject("lead.lead", opportunity.id(), 0L, null)));
        try (var c = database.apiConnection()) {
            inTransaction(c, Capability.COMMAND, x -> {
                assertEquals("OPPORTUNITY_NOT_FOUND", assertThrows(OpportunityTaskActivationService.Blocked.class,
                        () -> activation().activate(x, UUID.randomUUID(), opportunity, ZONE, businessAt)).code());
                assertThrows(IllegalArgumentException.class, () -> TaskFactory.databaseBacked().create(x, seed.tenant(), TaskFactory.Type.PROGRESS_OPPORTUNITY, seed.appointment(), opportunity, ZONE, businessAt));
                return null;
            });
        }
        assertEquals("0", initialCount());
    }

    @Test void stale_or_closed_opportunity_does_not_generate_a_normal_followup() throws Exception {
        var opportunity = openOpportunity();
        grantOpportunity();
        mutate("update opportunity.opportunity set close_outcome_code='LOST',closed_at=clock_timestamp(),revision=revision+1 where tenant_id=? and opportunity_id=?", seed.tenant(), opportunity.id());
        assertEquals("STALE_OPPORTUNITY", assertThrows(OpportunityTaskActivationService.Blocked.class, () -> activate(opportunity)).code());
        assertEquals("OPPORTUNITY_CLOSED", assertThrows(OpportunityTaskActivationService.Blocked.class, () -> activate(new Subject(opportunity.type(), opportunity.id(), 1L, null))).code());
        assertEquals("0", initialCount());
    }

    @Test void connected_contact_without_completed_source_responsibility_is_blocked() throws Exception {
        setupContact();
        grantOpportunity();
        Subject opportunity;
        try (var c = database.apiConnection()) {
            opportunity = inTransaction(c, Capability.COMMAND, x -> {
                UUID contactId = UUID.randomUUID();
                sql(x, "insert into lead.lead_contact_result (tenant_id,lead_contact_result_id,lead_id,lead_assignment_id,contact_no,contact_task_id,contact_channel_code,result_code,result_summary,resulted_at,created_at) values (?,?,?,?,1,?,'PHONE','CONNECTED_VALID','Synthetic imported source',clock_timestamp(),clock_timestamp())", seed.tenant(), contactId, current.lead().id(), secondaryFact.id(), current.selector().id());
                var contact = CurrentLeadReader.databaseBacked(protection).contactResult(x, seed.tenant(), contactId).selector();
                return OpportunityOpeningService.databaseBacked().open(x, seed.tenant(), current.lead(), secondaryFact, contact, seed.appointment(),
                        protection.encrypt(seed.tenant(), LeadProtection.Field.OPPORTUNITY_LEGAL_NEED, "Synthetic source"), CanonicalJson.digest("Synthetic source"), businessAt);
            });
        }
        assertEquals("OPPORTUNITY_OPENING_SOURCE_INVALID", assertThrows(OpportunityTaskActivationService.Blocked.class, () -> activate(opportunity)).code());
        assertEquals("0", initialCount());
    }
}
