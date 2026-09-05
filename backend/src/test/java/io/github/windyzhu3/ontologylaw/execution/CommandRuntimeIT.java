package io.github.windyzhu3.ontologylaw.execution;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.testing.PostgresIntegrationTest;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CommandRuntimeIT extends PostgresIntegrationTest {
    enum Mode { SUCCESS, NO_CHANGE, REJECT_AFTER_WRITE, TECHNICAL }
    class Handler implements CommandHandler {
        final AuthorizationServiceIT.Seed seed;final UUID task=UUID.randomUUID();Mode mode=Mode.SUCCESS;
        Handler() throws Exception {this(AuthorizationServiceIT.seed(database));}
        Handler(AuthorizationServiceIT.Seed seed) throws Exception {this(seed,"COMPLETE_LEAD_INGRESS","COMPLETE_LEAD_INGRESS","lead.lead");}
        Handler(AuthorizationServiceIT.Seed seed,String purpose,String primary,String completionType) throws Exception {
            this.seed=seed;
            try(var c=database.apiConnection()) {inTransaction(c,Capability.COMMAND,x->{
                sql(x,"insert into lead.lead (tenant_id,lead_id,source_channel_code,source_account_code,source_record_key_digest,captured_at,service_category_code,jurisdiction_code,urgency_code,legal_need_summary_ciphertext,captured_content_digest,party_resolution_code,disposition_code,created_at) values (?,?,'FIXTURE','FIXTURE',?,clock_timestamp(),'FIXTURE','FIXTURE','FIXTURE',decode('01','hex'),decode(repeat('00',32),'hex'),'UNRESOLVED','CAPTURED',clock_timestamp())",seed.tenant(),seed.subject(),CanonicalJson.digest(seed.subject().toString()));
                sql(x,"insert into responsibility.task_occurrence (tenant_id,task_occurrence_id,owner_appointment_id,business_purpose_code,primary_command_code,expected_completion_fact_type,original_sla_code,original_sla_seconds,original_sla_due_at,state,created_at,subject_type,subject_id,subject_revision) values (?,?,?,?,?,?,'R1_BUSINESS_4H_V1',14400,clock_timestamp()+interval '4 hours','OPEN',clock_timestamp(),'lead.lead',?,0)",seed.tenant(),task,seed.appointment(),purpose,primary,completionType,seed.subject());return null;
            });}
        }
        public CommandEnvelope.Type type(){return CommandEnvelope.Type.COMPLETE_LEAD_INGRESS;}
        CommandEnvelope envelope(UUID command,Object payload){return new CommandEnvelope(type(),command,UUID.randomUUID(),seed.request().actor(),payload);}
        public Context resolve(Connection c,CommandEnvelope e)throws SQLException {
            assertEquals("law_app_query",scalar(c,"select current_user"));
            try(var p=c.prepareStatement("select subject_revision from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?")) {
                p.setObject(1,e.actor().tenantId());p.setObject(2,task);
                try(var r=p.executeQuery()){if(!r.next())throw new Rejected("NOT_FOUND");}
            }
            return new Context(CommandScope.task(e.actor().tenantId(),type(),task,seed.request().subject(),Map.of()),seed.request());
        }
        public void lockRoots(Connection c,CommandEnvelope e,Context ctx)throws SQLException {
            assertEquals("law_app_command",scalar(c,"select current_user"));
            for(String query:List.of("select lead_id from lead.lead where tenant_id=? and lead_id=? for update","select task_occurrence_id from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=? for update")) {
                try(var p=c.prepareStatement(query)){p.setObject(1,e.actor().tenantId());p.setObject(2,query.contains("lead.lead")?seed.subject():task);p.executeQuery().close();}
            }
        }
        public void recoveryEligibility(Connection c,CommandEnvelope e,Context ctx)throws SQLException{throw new AssertionError("Primary cannot use recovery gate");}
        public void validateBeforeWork(Connection c,CommandEnvelope e,Context ctx)throws SQLException {
            if(!"0".equals(scalar(c,"select revision from lead.lead where tenant_id='"+seed.tenant()+"' and lead_id='"+seed.subject()+"'")))throw new Rejected("STALE_SUBJECT");
        }
        public Result execute(Connection c,CommandEnvelope e,Context ctx)throws SQLException {
            sql(c,"update lead.lead set disposition_code='KEEP_SEPARATE',revision=revision+1 where tenant_id=? and lead_id=?",seed.tenant(),seed.subject());
            if(mode==Mode.TECHNICAL)throw new SQLException("Injected technical failure","08006");
            if(mode==Mode.REJECT_AFTER_WRITE)throw new Rejected("STALE_TASK");
            if(mode==Mode.NO_CHANGE)return Result.noChange(seed.request().subject());
            var fact=new AuthorizationService.Subject("lead.lead",seed.subject(),1L,null);
            sql(c,"update responsibility.task_occurrence set state='DONE',revision=revision+1,completed_at=clock_timestamp(),completion_fact_type='lead.lead',completion_fact_id=?,completion_fact_revision=1 where tenant_id=? and task_occurrence_id=?",seed.subject(),seed.tenant(),task);
            return Result.succeeded(fact,Event.LeadIngressCompletedV1);
        }
        public void validateBeforeCommit(Connection c,CommandEnvelope e,Context ctx,Result result)throws SQLException {
            assertEquals("law_app_query",scalar(c,"select current_user"));
            assertEquals(result.fact().revision().toString(),scalar(c,"select revision from lead.lead where tenant_id='"+seed.tenant()+"' and lead_id='"+seed.subject()+"'"));
        }
    }
    static String scalar(Connection c,String query)throws SQLException {try(var s=c.createStatement();var r=s.executeQuery(query)){assertTrue(r.next());return r.getString(1);}}
    CommandRuntime runtime(Handler h){return new CommandRuntime(List.of(h),AuthorizationService.databaseBacked(),"POSTGRES_IT");}
    CommandResult runResult(CommandRuntime runtime,CommandEnvelope e)throws Exception {try(var c=database.apiConnection()){return runtime.execute(c,e);}}
    CommandOutcome run(CommandRuntime runtime,CommandEnvelope e)throws Exception {return assertInstanceOf(CommandOutcome.class,runResult(runtime,e));}
    List<Long> counts(Handler h)throws Exception {
        try(var c=database.apiConnection()) {return inTransaction(c,Capability.QUERY,x->{
            List<Long> result=new ArrayList<>();
            for(String table:List.of("execution.command_execution_slot","execution.command_receipt","audit.audit_entry_classified_v","execution.domain_event","execution.domain_event_outbox"))result.add(Long.parseLong(scalar(x,"select count(*) from "+table+" where tenant_id='"+h.seed.tenant()+"'")));
            result.add(Long.parseLong(scalar(x,"select revision from lead.lead where tenant_id='"+h.seed.tenant()+"' and lead_id='"+h.seed.subject()+"'")));return result;
        });}
    }
    @Test void commits_slot_fact_task_audit_event_outbox_receipt_then_replays_after_state_advance()throws Exception {
        Handler h=new Handler();var runtime=runtime(h);var e=h.envelope(UUID.randomUUID(),Map.of("phone","+123"));
        var first=run(runtime,e);assertEquals(CommandOutcome.Status.SUCCEEDED,first.status());assertEquals(h.seed.subject(),first.resultFact().id());assertEquals(1L,first.resultFact().revision());assertEquals(7,first.receiptId().version());
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
            assertEquals("lead.lead:"+h.seed.subject()+":1:R1_PROJECTION",scalar(x,"select e.source_fact_type||':'||e.source_fact_id||':'||e.source_fact_revision||':'||o.queue_owner from execution.domain_event e join execution.domain_event_outbox o on o.tenant_id=e.tenant_id and o.domain_event_id=e.domain_event_id where e.tenant_id='"+h.seed.tenant()+"'"));
            assertEquals("DONE:1:"+h.seed.subject()+":1",scalar(x,"select state||':'||revision||':'||completion_fact_id||':'||completion_fact_revision from responsibility.task_occurrence where tenant_id='"+h.seed.tenant()+"' and task_occurrence_id='"+h.task+"'"));
            assertEquals("DIRECT:SOURCE_INTAKE_OWNER:identity.authority_grant:"+h.seed.grant()+":0:32",scalar(x,"select authorization_path_code||':'||authorization_slot_code||':'||authorization_fact_type||':'||authorization_fact_id||':'||authorization_fact_revision||':'||octet_length(authorization_snapshot_digest) from audit.audit_entry_classified_v where tenant_id='"+h.seed.tenant()+"'"));return null;
        });}
        assertEquals(List.of(1L,1L,1L,1L,1L,1L),counts(h));assertEquals(first,run(runtime,e));assertEquals(List.of(1L,1L,1L,1L,1L,1L),counts(h));
        var conflict=assertInstanceOf(CommandResult.Conflict.class,runResult(runtime,h.envelope(e.commandId(),Map.of("phone","+124"))));assertEquals("COMMAND_PAYLOAD_CONFLICT",conflict.code());assertEquals(first.receiptId(),conflict.receiptId());assertEquals(List.of(1L,1L,1L,1L,1L,1L),counts(h));
    }
    @Test void no_change_discards_tentative_fact_writes_and_has_no_event_or_outbox()throws Exception {
        Handler h=new Handler();h.mode=Mode.NO_CHANGE;
        var result=run(runtime(h),h.envelope(UUID.randomUUID(),Map.of()));assertEquals(CommandOutcome.Status.NO_CHANGE,result.status());assertEquals(0L,result.resultFact().revision());assertEquals(List.of(1L,1L,1L,0L,0L,0L),counts(h));
    }
    @Test void post_write_business_rejection_commits_only_rejected_slot_receipt_audit()throws Exception {
        Handler h=new Handler();h.mode=Mode.REJECT_AFTER_WRITE;
        var e=h.envelope(UUID.randomUUID(),Map.of());var runtime=runtime(h);var result=run(runtime,e);
        assertEquals(CommandOutcome.Status.REJECTED,result.status());assertNull(result.resultFact());assertEquals("STALE_TASK",result.rejectionCode());assertEquals(List.of(1L,1L,1L,0L,0L,0L),counts(h));assertEquals(result,run(runtime,e));
    }
    @Test void technical_failure_rolls_back_without_orphan_slot_or_receipt()throws Exception {
        Handler h=new Handler();h.mode=Mode.TECHNICAL;
        assertThrows(SQLException.class,()->run(runtime(h),h.envelope(UUID.randomUUID(),Map.of())));assertEquals(List.of(0L,0L,0L,0L,0L,0L),counts(h));
    }
    @Test void incorrect_frozen_primary_authority_slot_fails_before_slot()throws Exception {
        Handler h=new Handler(){@Override public Context resolve(Connection c,CommandEnvelope e)throws SQLException {
            var context=super.resolve(c,e);var r=context.authorization();
            return new Context(context.scope(),new AuthorizationService.Request(r.actor(),r.subject(),r.scopeOrganizationId(),new AuthorizationService.Requirement("LEAD_INGRESS_COMPLETE","ASSIGNMENT_OWNER",AuthorizationService.Path.DIRECT,seed.grant())));
        }};
        assertThrows(CommandHandler.Rejected.class,()->run(runtime(h),h.envelope(UUID.randomUUID(),Map.of())));assertEquals(List.of(0L,0L,0L,0L,0L,0L),counts(h));
    }
    @Test void audit_failure_after_real_append_rolls_back_every_effect()throws Exception {
        Handler h=new Handler();
        var runtime=new CommandRuntime(List.of(h),AuthorizationService.databaseBacked(),(c,e)->{io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked("POSTGRES_IT").append(c,e);throw new SQLException("Injected audit failure","08006");});
        assertThrows(SQLException.class,()->run(runtime,h.envelope(UUID.randomUUID(),Map.of())));assertEquals(List.of(0L,0L,0L,0L,0L,0L),counts(h));
    }
    @Test void committed_revocation_after_fact_write_causes_only_terminal_rejection()throws Exception {
        Handler h=new Handler(){@Override public void validateBeforeCommit(Connection c,CommandEnvelope e,Context context,Result result)throws SQLException {
            super.validateBeforeCommit(c,e,context,result);
            try(var writer=database.apiConnection()){inTransaction(writer,Capability.COMMAND,w->{
                AuthorizationService.databaseBacked().lockForMutation(w,seed.tenant());
                sql(w,"update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='TEST',revision=revision+1 where tenant_id=? and authority_grant_id=?",seed.tenant(),seed.grant());return null;
            });}
        }};
        var result=run(runtime(h),h.envelope(UUID.randomUUID(),Map.of()));assertEquals(CommandOutcome.Status.REJECTED,result.status());assertEquals("NOT_AUTHORIZED",result.rejectionCode());assertEquals(List.of(1L,1L,1L,0L,0L,0L),counts(h));
    }
    @Test void lost_commit_acknowledgement_retries_original_receipt_with_zero_extra_deltas()throws Exception {
        Handler h=new Handler();var runtime=runtime(h);var envelope=h.envelope(UUID.randomUUID(),Map.of());
        try(var actual=database.apiConnection()) {
            Connection lost=(Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                try{Object result=method.invoke(actual,args);if(method.getName().equals("commit"))throw new SQLException("Injected acknowledgement loss","08006");return result;}
                catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
            });
            assertThrows(SQLException.class,()->runtime.execute(lost,envelope));
        }
        var result=run(runtime,envelope);assertEquals(CommandOutcome.Status.SUCCEEDED,result.status());assertEquals(result,run(runtime,envelope));assertEquals(List.of(1L,1L,1L,1L,1L,1L),counts(h));
    }
    @Test void concurrent_same_uuid_and_scope_produce_one_original_receipt()throws Exception {
        Handler h=new Handler();var runtime=runtime(h);var e=h.envelope(UUID.randomUUID(),Map.of());
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->run(runtime,e));var second=pool.submit(()->run(runtime,e));
            assertEquals(first.get(15,java.util.concurrent.TimeUnit.SECONDS),second.get(15,java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(List.of(1L,1L,1L,1L,1L,1L),counts(h));
    }
    @Test void concurrent_same_tenant_uuid_across_scopes_has_one_conflict_and_no_second_slot()throws Exception {
        Handler a=new Handler();var s=a.seed;Handler b=new Handler(new AuthorizationServiceIT.Seed(s.tenant(),s.principal(),s.appointment(),s.org(),s.grant(),UUID.randomUUID()));UUID command=UUID.randomUUID();
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->runResult(runtime(a),a.envelope(command,Map.of())));var second=pool.submit(()->runResult(runtime(b),b.envelope(command,Map.of())));
            var x=first.get(15,java.util.concurrent.TimeUnit.SECONDS);var y=second.get(15,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(x.receiptId(),y.receiptId());assertNotEquals(x instanceof CommandResult.Conflict,y instanceof CommandResult.Conflict);
        }
        assertEquals(List.of(1L,1L,1L,1L,1L),counts(a).subList(0,5));assertEquals(1L,counts(a).getLast()+counts(b).getLast());
    }
    @Test void multiple_notifications_keep_distinct_exact_sources_in_the_same_atomic_commit()throws Exception {
        Handler other=new Handler();var s=other.seed;
        Handler h=new Handler(new AuthorizationServiceIT.Seed(s.tenant(),s.principal(),s.appointment(),s.org(),s.grant(),UUID.randomUUID())) {
            @Override public Result execute(Connection c,CommandEnvelope e,Context context)throws SQLException {
                Result result=super.execute(c,e,context);
                return new Result(result.status(),result.fact(),List.of(new Notification(Event.LeadIngressCompletedV1,result.fact()),new Notification(Event.LeadIngressCompletedV1,other.seed.request().subject())));
            }
        };
        var result=run(runtime(h),h.envelope(UUID.randomUUID(),Map.of()));assertEquals(h.seed.subject(),result.resultFact().id());
        assertEquals(List.of(1L,1L,1L,2L,2L,1L),counts(h));
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
            assertEquals("2",scalar(x,"select count(distinct source_fact_id) from execution.domain_event where tenant_id='"+s.tenant()+"'"));return null;
        });}
    }
    @Test void incorrect_result_type_or_event_descriptor_rolls_back_the_handler_writes()throws Exception {
        for(boolean wrongResult:List.of(true,false)) {
            Handler h=new Handler(){@Override public Result execute(Connection c,CommandEnvelope e,Context context)throws SQLException {
                var original=super.execute(c,e,context);
                return wrongResult?Result.succeeded(new AuthorizationService.Subject("responsibility.task_occurrence",task,1L,null),Event.LeadIngressCompletedV1):Result.succeeded(original.fact(),Event.LeadAssignedV1);
            }};
            assertThrows(SQLException.class,()->run(runtime(h),h.envelope(UUID.randomUUID(),Map.of())));assertEquals(List.of(0L,0L,0L,0L,0L,0L),counts(h));
        }
    }
    @Test void revision_overflow_is_technical_and_never_leaves_slot_or_receipt()throws Exception {
        Handler h=new Handler(){@Override public void validateBeforeWork(Connection c,CommandEnvelope e,Context context)throws SQLException {CommandHandler.nextRevision(9007199254740991L);}};
        var error=assertThrows(SQLException.class,()->run(runtime(h),h.envelope(UUID.randomUUID(),Map.of())));assertEquals("22003",error.getSQLState());assertEquals(List.of(0L,0L,0L,0L,0L,0L),counts(h));
    }
    @Test void other_tenant_is_not_visible_and_has_no_command_or_fact_deltas()throws Exception {
        Handler h=new Handler(),sentinel=new Handler();
        var envelope=new CommandEnvelope(h.type(),UUID.randomUUID(),UUID.randomUUID(),sentinel.seed.request().actor(),Map.of());
        var rejected=assertThrows(CommandHandler.Rejected.class,()->run(runtime(h),envelope));assertEquals("NOT_FOUND",rejected.code());
        assertEquals(List.of(0L,0L,0L,0L,0L,0L),counts(h));assertEquals(List.of(0L,0L,0L,0L,0L,0L),counts(sentinel));
    }
    @Test void lock_timeout_is_technical_and_rolls_back_every_command_write()throws Exception {
        Handler h=new Handler();var e=h.envelope(UUID.randomUUID(),Map.of());
        try(var blocker=database.apiConnection();var command=database.apiConnection()) {
            inTransaction(blocker,Capability.COMMAND,b->{
                try(var p=b.prepareStatement("select lead_id from lead.lead where tenant_id=? and lead_id=? for update")){p.setObject(1,h.seed.tenant());p.setObject(2,h.seed.subject());p.executeQuery().close();}
                sql(command,"set lock_timeout='100ms'");
                SQLException error=assertThrows(SQLException.class,()->runtime(h).execute(command,e));assertEquals("55P03",error.getSQLState());return null;
            });
        }
        assertEquals(List.of(0L,0L,0L,0L,0L,0L),counts(h));
    }
    final class RecoveryHandler extends Handler {
        final UUID wait=UUID.randomUUID();String waitHash;long expected=1;
        RecoveryHandler(boolean early,boolean wrongType,boolean alreadyOpen)throws Exception {
            super(AuthorizationServiceIT.seed(database,"SERVICE"),wrongType?"COMPLETE_LEAD_INGRESS":"CONTACT_LEAD",wrongType?"COMPLETE_LEAD_INGRESS":"RECORD_CONTACT_RESULT",wrongType?"lead.lead":"lead.lead_contact_result");
            try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
                sql(x,"update responsibility.task_occurrence set state='WAITING',revision=revision+1 where tenant_id=? and task_occurrence_id=?",seed.tenant(),task);
                sql(x,"insert into responsibility.wait_receipt (tenant_id,wait_receipt_id,task_occurrence_id,task_revision,wait_sequence,wait_reason_code,wait_contract_code,wait_contract_version,entered_waiting_at,resume_due_at,recorded_by_appointment_id) values (?,?,?,1,1,'CONTACT_RETRY','CONTACT_RETRY_V1',1,clock_timestamp()-interval '2 hours',clock_timestamp()+ (? * interval '1 hour'),?)",seed.tenant(),wait,task,early?1:-1,seed.appointment());
                if(alreadyOpen)sql(x,"update responsibility.task_occurrence set state='OPEN',revision=revision+1 where tenant_id=? and task_occurrence_id=?",seed.tenant(),task);
                waitHash=waitHash(x);return null;
            });}
        }
        @Override public CommandEnvelope.Type type(){return CommandEnvelope.Type.REOPEN_DUE_CONTACT_TASKS;}
        String waitHash(Connection c)throws SQLException {
            try(var p=c.prepareStatement("select * from responsibility.wait_receipt where tenant_id=? and wait_receipt_id=?")) {
                p.setObject(1,seed.tenant());p.setObject(2,wait);
                try(var rows=p.executeQuery()){if(!rows.next())throw new Rejected("VALIDATION_FAILED");var fields=new TreeMap<String,Object>();
                    for(int i=1;i<=rows.getMetaData().getColumnCount();i++) {
                        String key=rows.getMetaData().getColumnName(i);Object value=rows.getObject(i);
                        if(value instanceof UUID)value=value.toString();
                        if(value instanceof java.sql.Timestamp ts)value=new java.time.format.DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(ts.toInstant());
                        fields.put(key.equals("tenant_id")?"tenantId":key,value);
                    }
                    return Base64.getUrlEncoder().withoutPadding().encodeToString(CanonicalJson.digest(CanonicalJson.encode(fields)));
                }
            }
        }
        @Override public Context resolve(Connection c,CommandEnvelope e)throws SQLException {
            var r=seed.request();
            return new Context(CommandScope.reopen(e.actor().tenantId(),type(),task,wait,waitHash),new AuthorizationService.Request(e.actor(),r.subject(),r.scopeOrganizationId(),new AuthorizationService.Requirement(r.requirement().authorityCode(),r.requirement().slot(),AuthorizationService.Path.SYSTEM,seed.grant())));
        }
        @Override public void recoveryEligibility(Connection c,CommandEnvelope e,Context context)throws SQLException {
            if(!waitHash.equals(waitHash(c)))throw new Rejected("VALIDATION_FAILED");
            try(var p=c.prepareStatement("select t.business_purpose_code,t.state,t.revision,w.task_revision,w.wait_contract_code,w.resume_due_at<=clock_timestamp() as due,w.wait_receipt_id=(select w2.wait_receipt_id from responsibility.wait_receipt w2 where w2.tenant_id=t.tenant_id and w2.task_occurrence_id=t.task_occurrence_id order by w2.wait_sequence desc limit 1) as latest from responsibility.task_occurrence t join responsibility.wait_receipt w on w.tenant_id=t.tenant_id and w.task_occurrence_id=t.task_occurrence_id where t.tenant_id=? and t.task_occurrence_id=? and w.wait_receipt_id=?")) {
                p.setObject(1,seed.tenant());p.setObject(2,task);p.setObject(3,wait);
                try(var r=p.executeQuery()) {if(!r.next() || !r.getString(1).equals("CONTACT_LEAD") || !(r.getString(2).equals("WAITING") && r.getLong(3)==expected || r.getString(2).equals("OPEN") && r.getLong(3)==expected+1) || r.getLong(4)!=expected || !r.getString(5).equals("CONTACT_RETRY_V1") || !r.getBoolean(6) || !r.getBoolean(7))throw new Rejected("VALIDATION_FAILED");}
            }
        }
        @Override public Result execute(Connection c,CommandEnvelope e,Context context)throws SQLException {
            long revision=Long.parseLong(scalar(c,"select revision from responsibility.task_occurrence where tenant_id='"+seed.tenant()+"' and task_occurrence_id='"+task+"'"));
            return Result.noChange(new AuthorizationService.Subject("responsibility.task_occurrence",task,revision,null));
        }
        @Override public void validateBeforeCommit(Connection c,CommandEnvelope e,Context context,Result result)throws SQLException {
            assertEquals("2",scalar(c,"select revision from responsibility.task_occurrence where tenant_id='"+seed.tenant()+"' and task_occurrence_id='"+task+"'"));
        }
    }
    /** Test-only low-level phase harness: NOT CommandRuntime or a production policy override. */
    CommandResult recoveryPhase(RecoveryHandler h,CommandEnvelope e)throws Exception {
        try(var c=database.apiConnection()){return inTransaction(c,Capability.QUERY,x->{
            var context=h.resolve(x,e);var auth=AuthorizationService.databaseBacked();
            assertTrue(auth.evaluate(x,context.authorization(),false).allowed());
            setLocalRole(x,Capability.COMMAND);h.lockRoots(x,e,context);
            var store=new io.github.windyzhu3.ontologylaw.execution.internal.persistence.JooqCommandStore(x);store.lockCommand(e);
            var existing=store.existingOrValidateNew(e,context.scope(),CanonicalJson.digest(CanonicalJson.encode(e.payload())),q->{h.recoveryEligibility(q,e,context);return null;});
            if(existing!=null)return existing;
            var slot=store.occupy(e,context.scope(),CanonicalJson.digest(CanonicalJson.encode(e.payload())));
            var result=h.execute(x,e,context);
            return store.receipt(e,slot,result.status(),result.fact(),null);
        });}
    }
    @Test void recovery_new_key_early_wrong_type_and_stale_selectors_are_all_zero()throws Exception {
        for(int scenario=0;scenario<3;scenario++) {
            var h=new RecoveryHandler(scenario==0,scenario==1,false);if(scenario==2)h.expected=0;
            var error=assertThrows(CommandHandler.Rejected.class,()->recoveryPhase(h,h.envelope(UUID.randomUUID(),Map.of())));assertEquals("VALIDATION_FAILED",error.code());assertEquals(List.of(0L,0L,0L,0L,0L,0L),counts(h));
        }
    }
    @Test void recovery_replay_precedes_new_key_eligibility_after_task_advances()throws Exception {
        var h=new RecoveryHandler(false,false,true);var e=h.envelope(UUID.randomUUID(),Map.of("expectedTaskRevision",1L));
        var first=assertInstanceOf(CommandOutcome.class,recoveryPhase(h,e));assertEquals(CommandOutcome.Status.NO_CHANGE,first.status());assertEquals(h.task,first.resultFact().id());assertEquals(2L,first.resultFact().revision());
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            sql(x,"update responsibility.task_occurrence set state='WAITING',revision=revision+1 where tenant_id=? and task_occurrence_id=?",h.seed.tenant(),h.task);
            sql(x,"insert into responsibility.wait_receipt (tenant_id,wait_receipt_id,task_occurrence_id,task_revision,wait_sequence,wait_reason_code,wait_contract_code,wait_contract_version,entered_waiting_at,resume_due_at,recorded_by_appointment_id) values (?,?,?,3,2,'CONTACT_RETRY','CONTACT_RETRY_V1',1,clock_timestamp(),clock_timestamp()+interval '1 hour',?)",h.seed.tenant(),UUID.randomUUID(),h.task,h.seed.appointment());return null;
        });}
        assertEquals(first,recoveryPhase(h,e));assertInstanceOf(CommandResult.Conflict.class,recoveryPhase(h,h.envelope(e.commandId(),Map.of("expectedTaskRevision",0L))));assertEquals(List.of(1L,1L,0L,0L,0L,0L),counts(h));
    }
    @Test void failure_on_later_notification_rolls_back_earlier_event_and_all_facts()throws Exception {
        Handler other=new Handler();var s=other.seed;
        Handler h=new Handler(new AuthorizationServiceIT.Seed(s.tenant(),s.principal(),s.appointment(),s.org(),s.grant(),UUID.randomUUID())) {
            @Override public Result execute(Connection c,CommandEnvelope e,Context context)throws SQLException {
                Result result=super.execute(c,e,context);
                return new Result(result.status(),result.fact(),List.of(new Notification(Event.LeadIngressCompletedV1,result.fact()),new Notification(Event.LeadIngressCompletedV1,other.seed.request().subject())));
            }
        };
        var inserts=new java.util.concurrent.atomic.AtomicInteger();
        try(var actual=database.apiConnection()) {
            Connection faulty=(Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                try {
                    Object value=method.invoke(actual,args);
                    if(method.getName().equals("prepareStatement") && ((String)args[0]).startsWith("insert into \"execution\".\"domain_event\" ")) {
                        var real=(PreparedStatement)value;
                        return java.lang.reflect.Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{
                            if(m.getName().equals("execute") && inserts.incrementAndGet()==2)throw new SQLException("Injected second notification failure","08006");
                            try{return m.invoke(real,a);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                        });
                    }
                    return value;
                }catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
            });
            assertThrows(RuntimeException.class,()->runtime(h).execute(faulty,h.envelope(UUID.randomUUID(),Map.of())));
        }
        assertEquals(2,inserts.get());assertEquals(List.of(0L,0L,0L,0L,0L,0L),counts(h));assertEquals(List.of(0L,0L,0L,0L,0L,0L),counts(other));
    }
    @Test void audit_uses_the_explicit_trusted_deployment_node()throws Exception {
        Handler h=new Handler();var runtime=new CommandRuntime(List.of(h),AuthorizationService.databaseBacked(),io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked("NODE_A"));
        run(runtime,h.envelope(UUID.randomUUID(),Map.of()));
        try(var observer=database.migratorConnection()){assertEquals("NODE_A",scalar(observer,"select execution_node_code from audit.audit_entry where tenant_id='"+h.seed.tenant()+"'"));}
    }
    @Test void cross_scope_conflict_never_discloses_original_terminal_fields()throws Exception {
        Handler original=new Handler();var s=original.seed;
        Handler other=new Handler(new AuthorizationServiceIT.Seed(s.tenant(),s.principal(),s.appointment(),s.org(),s.grant(),UUID.randomUUID()));
        UUID command=UUID.randomUUID();var receipt=run(runtime(original),original.envelope(command,Map.of()));
        var conflict=assertInstanceOf(CommandResult.Conflict.class,runResult(runtime(other),other.envelope(command,Map.of())));
        assertEquals(receipt.receiptId(),conflict.receiptId());assertEquals("COMMAND_PAYLOAD_CONFLICT",conflict.code());
        assertFalse(conflict.toString().contains(original.seed.subject().toString()));
        assertEquals(List.of(1L,1L,1L,1L,1L),counts(other).subList(0,5));assertEquals(0L,counts(other).getLast());
    }
    @Test void replay_and_conflict_reauthorize_after_waiting_for_root_lock()throws Exception {
        for(boolean conflict:List.of(false,true)) {
            var waiting=new java.util.concurrent.CountDownLatch(1);var armed=new java.util.concurrent.atomic.AtomicBoolean();
            Handler h=new Handler(){@Override public void lockRoots(Connection c,CommandEnvelope e,Context context)throws SQLException {
                if(armed.get())waiting.countDown();super.lockRoots(c,e,context);
            }};
            var runtime=runtime(h);var e=h.envelope(UUID.randomUUID(),Map.of());run(runtime,e);armed.set(true);
            try(var pool=java.util.concurrent.Executors.newSingleThreadExecutor();var blocker=database.apiConnection()) {
                var pending=inTransaction(blocker,Capability.COMMAND,b->{
                    try(var p=b.prepareStatement("select lead_id from lead.lead where tenant_id=? and lead_id=? for update")){p.setObject(1,h.seed.tenant());p.setObject(2,h.seed.subject());p.executeQuery().close();}
                    var future=pool.submit(()->runResult(runtime,conflict?h.envelope(e.commandId(),Map.of("changed",true)):e));
                    try{assertTrue(waiting.await(10,java.util.concurrent.TimeUnit.SECONDS));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new SQLException(interrupted);}
                    try(var writer=database.apiConnection()){inTransaction(writer,Capability.COMMAND,w->{
                        AuthorizationService.databaseBacked().lockForMutation(w,h.seed.tenant());
                        sql(w,"update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='TEST',revision=revision+1 where tenant_id=? and authority_grant_id=?",h.seed.tenant(),h.seed.grant());return null;
                    });}
                    return future;
                });
                var failure=assertThrows(java.util.concurrent.ExecutionException.class,()->pending.get(10,java.util.concurrent.TimeUnit.SECONDS));
                assertInstanceOf(CommandHandler.Rejected.class,failure.getCause());
            }
            assertEquals(List.of(1L,1L,1L,1L,1L,1L),counts(h));
        }
    }
    @Test void unresolved_command_policy_cannot_register_even_a_no_change_handler()throws Exception {
        for(var type:List.of(CommandEnvelope.Type.CAPTURE_LEAD,CommandEnvelope.Type.SAVE_ACTION_DRAFT,CommandEnvelope.Type.REOPEN_DUE_CONTACT_TASKS,CommandEnvelope.Type.REOPEN_DUE_ROUTING_REVIEW_TASKS)) {
            Handler h=new Handler(){@Override public CommandEnvelope.Type type(){return type;}};
            h.mode=Mode.NO_CHANGE;
            assertThrows(IllegalArgumentException.class,()->runtime(h));assertEquals(List.of(0L,0L,0L,0L,0L,0L),counts(h));
        }
    }
}
