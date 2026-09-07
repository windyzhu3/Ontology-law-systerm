package io.github.windyzhu3.ontologylaw.api;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;
import io.github.windyzhu3.ontologylaw.api.adapter.generated.model.ConsumeR1ProjectionV1;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.lead.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.util.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class R1ProjectionConsumerIT extends ContactFlowFixture {
    @Test void cross_tenant_mixed_event_expired_and_wrong_owner_claims_are_read_only_rejections()throws Exception{
        setup(CommandHandler.Event.LeadCapturedV1);var actor=service("R1_PROJECTION_CONSUME");var claim=claim(CommandHandler.Event.LeadCapturedV1);var before=snapshots();
        var other=io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.seed(database,"SERVICE","R1_PROJECTION_CONSUME");var otherActor=new Actor(other.tenant(),other.principal(),other.appointment(),null,null,PrincipalKind.SERVICE);
        assertEquals(404,consume(otherActor,dto(claim)).status());var mixed=dto(claim);mixed.setDomainEventId(UUID.randomUUID());assertEquals(422,consume(actor,mixed).status());var owner=dto(claim);owner.setLeaseOwner("OTHER_WORKER");assertEquals(409,consume(actor,owner).status());assertEquals(before,snapshots());
        fault("update execution.domain_event_outbox set lease_until=clock_timestamp()-interval '1 second' where tenant_id=? and domain_event_outbox_id=?",seed.tenant(),claim.outboxId());before=snapshots();assertEquals(409,consume(actor,dto(claim)).status());assertEquals(before,snapshots());
    }
    @Test void commit_acknowledgement_failure_cannot_escape_as_204()throws Exception{
        setup(CommandHandler.Event.LeadCapturedV1);var actor=service("R1_PROJECTION_CONSUME");var claim=claim(CommandHandler.Event.LeadCapturedV1);var before=snapshots();
        try(var raw=database.apiConnection()){
            var failing=(java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(java.sql.Connection.class.getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{if(method.getName().equals("commit"))throw new java.sql.SQLException("Synthetic commit acknowledgement failure");try{return method.invoke(raw,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}});
            assertEquals(503,new R1ProjectionConsumer(policies).consume(failing,actor,dto(claim)).status());assertTrue(raw.getAutoCommit());
        }assertEquals(before,snapshots());
    }
    void fault(String query,Object... args)throws Exception{try(var c=database.adminConnection()){c.setAutoCommit(false);sql(c,"set local session_replication_role=replica");sql(c,query,args);c.commit();}}
    @Test void missing_source_is_404_and_stale_claim_uses_frozen_internal_code()throws Exception{
        setup(CommandHandler.Event.LeadCapturedV1);var actor=service("R1_PROJECTION_CONSUME");var claim=claim(CommandHandler.Event.LeadCapturedV1);
        fault("update execution.domain_event set source_fact_id=? where tenant_id=? and domain_event_id=?",UUID.randomUUID(),seed.tenant(),claim.eventId());var before=snapshots();assertEquals(404,consume(actor,dto(claim)).status());assertEquals(before,snapshots());
    }
    @Test void stale_claim_uses_exact_internal_code_and_oversized_owner_is_validation_error()throws Exception{
        setup(CommandHandler.Event.LeadCapturedV1);var actor=service("R1_PROJECTION_CONSUME");var claim=claim(CommandHandler.Event.LeadCapturedV1);var stale=dto(claim);stale.setExpectedOutboxRevision(claim.revision()+1);assertEquals("STALE_OUTBOX_CLAIM",consume(actor,stale).errorCode());var oversized=dto(claim);oversized.setLeaseOwner("X".repeat(65));assertEquals(400,consume(actor,oversized).status());
    }
    @Test void malformed_registry_payload_source_and_queue_are_permanent_without_business_writes()throws Exception{
        var changes=List.of("event_type='UnknownV1'","event_schema_version=2","event_payload='{\"unexpected\":true}'::jsonb","payload_digest=decode(repeat('00',32),'hex')","source_fact_revision=9007199254740991","source_fact_type='responsibility.task_occurrence'","source_fact_hash=decode(repeat('00',32),'hex'),source_fact_revision=null");
        for(var change:changes){setup(CommandHandler.Event.LeadCapturedV1);var actor=service("R1_PROJECTION_CONSUME");var claim=claim(CommandHandler.Event.LeadCapturedV1);fault("update execution.domain_event set "+change+" where tenant_id=? and domain_event_id=?",seed.tenant(),claim.eventId());var before=snapshots();assertEquals(422,consume(actor,dto(claim)).status());assertEquals(before,snapshots());}
        setup(CommandHandler.Event.LeadCapturedV1);var actor=service("R1_PROJECTION_CONSUME");var claim=claim(CommandHandler.Event.LeadCapturedV1);fault("update execution.domain_event_outbox set queue_owner='OTHER' where tenant_id=? and domain_event_outbox_id=?",seed.tenant(),claim.outboxId());assertEquals(422,consume(actor,dto(claim)).status());
    }
    @Test void draft_notification_after_confirmation_and_opportunity_after_revision_advance_are_current_read_only()throws Exception{
        setupFlow(TaskFactory.Type.CONTACT_LEAD);var draft=saveDraft(contact("NOT_CONNECTED"),false);emit(CommandHandler.Event.ActionDraftSavedV1,draft.selector());
        var payload=new TreeMap<String,Object>(contact("NOT_CONNECTED"));payload.put("draftId",draft.selector().id().toString());payload.put("expectedDraftRevision",draft.selector().revision());payload.put("draftDigest",draft.digest());assertEquals(CommandOutcome.Status.SUCCEEDED,execute(new CommandEnvelope(CommandEnvelope.Type.RECORD_CONTACT_RESULT,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),payload,new CommandEnvelope.TaskPrecondition(current.selector().id(),R1ResourceTags.task(seed.request().actor(),current.selector(),current.state())))).status());
        var actor=service("R1_PROJECTION_CONSUME");var claim=claim(CommandHandler.Event.ActionDraftSavedV1);var before=snapshots();assertEquals(204,consume(actor,dto(claim)).status());assertEquals(before,snapshots());assertEquals("CONFIRMED",scalar("select state from responsibility.action_draft where tenant_id=? and action_draft_id=?",seed.tenant(),draft.selector().id()));
        setup(CommandHandler.Event.OpportunityOpened);actor=service("R1_PROJECTION_CONSUME");claim=claim(CommandHandler.Event.OpportunityOpened);fault("update opportunity.opportunity set revision=revision+1 where tenant_id=?",seed.tenant());before=snapshots();assertEquals(204,consume(actor,dto(claim)).status());assertEquals(before,snapshots());
    }
    @Test void current_card_reads_committed_facts_without_worker_or_delivered_outbox()throws Exception{
        setup(CommandHandler.Event.LeadCapturedV1);var first=readCard(null);assertEquals(200,first.status());var claim=claim(CommandHandler.Event.LeadCapturedV1);assertTrue(R1ProjectionOutboxPort.databaseBacked(database::workerConnection).exhaust(claim,"PROJECTION_EVENT_INVALID"));var after=readCard(null);assertEquals(200,after.status());assertEquals(first.etag(),after.etag());assertArrayEquals(CanonicalJson.digest(CanonicalJson.encode(first.body())),CanonicalJson.digest(CanonicalJson.encode(after.body())));
    }
    @Test void contact_summary_is_read_only_after_current_authorization_and_never_logged_or_returned()throws Exception{
        setupFlow(TaskFactory.Type.CONTACT_LEAD);var values=new TreeMap<>(contact("NOT_CONNECTED"));String summary="Projection summary must remain inside its Owner";values.put("resultSummary",summary);assertEquals(CommandOutcome.Status.SUCCEEDED,execute(prepare(values)).status());var actor=service("R1_PROJECTION_CONSUME");var claim=claim(CommandHandler.Event.LeadContactResultRecordedV1);
        var reads=new java.util.concurrent.atomic.AtomicInteger();var logs=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();logs.start();var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);logger.addAppender(logs);
        try(var raw=database.apiConnection()){
            var observed=(java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(java.sql.Connection.class.getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{if(method.getName().equals("prepareStatement")&&args[0] instanceof String query&&query.contains("lead_contact_result")&&query.contains("result_summary"))reads.incrementAndGet();try{return method.invoke(raw,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}});
            var consumer=new R1ProjectionConsumer(policies);assertEquals(403,consumer.consume(observed,service("CONTACT_TASK_RECOVER"),dto(claim)).status());assertEquals(0,reads.get());var response=consumer.consume(observed,actor,dto(claim));assertEquals(204,response.status());assertEquals(1,reads.get());assertFalse(response.toString().contains(summary));
            fault("update execution.domain_event set source_fact_hash=decode(repeat('00',32),'hex') where tenant_id=? and domain_event_id=?",seed.tenant(),claim.eventId());assertEquals(422,consumer.consume(observed,actor,dto(claim)).status());assertEquals(2,reads.get());
            mutate("insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values (?,?,?,?,'R1_PROJECTION_CONSUME','DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),'lead.lead',?,?)",seed.tenant(),UUID.randomUUID(),actor.principalId(),seed.appointment(),current.lead().id(),Long.valueOf(scalar("select revision::text from lead.lead where tenant_id=? and lead_id=?",seed.tenant(),current.lead().id())));assertEquals(204,new R1ProjectionReadinessService(policies).check(observed,actor).status());assertEquals(403,consumer.consume(observed,actor,dto(claim)).status());assertEquals(2,reads.get());assertTrue(logs.list.stream().noneMatch(e->e.getFormattedMessage().contains(summary)));
        }finally{logger.detachAppender(logs);logs.stop();}
    }
    @Test void retained_contact_and_decision_scope_survives_departed_historical_owner_but_service_must_remain_active()throws Exception{
        for(var event:List.of(CommandHandler.Event.LeadContactResultRecordedV1,CommandHandler.Event.LeadRoutingDispositionRecordedV1)){
            setup(event);var actor=service("R1_PROJECTION_CONSUME");var claim=claim(event);
            mutate("update identity.appointment set state='SUSPENDED',revision=revision+1 where tenant_id=? and appointment_id=?",seed.tenant(),seed.appointment());var before=snapshots();assertEquals(204,consume(actor,dto(claim)).status());assertEquals(before,snapshots());
            mutate("update identity.appointment set state='SUSPENDED',revision=revision+1 where tenant_id=? and appointment_id=?",seed.tenant(),actor.appointmentId());assertEquals(403,consume(actor,dto(claim)).status());
        }
    }
    void emit(CommandHandler.Event event,Subject source)throws Exception {
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{UUID id=UUID.randomUUID();
            sql(x,"insert into execution.domain_event (tenant_id,domain_event_id,event_type,event_schema_version,event_payload,payload_digest,command_id,correlation_id,occurred_at,source_fact_type,source_fact_id,source_fact_revision,source_fact_hash) values (?,?,?,1,'{}',decode('44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a','hex'),?,?,clock_timestamp(),?,?,?,?)",seed.tenant(),id,event.name(),UUID.randomUUID(),UUID.randomUUID(),source.type(),source.id(),source.revision(),source.hash()==null?null:Base64.getUrlDecoder().decode(source.hash()));
            sql(x,"insert into execution.domain_event_outbox (tenant_id,domain_event_outbox_id,domain_event_id,queue_owner,status,available_at) values (?,?,?,'R1_PROJECTION','PENDING',clock_timestamp())",seed.tenant(),UUID.randomUUID(),id);return null;
        });}
    }
    void setup(CommandHandler.Event event)throws Exception {
        TaskFactory.Type type=switch(event) {
            case LeadIngressCompletedV1 -> TaskFactory.Type.COMPLETE_LEAD_INGRESS;
            case LeadDuplicateResolutionRecordedV1 -> TaskFactory.Type.RESOLVE_LEAD_DUPLICATE;
            case LeadRoutingDispositionRecordedV1,SourceIntakeStopRequestedV1,RoutingReviewTaskReopenedV1 -> TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP;
            case SourceIntakeStopRequestAcknowledgedV1 -> TaskFactory.Type.ACK_SOURCE_INTAKE_STOP_REQUEST;
            case LeadAssignedV1 -> TaskFactory.Type.ASSIGN_LEAD;
            default -> TaskFactory.Type.CONTACT_LEAD;
        };
        setupFlow(type);
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{for(String code:List.of("LEAD_CAPTURE","LEAD_ASSIGN","LEAD_ROUTING_DECIDE","SOURCE_INTAKE_REQUEST_ACK"))grant(x,code);return null;});}
        switch(event) {
            case LeadCapturedV1 -> emit(event,current.lead());
            case ActionDraftSavedV1 -> {var draft=saveDraft(contact("NOT_CONNECTED"),false);emit(event,draft.selector());saveDraft(contact("SUSPECT_INVALID"),false);}
            case ContactTaskReopenedV1,RoutingReviewTaskReopenedV1 -> {
                Subject source;try(var c=database.apiConnection()){source=inTransaction(c,Capability.COMMAND,x->{var tasks=TaskFactory.databaseBacked();tasks.waitUntil(x,seed.tenant(),current,seed.appointment(),businessAt.plusSeconds(3600),businessAt);return tasks.reopen(x,seed.tenant(),tasks.read(x,seed.tenant(),current.selector().id())).selector();});}emit(event,source);cancelCurrent();
            }
            case LeadIngressCompletedV1 -> assertEquals(CommandOutcome.Status.SUCCEEDED,execute(prepare(Map.of("phone","+12025550124","sourceCode","OWNER_CONFIRMED","sourceSummary","Fixture completion"))).status());
            case LeadAssignedV1 -> assertEquals(CommandOutcome.Status.SUCCEEDED,execute(prepare(Map.of("ownerAppointmentId",secondaryAppointment.toString()))).status());
            case LeadDuplicateResolutionRecordedV1 -> assertEquals(CommandOutcome.Status.SUCCEEDED,execute(prepare(Map.of("decisionCode","KEEP_SEPARATE","candidateLeadId",secondaryLead.toString(),"candidateLeadRevision",1L,"partyId",secondaryParty.toString(),"partyRevision",0L,"rationaleSummary","Fixture duplicate"))).status());
            case LeadRoutingDispositionRecordedV1,SourceIntakeStopRequestedV1 -> assertEquals(CommandOutcome.Status.SUCCEEDED,execute(prepare(Map.of("decisionCode",event==CommandHandler.Event.SourceIntakeStopRequestedV1?"REQUEST_SOURCE_INTAKE_STOP":"SCHEDULE_ROUTING_REVIEW","rationaleSummary","Fixture routing"))).status());
            case SourceIntakeStopRequestAcknowledgedV1 -> assertEquals(CommandOutcome.Status.SUCCEEDED,execute(prepare(Map.of("causalDecisionId",secondaryFact.id().toString(),"causalDecisionHash",secondaryFact.hash(),"rationaleSummary","Fixture acknowledgement"))).status());
            case LeadContactResultRecordedV1,OpportunityOpened -> assertEquals(CommandOutcome.Status.SUCCEEDED,execute(prepare(contact("CONNECTED_VALID"))).status());
            case LeadContactRetryExhaustedV1 -> {for(int n=1;n<=3;n++){assertEquals(CommandOutcome.Status.SUCCEEDED,execute(prepare(contact("NOT_CONNECTED"))).status());if(n<3)recoverContact();}}
            case LeadValidityReviewedV1 -> {var contact=execute(prepare(contact("SUSPECT_INVALID")));selectTask(TaskFactory.Type.REVIEW_LEAD_VALIDITY);businessAt=businessAt.plusSeconds(10);assertEquals(CommandOutcome.Status.SUCCEEDED,execute(prepare(review(contact,"CONFIRM_INVALID"))).status());}
        }
    }
    R1ProjectionOutboxPort.Claim claim(CommandHandler.Event type)throws Exception {
        var port=R1ProjectionOutboxPort.databaseBacked(database::workerConnection);
        for(int batch=0;batch<5;batch++)for(var claim:port.claim(seed.tenant(),"CONSUMER_IT",4))
            if(type.name().equals(scalar("select event_type from execution.domain_event where tenant_id=? and domain_event_id=?",seed.tenant(),claim.eventId())))return claim;
        throw new AssertionError("Fixture event was not claimable: "+type);
    }
    ConsumeR1ProjectionV1 dto(R1ProjectionOutboxPort.Claim claim){return new ConsumeR1ProjectionV1(claim.outboxId(),claim.eventId(),claim.revision(),claim.leaseOwner(),claim.fencingToken());}
    R1ProjectionConsumer.Response consume(Actor actor,ConsumeR1ProjectionV1 dto)throws Exception {try(var c=database.apiConnection()){return new R1ProjectionConsumer(policies).consume(c,actor,dto);}}
    List<String> snapshots()throws Exception {
        var result=new ArrayList<String>();try(var c=database.adminConnection()){for(String table:List.of("lead.lead","lead.lead_assignment","lead.lead_contact_result","opportunity.opportunity","responsibility.task_occurrence","responsibility.action_draft","responsibility.decision_record","responsibility.wait_receipt","execution.command_execution_slot","execution.command_receipt","execution.domain_event","execution.domain_event_outbox","audit.audit_entry_classified_v"))
            try(var statement=c.prepareStatement("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text),'[]'::jsonb)::text from "+table+" t where tenant_id=?")){statement.setObject(1,seed.tenant());try(var rows=statement.executeQuery()){rows.next();result.add(HexFormat.of().formatHex(CanonicalJson.digest(rows.getString(1))));}}}return result;
    }
    @ParameterizedTest @EnumSource(CommandHandler.Event.class)
    void all_fourteen_routes_read_real_current_facts_with_zero_business_delta(CommandHandler.Event event)throws Exception {
        setup(event);var actor=service("R1_PROJECTION_CONSUME");var claim=claim(event);var before=snapshots();
        assertEquals(204,consume(actor,dto(claim)).status(),event.name());assertEquals(before,snapshots());
        assertEquals(204,consume(actor,dto(claim)).status());assertEquals(before,snapshots());
        assertTrue(R1ProjectionOutboxPort.databaseBacked(database::workerConnection).ack(claim));
        assertEquals(409,consume(actor,dto(claim)).status());
    }
    @Test void authorization_is_403_and_stale_claim_is_409_without_consumption_writes()throws Exception {
        setup(CommandHandler.Event.LeadCapturedV1);var actor=service("R1_PROJECTION_CONSUME");var claim=claim(CommandHandler.Event.LeadCapturedV1);
        assertEquals(403,consume(service("CONTACT_TASK_RECOVER"),dto(claim)).status());assertEquals(403,consume(seed.request().actor(),dto(claim)).status());
        var stale=dto(claim);stale.setFencingToken(claim.fencingToken()+1);assertEquals(409,consume(actor,stale).status());
        var missing=dto(claim);missing.setDomainEventOutboxId(UUID.randomUUID());assertEquals(404,consume(actor,missing).status());
        assertEquals(204,consume(actor,dto(claim)).status());
    }
    @Test void delayed_reopened_event_accepts_a_later_wait_cycle_without_reviving_current_waiting_task()throws Exception{
        for(var type:List.of(TaskFactory.Type.CONTACT_LEAD,TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP)){
            setupFlow(type);var event=type==TaskFactory.Type.CONTACT_LEAD?CommandHandler.Event.ContactTaskReopenedV1:CommandHandler.Event.RoutingReviewTaskReopenedV1;
            Subject old;try(var c=database.apiConnection()){old=inTransaction(c,Capability.COMMAND,x->{var tasks=TaskFactory.databaseBacked();tasks.waitUntil(x,seed.tenant(),current,seed.appointment(),businessAt.plusSeconds(3600),businessAt);return tasks.reopen(x,seed.tenant(),tasks.read(x,seed.tenant(),current.selector().id())).selector();});}emit(event,old);
            try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{var tasks=TaskFactory.databaseBacked();tasks.waitUntil(x,seed.tenant(),tasks.read(x,seed.tenant(),current.selector().id()),seed.appointment(),businessAt.plusSeconds(7200),businessAt.plusSeconds(3601));return null;});}
            var actor=service("R1_PROJECTION_CONSUME");var claim=claim(event);var before=snapshots();assertEquals(204,consume(actor,dto(claim)).status());assertEquals(before,snapshots());assertEquals("WAITING",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),current.selector().id()));
        }
    }
}
