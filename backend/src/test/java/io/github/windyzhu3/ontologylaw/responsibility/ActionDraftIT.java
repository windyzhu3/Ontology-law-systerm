package io.github.windyzhu3.ontologylaw.responsibility;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;
import io.github.windyzhu3.ontologylaw.testing.PostgresIntegrationTest;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.lead.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import java.util.*;
import java.time.*;
import java.sql.*;
import java.lang.reflect.*;
import java.util.concurrent.*;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class ActionDraftIT extends PostgresIntegrationTest {
    AuthorizationServiceIT.Seed seed;
    TaskFactory.Task task;
    CommandRuntime runtime;
    final LeadProtection protection=LeadProtection.aesGcm(new LeadProtection.Keys() {
        public SecretKey encryption(UUID tenant) { return new SecretKeySpec(new byte[32],"AES"); }
        public SecretKey hmac(UUID tenant,LeadProtection.Purpose purpose) { return new SecretKeySpec(new byte[32],"HmacSHA256"); }
    });
    void setup(TaskFactory.Type type)throws Exception {
        seed=AuthorizationServiceIT.seed(database,"HUMAN",type.authority);
        var sources=new R1SourcePolicyRegistry(Map.of("FIXTURE",new R1SourcePolicyRegistry.SourcePolicy(
                R1SourcePolicyRegistry.AssignmentMode.MANUAL,List.of("ROOT"),"ROOT","ROOT","Asia/Shanghai")));
        var handlers=new ArrayList<CommandHandler>(new LeadCommands(sources,protection).handlers());
        handlers.addAll(new ActionDraftCommands(protection).handlers());
        runtime=new CommandRuntime(handlers,AuthorizationService.databaseBacked(),
                AuditAppender.databaseBacked("DRAFT_IT"),R1AuthorizationReaders.databaseBacked(sources),R1EventReaders.databaseBacked());
        try(var c=database.apiConnection()) { task=inTransaction(c,Capability.COMMAND,x->{
            var values=Map.<String,Object>of("sourceChannelCode","TEST","sourceAccountCode","FIXTURE","capturedAt","2026-09-04T09:00:00Z",
                    "capturedName","Synthetic contact","serviceCategoryCode","CONSULTATION","jurisdictionCode","CN","urgencyCode","NORMAL","legalNeedSummary","Synthetic enquiry");
            var lead=LeadIngressService.databaseBacked(protection).capture(x,seed.tenant(),values,CanonicalJson.digest(UUID.randomUUID().toString()),Instant.now());
            return TaskFactory.databaseBacked().create(x,seed.tenant(),type,seed.appointment(),lead.selector(),ZoneId.of("Asia/Shanghai"),Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        }); }
    }
    @Test void create_persists_canonical_candidate_without_completing_task()throws Exception {
        setup(TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP);
        var values=Map.<String,Object>of("decisionCode","SCHEDULE_ROUTING_REVIEW","rationaleSummary"," Synthetic review ");
        var envelope=new CommandEnvelope(CommandEnvelope.Type.SAVE_ACTION_DRAFT,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),
                Map.of("actionCode",task.type().command,"schemaVersion",1,"values",values),null,new CommandEnvelope.DraftPrecondition(task.selector().id(),null,"*"));
        try(var c=database.apiConnection()) {
            var outcome=assertInstanceOf(CommandOutcome.class,assertDoesNotThrow(()->runtime.execute(c,envelope)));
            assertEquals(CommandOutcome.Status.SUCCEEDED,outcome.status());
            assertEquals("responsibility.action_draft",outcome.resultFact().type());
            assertEquals(0L,outcome.resultFact().revision());
            inTransaction(c,Capability.QUERY,x->{
                assertEquals(task,TaskFactory.databaseBacked().read(x,seed.tenant(),task.selector().id()));
                var draft=ActionDraftService.databaseBacked().read(x,seed.tenant(),task.selector().id());
                assertEquals("TvvkTUSIhXqtnsH1ycr6jvkx4W483WJqqkcApOvz4bc",draft.digest());
                assertEquals("Synthetic review",draft.values().get("rationaleSummary"));
                assertEquals(draft.createdAt(),draft.updatedAt());
                assertThrows(UnsupportedOperationException.class,()->draft.values().put("rationaleSummary","changed"));
                return null;
            });
        }
        assertEquals(List.of(1L,1L,1L,1L,1L,1L,1L,0L,1L),counts());
    }
    Map<String,Object> routing(String rationale) { return Map.of("decisionCode","SCHEDULE_ROUTING_REVIEW","rationaleSummary",rationale); }
    CommandEnvelope save(UUID key,Map<String,Object> values,String match,String none) {
        return new CommandEnvelope(CommandEnvelope.Type.SAVE_ACTION_DRAFT,key,UUID.randomUUID(),seed.request().actor(),
                Map.of("actionCode",task.type().command,"schemaVersion",1,"values",values),null,new CommandEnvelope.DraftPrecondition(task.selector().id(),match,none));
    }
    CommandOutcome run(CommandEnvelope e)throws Exception {
        try(var c=database.apiConnection()) { return assertInstanceOf(CommandOutcome.class,runtime.execute(c,e)); }
    }
    ActionDraftService.Draft draft()throws Exception {
        try(var c=database.apiConnection()) { return inTransaction(c,Capability.QUERY,x->ActionDraftService.databaseBacked().read(x,seed.tenant(),task.selector().id())); }
    }
    String tag()throws Exception { var d=draft();return R1ResourceTags.draft(seed.request().actor(),d.selector(),d.state()); }
    List<Long> counts()throws Exception {
        try(var c=database.apiConnection()) { return inTransaction(c,Capability.QUERY,x->{
            var result=new ArrayList<Long>();
            for(String table:List.of("lead.lead","responsibility.task_occurrence","responsibility.action_draft","execution.command_execution_slot",
                    "execution.command_receipt","execution.domain_event","execution.domain_event_outbox","responsibility.decision_record","audit.audit_entry_classified_v")) {
                try(var p=x.prepareStatement("select count(*) from "+table+" where tenant_id=?")) {
                    p.setObject(1,seed.tenant());try(var r=p.executeQuery()) { r.next();result.add(r.getLong(1)); }
                }
            }
            return result;
        }); }
    }
    String snapshot()throws Exception {
        try(var c=database.apiConnection()) { return inTransaction(c,Capability.COMMAND,x->{
            try(var p=x.prepareStatement("select row_to_json(d)::text from responsibility.action_draft d where tenant_id=? and task_occurrence_id=?")) {
                p.setObject(1,seed.tenant());p.setObject(2,task.selector().id());try(var r=p.executeQuery()) { return r.next()?r.getString(1):null; }
            }
        }); }
    }
    void before(CommandEnvelope e,String code)throws Exception {
        var counts=counts();var row=snapshot();
        try(var c=database.apiConnection()) { assertEquals(code,assertThrows(CommandHandler.Rejected.class,()->runtime.execute(c,e)).code()); }
        assertEquals(counts,counts());assertEquals(row,snapshot());
    }
    void terminal(CommandEnvelope e,String code)throws Exception {
        var before=counts();var row=snapshot();var result=run(e);
        assertEquals(CommandOutcome.Status.REJECTED,result.status());assertEquals(code,result.rejectionCode());
        var expected=new ArrayList<>(before);for(int index:List.of(3,4,8))expected.set(index,expected.get(index)+1);
        assertEquals(expected,counts());assertEquals(row,snapshot());
        assertEquals(result,run(e));assertEquals(expected,counts());
    }
    void mutate(String statement,Object... args)throws Exception {
        try(var c=database.apiConnection()) { inTransaction(c,Capability.COMMAND,x->{sql(x,statement,args);return null;}); }
    }
    @Test void update_no_change_and_old_key_replay_preserve_exact_row_and_receipt()throws Exception {
        setup(TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP);
        var create=save(UUID.randomUUID(),routing("Synthetic review"),null,"*");var first=run(create);String initial=tag();
        var update=save(UUID.randomUUID(),routing("Changed reason"),initial,null);var changed=run(update);
        assertEquals(CommandOutcome.Status.SUCCEEDED,changed.status());assertEquals(first.resultFact().id(),changed.resultFact().id());
        assertEquals(1L,changed.resultFact().revision());assertEquals("Changed reason",draft().values().get("rationaleSummary"));assertNotEquals(initial,tag());
        String row=snapshot();var before=counts();
        var same=run(save(UUID.randomUUID(),routing(" Changed reason "),tag(),null));
        assertEquals(CommandOutcome.Status.NO_CHANGE,same.status());assertEquals(changed.resultFact(),same.resultFact());assertEquals(row,snapshot());
        var expected=new ArrayList<>(before);for(int index:List.of(3,4,8))expected.set(index,expected.get(index)+1);assertEquals(expected,counts());
        assertEquals(first,run(create));assertEquals(changed,run(update));assertEquals(expected,counts());assertEquals(row,snapshot());
        assertEquals(first,run(save(create.commandId(),routing("Synthetic review"),tag(),null)),"Changing only trusted headers must not change the body digest");
        try(var c=database.apiConnection()) { assertInstanceOf(CommandResult.Conflict.class,runtime.execute(c,save(create.commandId(),routing("Other payload"),null,"*"))); }
        assertEquals(expected,counts());
        terminal(save(UUID.randomUUID(),routing("Third"),initial,null),"STALE_DRAFT");
        terminal(save(UUID.randomUUID(),routing("Third"),null,"*"),"STALE_DRAFT");
    }
    @Test void malformed_missing_and_wrong_kind_headers_fail_before_slot_and_absent_edit_is_not_found()throws Exception {
        setup(TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP);
        before(save(UUID.randomUUID(),routing("Reason"),null,null),"DRAFT_PRECONDITION_REQUIRED");
        for(String token:List.of("*","W/\"draft."+"A".repeat(43)+"\"","\"task."+"A".repeat(43)+"\"","\"draft.x\"","\"draft."+"A".repeat(43)+"\",\"draft."+"A".repeat(43)+"\""))
            before(save(UUID.randomUUID(),routing("Reason"),token,null),"VALIDATION_FAILED");
        before(save(UUID.randomUUID(),routing("Reason"),"\"draft."+"A".repeat(43)+"\"","*"),"VALIDATION_FAILED");
        before(save(UUID.randomUUID(),routing("Reason"),null,"not-star"),"VALIDATION_FAILED");
        before(save(UUID.randomUUID(),routing("Reason"),"\"draft."+"A".repeat(43)+"\"",null),"NOT_FOUND");
    }
    @ParameterizedTest @EnumSource(TaskFactory.Type.class)
    void each_frozen_action_saves_its_required_values_and_rejects_confirmation_fields(TaskFactory.Type type)throws Exception {
        setup(type);var values=candidate(type);
        var invalid=new TreeMap<>(values);invalid.put("draftId",UUID.randomUUID().toString());before(save(UUID.randomUUID(),invalid,null,"*"),"VALIDATION_FAILED");
        for(String required:required(type)) {
            invalid=new TreeMap<>(values);invalid.remove(required);before(save(UUID.randomUUID(),invalid,null,"*"),"VALIDATION_FAILED");
            invalid=new TreeMap<>(values);invalid.put(required,values.get(required) instanceof String?2:"not-a-revision");
            before(save(UUID.randomUUID(),invalid,null,"*"),"VALIDATION_FAILED");
        }
        var outcome=run(save(UUID.randomUUID(),values,null,"*"));assertEquals(CommandOutcome.Status.SUCCEEDED,outcome.status());
        assertEquals(type.command,draft().actionCode());assertEquals(type.schema,draft().schemaCode());
        assertEquals(1,draft().schemaVersion());assertEquals(values.keySet(),draft().values().keySet());
    }
    Map<String,Object> candidate(TaskFactory.Type type) {
        String id="10000000-0000-0000-0000-000000000001",hash="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        return switch(type) {
            case RESOLVE_LEAD_DUPLICATE -> Map.of("decisionCode","KEEP_SEPARATE","candidateLeadId",id,"candidateLeadRevision",0L,"partyId",id,"partyRevision",0L,"rationaleSummary","Reason");
            case COMPLETE_LEAD_INGRESS -> Map.of("phone","+12025550123","sourceCode","OWNER_CONFIRMED","sourceSummary","Source");
            case ASSIGN_LEAD -> Map.of("ownerAppointmentId",seed.appointment().toString());
            case RESOLVE_LEAD_ROUTING_GAP -> routing("Reason");
            case ACK_SOURCE_INTAKE_STOP_REQUEST -> Map.of("causalDecisionId",id,"causalDecisionHash",hash,"rationaleSummary","Reason");
            case CONTACT_LEAD -> Map.of("leadAssignmentId",id,"leadAssignmentRevision",0L,"contactChannelCode","PHONE","resultCode","NOT_CONNECTED");
            case REVIEW_LEAD_VALIDITY -> Map.of("triggeringContactResultId",id,"triggeringContactResultHash",hash,"decisionCode","REOPEN_CONTACT","rationaleSummary","Reason");
        };
    }
    Set<String> required(TaskFactory.Type type) { return candidate(type).keySet(); }
    @Test void schema_action_unknown_task_and_foreign_task_fail_before_slot()throws Exception {
        setup(TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP);
        for(Map<String,Object> body:List.of(Map.<String,Object>of("actionCode",task.type().command,"schemaVersion",2,"values",routing("Reason")),
                Map.<String,Object>of("actionCode","ASSIGN_LEAD","schemaVersion",1,"values",Map.of("ownerAppointmentId",seed.appointment().toString())),
                Map.<String,Object>of("actionCode",task.type().command,"schemaVersion",1,"values",routing("Reason"),"taskId",task.selector().id().toString())))
            before(new CommandEnvelope(CommandEnvelope.Type.SAVE_ACTION_DRAFT,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),body,null,new CommandEnvelope.DraftPrecondition(task.selector().id(),null,"*")),"VALIDATION_FAILED");
        var valid=save(UUID.randomUUID(),routing("Reason"),null,"*");
        before(new CommandEnvelope(valid.type(),valid.commandId(),valid.correlationId(),valid.actor(),valid.payload(),null,new CommandEnvelope.DraftPrecondition(UUID.randomUUID(),null,"*")),"NOT_FOUND");
        var foreign=new AuthorizationService.Actor(UUID.randomUUID(),seed.principal(),seed.appointment(),null,null);
        before(new CommandEnvelope(valid.type(),valid.commandId(),valid.correlationId(),foreign,valid.payload(),null,valid.draftPrecondition()),"NOT_FOUND");
    }
    @Test void saved_draft_is_consumed_atomically_by_real_primary_and_remains_immutable()throws Exception {
        setup(TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP);var values=routing("Reason");var save=save(UUID.randomUUID(),values,null,"*");var receipt=run(save);var draft=draft();
        var payload=new TreeMap<>(values);payload.put("draftId",draft.selector().id().toString());payload.put("expectedDraftRevision",0L);payload.put("draftDigest",draft.digest());
        var main=new CommandEnvelope(CommandEnvelope.Type.RECORD_ROUTING_DISPOSITION,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),payload,
                new CommandEnvelope.TaskPrecondition(task.selector().id(),R1ResourceTags.task(seed.request().actor(),task.selector(),task.state())));
        var completed=run(main);assertEquals(CommandOutcome.Status.SUCCEEDED,completed.status());assertEquals("CONFIRMED",draft().state());assertEquals(1L,draft().selector().revision());
        try(var c=database.apiConnection()) { inTransaction(c,Capability.QUERY,x->{
            var decision=CurrentTaskReader.databaseBacked().decision(x,seed.tenant(),completed.resultFact().id());
            assertEquals(completed.resultFact(),decision.selector());assertEquals(task.selector().id(),decision.taskId());assertEquals(task.lead(),decision.subject());
            assertEquals("SCHEDULE_ROUTING_REVIEW",decision.code());assertEquals("Reason",decision.rationale());
            assertNull(CurrentTaskReader.databaseBacked().decision(x,UUID.randomUUID(),completed.resultFact().id()));return null;
        }); }
        var snapshot=snapshot();var counts=counts();assertEquals(receipt,run(save));assertEquals(counts,counts());assertEquals(snapshot,snapshot());
        terminal(save(UUID.randomUUID(),values,tag(),null),"TASK_ALREADY_COMPLETED");
    }
    @Test void new_save_rejects_waiting_task_and_stale_lead_after_slot()throws Exception {
        setup(TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP);
        try(var c=database.apiConnection()) { inTransaction(c,Capability.COMMAND,x->{TaskFactory.databaseBacked().waitUntil(x,seed.tenant(),task,seed.appointment(),Instant.parse("2027-01-01T00:00:00Z"),Instant.parse("2026-09-06T00:00:00Z"));return null;}); }
        terminal(save(UUID.randomUUID(),routing("Reason"),null,"*"),"TASK_NOT_OPEN");
        setup(TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP);
        mutate("update lead.lead set revision=revision+1,disposition_code='KEEP_SEPARATE' where tenant_id=? and lead_id=?",seed.tenant(),task.lead().id());
        terminal(save(UUID.randomUUID(),routing("Reason"),null,"*"),"STALE_TASK");
    }
    @ParameterizedTest @ValueSource(strings={"responsibility.action_draft","execution.domain_event","execution.domain_event_outbox","execution.command_receipt","audit.audit_entry"})
    void storage_failure_rolls_back_all_command_writes(String table)throws Exception {
        setup(TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP);var before=counts();
        try(var admin=database.adminConnection();var statement=admin.createStatement()) {
            statement.execute("create function public.task4_fail() returns trigger language plpgsql as 'begin raise exception ''Synthetic Task4 storage failure'' using errcode=''XX000''; end'");
            try {
                statement.execute("create trigger task4_failure before insert or update on "+table+" for each row execute function public.task4_fail()");
                var error=assertThrows(Exception.class,()->run(save(UUID.randomUUID(),routing("Reason"),null,"*")));
                boolean marker=false;for(Throwable cause=error;cause!=null;cause=cause.getCause())if(cause.getMessage()!=null&&cause.getMessage().contains("Synthetic Task4 storage failure"))marker=true;
                assertTrue(marker,"Must reach the selected real storage boundary");
            } finally { statement.execute("drop trigger if exists task4_failure on "+table);statement.execute("drop function public.task4_fail()"); }
        }
        assertEquals(before,counts());assertNull(draft());
    }
    @Test void refresh_reads_exact_task_lead_draft_and_independent_owner_sources_under_query()throws Exception {
        setup(TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP);
        run(save(UUID.randomUUID(),routing("Reason"),null,"*"));
        var tasks=assertDoesNotThrow(CurrentTaskReader::databaseBacked);
        var leads=assertDoesNotThrow(()->CurrentLeadReader.databaseBacked(protection));
        var owners=assertDoesNotThrow(WorkcardOwnerReader::databaseBacked);
        try(var c=database.apiConnection()) { inTransaction(c,Capability.QUERY,x->{
            var current=tasks.read(x,seed.tenant(),task.selector().id());assertEquals(task.selector(),current.selector());assertEquals(task.lead(),current.lead());
            assertEquals(14400,current.slaSeconds());assertEquals("R1_BUSINESS_4H_V1",current.slaCode());assertNotNull(current.slaDueAt());
            var owned=tasks.ownedTasks(x,seed.tenant(),seed.appointment());assertEquals(List.of(current),owned);assertThrows(UnsupportedOperationException.class,()->owned.clear());
            assertTrue(tasks.ownedTasks(x,seed.tenant(),UUID.randomUUID()).isEmpty());
            var lead=leads.read(x,seed.tenant(),task.lead().id());assertEquals(task.lead(),lead.selector());
            assertEquals("Synthetic contact",lead.capturedName());assertEquals("Synthetic enquiry",lead.legalNeedSummary());assertNull(lead.capturedPhone());
            var owner=owners.read(x,seed.tenant(),seed.appointment());
            assertEquals(new AuthorizationService.Subject("identity.appointment",seed.appointment(),0L,null),owner.appointment().selector());
            assertEquals(new AuthorizationService.Subject("identity.principal",seed.principal(),0L,null),owner.principal().selector());
            assertEquals(new AuthorizationService.Subject("identity.organization_unit",seed.org(),0L,null),owner.organization().selector());
            assertEquals("fixture",owner.principal().displayName());assertEquals("fixture",owner.organization().displayName());
            UUID foreign=UUID.randomUUID();assertNull(tasks.read(x,foreign,task.selector().id()));assertNull(leads.read(x,foreign,task.lead().id()));
            assertNull(owners.read(x,foreign,seed.appointment()));assertNull(ActionDraftService.databaseBacked().read(x,foreign,task.selector().id()));
            return null;
        }); }
        mutate("update identity.principal set display_name='New synthetic name',revision=revision+1 where tenant_id=? and principal_id=?",seed.tenant(),seed.principal());
        try(var c=database.apiConnection()) { inTransaction(c,Capability.QUERY,x->{
            var owner=owners.read(x,seed.tenant(),seed.appointment());assertEquals(1L,owner.principal().selector().revision());
            assertEquals("New synthetic name",owner.principal().displayName());assertEquals(0L,owner.appointment().selector().revision());return null;
        }); }
    }
    @Test void secondary_read_ports_preserve_party_assignment_and_contact_source_bindings()throws Exception {
        setup(TaskFactory.Type.CONTACT_LEAD);UUID party=UUID.randomUUID(),contact=UUID.randomUUID();
        mutate("insert into party.party (tenant_id,party_id,party_type,canonical_name,status) values (?,?,'PERSON','Synthetic Party','ACTIVE')",seed.tenant(),party);
        LeadIngressService.Assignment assignment;
        try(var c=database.apiConnection()) { assignment=inTransaction(c,Capability.COMMAND,x->{
            var service=LeadIngressService.databaseBacked(protection);var lead=service.read(x,seed.tenant(),task.lead().id());var now=service.now(x);
            var assigned=service.assign(x,seed.tenant(),lead,seed.appointment(),"MANUAL_SELECTION",now);
            service.update(x,seed.tenant(),lead,null,null,null,seed.appointment(),assigned.selector().id(),now);return assigned;
        }); }
        mutate("insert into lead.lead_contact_result (tenant_id,lead_contact_result_id,lead_id,lead_assignment_id,contact_no,contact_task_id,contact_channel_code,result_code,result_summary,resulted_at,created_at) values (?,?,?,?,1,?,'PHONE','NOT_CONNECTED','Synthetic result','2026-09-06T02:00:00Z','2026-09-06T02:00:00Z')",
                seed.tenant(),contact,task.lead().id(),assignment.selector().id(),task.selector().id());
        var leads=CurrentLeadReader.databaseBacked(protection);
        var sha256=java.security.MessageDigest.getInstance("SHA-256");
        try(var c=database.apiConnection()) { inTransaction(c,Capability.QUERY,x->{
            var named=leads.namedParty(x,seed.tenant(),party);assertEquals(new AuthorizationService.Subject("party.party",party,0L,null),named.selector());assertEquals("Synthetic Party",named.canonicalName());
            var assigned=leads.assignment(x,seed.tenant(),assignment.selector().id());assertEquals(assignment.selector(),assigned.selector());assertEquals(task.lead().id(),assigned.leadId());assertEquals(seed.appointment(),assigned.owner());
            var result=leads.contactResult(x,seed.tenant(),contact);assertEquals(task.selector().id(),result.taskId());assertEquals(task.lead().id(),result.leadId());assertEquals(assignment.selector().id(),result.assignmentId());
            assertEquals("Synthetic result",result.summary());assertEquals("NOT_CONNECTED",result.resultCode());assertEquals(1,result.contactNo());
            assertEquals(R1EventReaders.databaseBacked().contact(x,seed.tenant(),contact).selector(),result.selector());
            // Independent literal row serialization catches omitted/renamed fields in the shared hash mapping.
            String row="{\"contact_channel_code\":\"PHONE\",\"contact_no\":1,\"contact_task_id\":\""+task.selector().id()+"\",\"created_at\":\"2026-09-06T02:00:00.000000Z\",\"evidence_submission_id\":null,\"lead_assignment_id\":\""+assignment.selector().id()+"\",\"lead_contact_result_id\":\""+contact+"\",\"lead_id\":\""+task.lead().id()+"\",\"result_code\":\"NOT_CONNECTED\",\"result_summary\":\"Synthetic result\",\"resulted_at\":\"2026-09-06T02:00:00.000000Z\",\"tenantId\":\""+seed.tenant()+"\"}";
            assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(sha256.digest(row.getBytes(java.nio.charset.StandardCharsets.UTF_8))),result.selector().hash());
            UUID foreign=UUID.randomUUID();assertNull(leads.namedParty(x,foreign,party));assertNull(leads.assignment(x,foreign,assignment.selector().id()));assertNull(leads.contactResult(x,foreign,contact));return null;
        }); }
        mutate("update party.party set canonical_name='Renamed synthetic Party',revision=revision+1 where tenant_id=? and party_id=?",seed.tenant(),party);
        try(var c=database.apiConnection()) { inTransaction(c,Capability.QUERY,x->{var named=leads.namedParty(x,seed.tenant(),party);assertEquals(1L,named.selector().revision());assertEquals("Renamed synthetic Party",named.canonicalName());return null;}); }
    }
    AuthorizationService.Actor otherActor()throws Exception {
        UUID principal=UUID.randomUUID(),appointment=UUID.randomUUID();
        mutate("insert into identity.principal (tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) values (?,?,'HUMAN','SYNTHETIC',?,'Synthetic delegate','ACTIVE',clock_timestamp())",seed.tenant(),principal,CanonicalJson.digest(principal.toString()));
        mutate("insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'OWNER',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),appointment,principal,seed.org());
        return new AuthorizationService.Actor(seed.tenant(),principal,appointment,null,null);
    }
    CommandEnvelope actor(CommandEnvelope e,AuthorizationService.Actor actor) {
        return new CommandEnvelope(e.type(),e.commandId(),e.correlationId(),actor,e.payload(),e.taskPrecondition(),e.draftPrecondition());
    }
    void deny(AuthorizationService.Subject subject)throws Exception {
        try(var c=database.apiConnection()) { inTransaction(c,Capability.COMMAND,x->{
            AuthorizationService.databaseBacked().lockForMutation(x,seed.tenant());
            sql(x,"insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values (?,?,?,?,?,'DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),?,?,?)",
                    seed.tenant(),UUID.randomUUID(),seed.principal(),seed.appointment(),task.type().authority,subject.type(),subject.id(),subject.revision());return null;
        }); }
    }
    @Test void only_current_owner_or_complete_one_hop_delegate_can_save_and_replay()throws Exception {
        setup(TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP);var other=otherActor();var command=save(UUID.randomUUID(),routing("Reason"),null,"*");
        before(actor(command,other),"NOT_AUTHORIZED");
        var represented=new AuthorizationService.Actor(seed.tenant(),other.principalId(),other.appointmentId(),seed.principal(),seed.appointment());
        before(actor(command,represented),"NOT_AUTHORIZED");
        mutate("insert into identity.delegation_grant (tenant_id,delegation_grant_id,source_authority_grant_id,delegator_appointment_id,delegate_appointment_id,scope_organization_unit_id,valid_from,state,created_at) values (?,?,?,?,?,?,clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",
                seed.tenant(),UUID.randomUUID(),seed.grant(),seed.appointment(),other.appointmentId(),seed.org());
        var delegated=actor(command,represented);var outcome=run(delegated);assertEquals(CommandOutcome.Status.SUCCEEDED,outcome.status());assertEquals(outcome,run(delegated));
        var service=new AuthorizationService.Actor(seed.tenant(),other.principalId(),other.appointmentId(),null,null,AuthorizationService.PrincipalKind.SERVICE);
        assertThrows(IllegalArgumentException.class,()->actor(command,service));
        revoke();before(delegated,"NOT_AUTHORIZED");
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void current_task_and_lead_deny_block_save_and_existing_key_replay(boolean onTask)throws Exception {
        setup(TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP);var e=save(UUID.randomUUID(),routing("Reason"),null,"*");run(e);
        deny(onTask?task.selector():task.lead());before(e,"NOT_AUTHORIZED");before(save(UUID.randomUUID(),routing("Changed"),tag(),null),"NOT_AUTHORIZED");
    }
    void revoke()throws Exception {
        try(var c=database.apiConnection()) { inTransaction(c,Capability.COMMAND,x->{
            AuthorizationService.databaseBacked().lockForMutation(x,seed.tenant());
            sql(x,"update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='SYNTHETIC',revision=revision+1 where tenant_id=? and authority_grant_id=?",seed.tenant(),seed.grant());
            return null;
        }); }
    }
    static void awaitBlocked(Connection observer,int pid)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<until) {
            try(var p=observer.prepareStatement("select exists(select 1 from pg_locks where pid=? and not granted and locktype='advisory')")) {
                p.setInt(1,pid);try(var r=p.executeQuery()) { r.next();if(r.getBoolean(1))return; }
            }
            Thread.onSpinWait();
        }
        fail("Expected real business-fence wait");
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void authority_expiry_or_revocation_while_waiting_on_fence_leaves_no_slot(boolean expire)throws Exception {
        setup(TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP);var before=counts();var e=save(UUID.randomUUID(),routing("Reason"),null,"*");
        if(expire) {
            revoke();UUID grant=UUID.randomUUID();
            mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,valid_until,state,created_at) values (?,?,?,?,?,?,clock_timestamp()-interval '1 day',clock_timestamp()+interval '3 seconds','ACTIVE',clock_timestamp())",
                    seed.tenant(),grant,seed.appointment(),seed.appointment(),seed.org(),task.type().authority);
            seed=new AuthorizationServiceIT.Seed(seed.tenant(),seed.principal(),seed.appointment(),seed.org(),grant,seed.subject());
        }
        try(var holder=database.apiConnection();var command=database.apiConnection();var pool=Executors.newSingleThreadExecutor()) {
            int pid;try(var s=command.createStatement();var r=s.executeQuery("select pg_backend_pid()")) { r.next();pid=r.getInt(1); }
            holder.setAutoCommit(false);setLocalRole(holder,Capability.COMMAND);R1BusinessFence.databaseBacked().exclusive(holder,seed.tenant());
            var future=pool.submit(()->runtime.execute(command,e));
            try {
                awaitBlocked(holder,pid);
                if(expire) {
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean expired=false;
                    while(System.nanoTime()<deadline&&!expired) {
                        try(var p=holder.prepareStatement("select clock_timestamp()>=valid_until from identity.authority_grant where tenant_id=? and authority_grant_id=?")) {
                            p.setObject(1,seed.tenant());p.setObject(2,seed.grant());try(var r=p.executeQuery()) { assertTrue(r.next());expired=r.getBoolean(1); }
                        }
                        if(!expired)Thread.sleep(20);
                    }
                    assertTrue(expired,"The real clock must cross the immutable grant expiry before releasing the fence");
                } else revoke();
            } finally { holder.commit(); }
            var error=assertThrows(ExecutionException.class,()->future.get(15,TimeUnit.SECONDS));assertEquals("NOT_AUTHORIZED",assertInstanceOf(CommandHandler.Rejected.class,error.getCause()).code());
        }
        assertEquals(before,counts());assertNull(draft());
    }
    @Test void final_authorization_after_real_draft_write_rolls_back_candidate()throws Exception {
        setup(TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP);var before=counts();var e=save(UUID.randomUUID(),routing("Reason"),null,"*");
        try(var actual=database.apiConnection();var pool=Executors.newSingleThreadExecutor()) {
            var written=new CountDownLatch(1);var resume=new CountDownLatch(1);
            var future=pool.submit(()->runtime.execute(beforeFinalRead(actual,written,resume),e));
            try { assertTrue(written.await(15,TimeUnit.SECONDS));deny(task.selector()); } finally { resume.countDown(); }
            var outcome=assertInstanceOf(CommandOutcome.class,future.get(15,TimeUnit.SECONDS));assertEquals(CommandOutcome.Status.REJECTED,outcome.status());assertEquals("NOT_AUTHORIZED",outcome.rejectionCode());
        }
        var expected=new ArrayList<>(before);for(int i:List.of(3,4,8))expected.set(i,expected.get(i)+1);assertEquals(expected,counts());assertNull(draft());
    }
    /** Observes the actual connection's post-write role transition; production handlers and fact readers remain real. */
    static Connection beforeFinalRead(Connection actual,CountDownLatch written,CountDownLatch resume) {
        int[] queryRoles={0};
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            try {
                Object value=method.invoke(actual,args);
                if(!method.getName().equals("createStatement"))return value;
                var statement=(Statement)value;
                return Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class<?>[]{Statement.class},(p,m,a)->{
                    if(m.getName().equals("execute")&&a!=null&&"SET LOCAL ROLE law_app_query".equals(a[0])&&++queryRoles[0]==3) {
                        written.countDown();if(!resume.await(15,TimeUnit.SECONDS))throw new SQLException("Synthetic Task4 barrier timeout");
                    }
                    try { return m.invoke(statement,a); } catch(InvocationTargetException error) { throw error.getCause(); }
                });
            } catch(InvocationTargetException error) { throw error.getCause(); }
        });
    }
    @Test void competing_creates_and_edits_have_one_success_and_one_stale_receipt()throws Exception {
        setup(TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP);
        for(boolean create:List.of(true,false)) {
            String match=create?null:tag();var a=save(UUID.randomUUID(),routing("First "+create),match,create?"*":null);
            var b=save(UUID.randomUUID(),routing("Second "+create),match,create?"*":null);var start=new CountDownLatch(1);
            try(var pool=Executors.newFixedThreadPool(2)) {
                var left=pool.submit(()->{start.await();return run(a);});var right=pool.submit(()->{start.await();return run(b);});start.countDown();
                var results=List.of(left.get(15,TimeUnit.SECONDS),right.get(15,TimeUnit.SECONDS));
                assertEquals(1,results.stream().filter(r->r.status()==CommandOutcome.Status.SUCCEEDED).count());
                assertEquals(1,results.stream().filter(r->"STALE_DRAFT".equals(r.rejectionCode())).count());
                assertEquals(create?0L:1L,draft().selector().revision());
            }
        }
        assertEquals(List.of(1L,1L,1L,4L,4L,2L,2L,0L,4L),counts());
    }
    @Test void same_key_competing_creates_replay_one_receipt_with_zero_duplicate_effects()throws Exception {
        setup(TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP);var e=save(UUID.randomUUID(),routing("Reason"),null,"*");var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{start.await();return run(e);});var b=pool.submit(()->{start.await();return run(e);});start.countDown();
            assertEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
        }
        assertEquals(List.of(1L,1L,1L,1L,1L,1L,1L,0L,1L),counts());
    }
    @Test void confirmation_alone_does_not_complete_task_and_confirmed_draft_cannot_be_edited()throws Exception {
        setup(TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP);var values=routing("Reason");run(save(UUID.randomUUID(),values,null,"*"));var d=draft();
        try(var c=database.apiConnection()) { inTransaction(c,Capability.COMMAND,x->{
            ActionDraftService.databaseBacked().confirm(x,seed.tenant(),task,new ActionDraftService.Confirmation(d.selector().id(),0,d.digest()),values,seed.appointment(),Instant.now());
            assertEquals(task,TaskFactory.databaseBacked().read(x,seed.tenant(),task.selector().id()));return null;
        }); }
        terminal(save(UUID.randomUUID(),routing("Changed"),tag(),null),"DRAFT_DIGEST_MISMATCH");
    }
    @Test void real_primary_rejects_saved_revision_digest_payload_and_foreign_draft_mismatches()throws Exception {
        setup(TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP);var values=routing("Reason");run(save(UUID.randomUUID(),values,null,"*"));var d=draft();
        var payload=new TreeMap<>(values);payload.put("draftId",d.selector().id().toString());payload.put("expectedDraftRevision",0L);payload.put("draftDigest",d.digest());
        for(String mismatch:List.of("revision","digest","values","foreign")) {
            var changed=new TreeMap<>(payload);
            switch(mismatch) {
                case "revision" -> changed.put("expectedDraftRevision",1L);
                case "digest" -> changed.put("draftDigest","AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
                case "values" -> changed.put("rationaleSummary","Other reason");
                default -> changed.put("draftId",UUID.randomUUID().toString());
            }
            var main=new CommandEnvelope(CommandEnvelope.Type.RECORD_ROUTING_DISPOSITION,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),changed,
                    new CommandEnvelope.TaskPrecondition(task.selector().id(),R1ResourceTags.task(seed.request().actor(),task.selector(),task.state())));
            if(mismatch.equals("foreign"))before(main,"NOT_FOUND");
            else terminal(main,mismatch.equals("revision")?"STALE_DRAFT":"DRAFT_DIGEST_MISMATCH");
        }
    }
}
