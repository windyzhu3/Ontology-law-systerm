package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.lead.R1EventReaders;
import io.github.windyzhu3.ontologylaw.testing.PostgresIntegrationTest;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;

/** Real Owner facts and CommandRuntime, with business fixture Handlers only in test sources. */
class R1ContractClosureIT extends PostgresIntegrationTest {
    static UUID id(int n){return UUID.fromString("01900000-0000-7000-8000-"+String.format(Locale.ROOT,"%012d",n));}
    static String scalar(Connection c,String query)throws SQLException{try(var s=c.createStatement();var r=s.executeQuery(query)){assertTrue(r.next());return r.getString(1);}}
    enum Fault { NONE, DROP_OPPORTUNITY, ADD_OPPORTUNITY, WRONG_OPPORTUNITY, WRONG_CONTACT, WRONG_HASH, DUPLICATE, WRONG_OWNER, MISSING_OPPORTUNITY, OPPORTUNITY_REVISION, MISSING_DONE, MISSING_CONFIRM, ALREADY_CONFIRMED_NO_CONFIRM_WRITE, PREEXISTING_CONTACT_OPPORTUNITY_NO_INSERTS }
    class ContactHandler implements CommandHandler {
        final UUID tenant,principal,owner,org,grant,lead,task,assignment,contact,opportunity,draft;
        final String code;final int number;final Fault fault;final String contactHash;
        ContactHandler(int fixture,String code,int number,Fault fault)throws Exception {
            int base=fixture*100;tenant=id(base+1);principal=id(base+2);owner=id(base+3);org=id(base+4);grant=id(base+5);lead=id(base+6);task=id(base+7);assignment=id(base+8);contact=id(base+9);opportunity=id(base+10);draft=id(base+11);
            this.code=code;this.number=number;this.fault=fault;
            // Independent literal canonical fixture, including both explicit null fields and fixed microseconds.
            String json="{\"contact_channel_code\":\"PHONE\",\"contact_no\":"+number+",\"contact_task_id\":\""+task+"\",\"created_at\":\"2026-09-05T00:00:00.123456Z\",\"evidence_submission_id\":null,\"lead_assignment_id\":\""+assignment+"\",\"lead_contact_result_id\":\""+contact+"\",\"lead_id\":\""+lead+"\",\"result_code\":\""+code+"\",\"result_summary\":null,\"resulted_at\":\"2026-09-05T00:00:00.123456Z\",\"tenantId\":\""+tenant+"\"}";
            contactHash=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
            try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
                sql(x,"insert into identity.tenant (tenant_id,tenant_code,display_name,state,created_at) values (?,?,'closure','ACTIVE',clock_timestamp())",tenant,tenant.toString());
                sql(x,"insert into identity.principal (tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) values (?,?,'HUMAN','FIXTURE',decode(repeat('00',32),'hex'),'fixture','ACTIVE',clock_timestamp())",tenant,principal);
                sql(x,"insert into identity.organization_unit (tenant_id,organization_unit_id,unit_code,display_name,state,created_at) values (?,?,'ROOT','fixture','ACTIVE',clock_timestamp())",tenant,org);
                sql(x,"insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'OWNER',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",tenant,owner,principal,org);
                sql(x,"insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'OTHER',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",tenant,id(base+12),principal,org);
                sql(x,"insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,'SALES_CONTACT_OWNER',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",tenant,grant,owner,owner,org);
                sql(x,"insert into lead.lead (tenant_id,lead_id,source_channel_code,source_account_code,source_record_key_digest,captured_at,service_category_code,jurisdiction_code,urgency_code,legal_need_summary_ciphertext,captured_content_digest,party_resolution_code,disposition_code,created_at) values (?,?,'FIXTURE','FIXTURE',decode(repeat('00',32),'hex'),clock_timestamp(),'FIXTURE','FIXTURE','FIXTURE',decode('01','hex'),decode(repeat('00',32),'hex'),'UNRESOLVED','CAPTURED',clock_timestamp())",tenant,lead);
                sql(x,"insert into lead.lead_assignment (tenant_id,lead_assignment_id,lead_id,assignment_no,owner_appointment_id,assignment_reason_code,assigned_at,assignment_status_code,created_at) values (?,?,?,1,?,'MANUAL',clock_timestamp(),'OPEN',clock_timestamp())",tenant,assignment,lead,owner);
                sql(x,"update lead.lead set current_assignment_id=?,revision=revision+1 where tenant_id=? and lead_id=?",assignment,tenant,lead);
                sql(x,"insert into responsibility.task_occurrence (tenant_id,task_occurrence_id,owner_appointment_id,business_purpose_code,primary_command_code,expected_completion_fact_type,original_sla_code,original_sla_seconds,original_sla_due_at,state,created_at,subject_type,subject_id,subject_revision) values (?,?,?,'CONTACT_LEAD','RECORD_CONTACT_RESULT','lead.lead_contact_result','R1_CONTACT_30M_V1',1800,clock_timestamp()+interval '30 minutes','OPEN',clock_timestamp(),'lead.lead',?,1)",tenant,task,fault==Fault.WRONG_OWNER?id(base+12):owner,lead);
                sql(x,"insert into responsibility.action_draft (tenant_id,action_draft_id,task_occurrence_id,action_code,payload_schema_code,payload_schema_version,candidate_payload,candidate_payload_digest,state,created_by_appointment_id,created_at,last_edited_at) values (?,?,?,'RECORD_CONTACT_RESULT','RecordContactResultV1',1,'{}',decode(repeat('00',32),'hex'),'DRAFT',?,clock_timestamp(),clock_timestamp())",tenant,draft,task,owner);return null;
            });}
            if(fault==Fault.ALREADY_CONFIRMED_NO_CONFIRM_WRITE)try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
                sql(x,"update responsibility.action_draft set state='CONFIRMED',revision=revision+1,confirmed_by_appointment_id=?,confirmed_at=clock_timestamp(),confirmed_payload_digest=candidate_payload_digest where tenant_id=? and action_draft_id=?",owner,tenant,draft);return null;
            });}
        }
        public CommandEnvelope.Type type(){return CommandEnvelope.Type.RECORD_CONTACT_RESULT;}
        CommandEnvelope envelope(){return new CommandEnvelope(type(),UUID.randomUUID(),UUID.randomUUID(),new Actor(tenant,principal,owner,null,null),Map.of());}
        Subject receipt(){return new Subject("lead.lead_contact_result",contact,null,contactHash);}
        public Context resolve(Connection c,CommandEnvelope e) {
            var subject=new Subject("lead.lead",lead,1L,null);
            return new Context(CommandScope.task(tenant,type(),task,subject,Map.of("leadAssignmentId",assignment,"leadAssignmentRevision",0L)),
                    new Request(e.actor(),subject,org,new Requirement("SALES_CONTACT_OWNER","ASSIGNMENT_OWNER",Path.DIRECT,grant)));
        }
        public void lockRoots(Connection c,CommandEnvelope e,Context context)throws SQLException {
            try(var p=c.prepareStatement("select lead_id from lead.lead where tenant_id=? and lead_id=? for update")){p.setObject(1,tenant);p.setObject(2,lead);p.executeQuery().close();}
            try(var p=c.prepareStatement("select task_occurrence_id from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=? for update")){p.setObject(1,tenant);p.setObject(2,context.scope().taskId());p.executeQuery().close();}
        }
        public void recoveryEligibility(Connection c,CommandEnvelope e,Context context){throw new AssertionError();}
        public void validateBeforeWork(Connection c,CommandEnvelope e,Context context)throws SQLException {
            if(!"OPEN".equals(scalar(c,"select state from responsibility.task_occurrence where tenant_id='"+tenant+"'")))throw new Rejected("STALE_TASK");
        }
        public Result execute(Connection c,CommandEnvelope e,Context context)throws SQLException {
            if(fault!=Fault.PREEXISTING_CONTACT_OPPORTUNITY_NO_INSERTS)sql(c,"insert into lead.lead_contact_result (tenant_id,lead_contact_result_id,lead_id,lead_assignment_id,contact_no,contact_task_id,contact_channel_code,result_code,resulted_at,created_at) values (?,?,?,?,?,?,'PHONE',?,'2026-09-05T00:00:00.123456Z','2026-09-05T00:00:00.123456Z')",tenant,contact,lead,assignment,number,task,code);
            if(fault!=Fault.PREEXISTING_CONTACT_OPPORTUNITY_NO_INSERTS && ((code.equals("CONNECTED_VALID") && fault!=Fault.MISSING_OPPORTUNITY) || fault==Fault.ADD_OPPORTUNITY)) {
                sql(c,"insert into opportunity.opportunity (tenant_id,opportunity_id,source_lead_id,source_assignment_id,source_contact_result_id,owner_appointment_id,legal_need_ciphertext,legal_need_digest,created_at) values (?,?,?,?,?,?,decode('01','hex'),decode(repeat('00',32),'hex'),clock_timestamp())",tenant,opportunity,lead,assignment,contact,owner);
                if(fault==Fault.OPPORTUNITY_REVISION)sql(c,"update opportunity.opportunity set revision=revision+1,close_outcome_code='FIXTURE',closed_at=clock_timestamp() where tenant_id=? and opportunity_id=?",tenant,opportunity);
            }
            if(fault!=Fault.MISSING_DONE)sql(c,"update responsibility.task_occurrence set state='DONE',revision=revision+1,completed_at=clock_timestamp(),completion_fact_type='lead.lead_contact_result',completion_fact_id=?,completion_fact_hash=? where tenant_id=? and task_occurrence_id=?",contact,Base64.getUrlDecoder().decode(contactHash),tenant,task);
            if(fault!=Fault.MISSING_CONFIRM && fault!=Fault.ALREADY_CONFIRMED_NO_CONFIRM_WRITE)sql(c,"update responsibility.action_draft set state='CONFIRMED',revision=revision+1,confirmed_by_appointment_id=?,confirmed_at=clock_timestamp(),confirmed_payload_digest=candidate_payload_digest where tenant_id=? and action_draft_id=?",owner,tenant,draft);
            var first=new Notification(number==3 && code.equals("NOT_CONNECTED")?Event.LeadContactRetryExhaustedV1:Event.LeadContactResultRecordedV1,receipt());
            var notifications=new ArrayList<Notification>();notifications.add(first);
            if(code.equals("CONNECTED_VALID") && fault!=Fault.DROP_OPPORTUNITY || fault==Fault.ADD_OPPORTUNITY)notifications.add(new Notification(Event.OpportunityOpened,new Subject("opportunity.opportunity",fault==Fault.WRONG_OPPORTUNITY?id(99998):opportunity,0L,null)));
            if(fault==Fault.DUPLICATE)notifications.add(first);
            if(fault==Fault.WRONG_CONTACT)notifications.set(0,new Notification(first.event(),new Subject("lead.lead_contact_result",id(99997),null,contactHash)));
            if(fault==Fault.WRONG_HASH)notifications.set(0,new Notification(first.event(),new Subject("lead.lead_contact_result",contact,null,Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]))));
            return new Result(CommandOutcome.Status.SUCCEEDED,receipt(),notifications);
        }
        // The real production policy must detect wrong sources even when a test Handler fails to validate them.
        public void validateBeforeCommit(Connection c,CommandEnvelope e,Context context,Result result) {}
    }
    CommandRuntime runtime(ContactHandler h){return runtime(h,AuditAppender.databaseBacked("CLOSURE_IT"));}
    CommandRuntime runtime(ContactHandler h,AuditAppender audit){return new CommandRuntime(List.of(h),AuthorizationService.databaseBacked(),audit,null,R1EventReaders.databaseBacked());}
    CommandOutcome run(CommandRuntime runtime,CommandEnvelope e)throws Exception{try(var c=database.apiConnection()){return assertInstanceOf(CommandOutcome.class,runtime.execute(c,e));}}
    List<Long> counts(UUID tenant)throws Exception {
        try(var c=database.apiConnection()){return inTransaction(c,Capability.QUERY,x->{var result=new ArrayList<Long>();
            for(String table:List.of("execution.command_execution_slot","execution.command_receipt","audit.audit_entry_classified_v","execution.domain_event","execution.domain_event_outbox"))result.add(Long.parseLong(scalar(x,"select count(*) from "+table+" where tenant_id='"+tenant+"'")));return result;
        });}
    }
    void assertRolledBack(ContactHandler h)throws Exception {
        assertEquals(List.of(0L,0L,0L,0L,0L),counts(h.tenant));
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
            assertEquals("0",scalar(x,"select count(*) from lead.lead_contact_result where tenant_id='"+h.tenant+"'"));
            assertEquals("0",scalar(x,"select count(*) from opportunity.opportunity where tenant_id='"+h.tenant+"'"));
            assertEquals("OPEN:0",scalar(x,"select state||':'||revision from responsibility.task_occurrence where tenant_id='"+h.tenant+"'"));
            assertEquals("DRAFT:0",scalar(x,"select state||':'||revision from responsibility.action_draft where tenant_id='"+h.tenant+"'"));return null;
        });}
    }
    @Test void connected_valid_commits_two_exact_sources_one_receipt_audit_and_no_r2_then_replays()throws Exception {
        var h=new ContactHandler(1,"CONNECTED_VALID",1,Fault.NONE);var runtime=runtime(h);var e=h.envelope();var outcome=run(runtime,e);
        assertEquals(CommandOutcome.Status.SUCCEEDED,outcome.status());assertEquals("lead.lead_contact_result",outcome.resultFact().type());
        assertEquals(id(109),outcome.resultFact().id());assertEquals(h.contactHash,outcome.resultFact().hash());
        assertEquals("bDHXwGMMysZpts2GoiO2JcmY_ocM3LMmMdr-lFfPHDk",outcome.resultFact().hash());
        assertEquals(List.of(1L,1L,1L,2L,2L),counts(h.tenant));
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
            assertEquals("LeadContactResultRecordedV1:lead.lead_contact_result:01900000-0000-7000-8000-000000000109:"+h.contactHash+":1:{}:R1_PROJECTION",scalar(x,"select e.event_type||':'||e.source_fact_type||':'||e.source_fact_id||':'||rtrim(translate(encode(e.source_fact_hash,'base64'),'+/','-_'),'=')||':'||e.event_schema_version||':'||e.event_payload||':'||o.queue_owner from execution.domain_event e join execution.domain_event_outbox o using (tenant_id,domain_event_id) where e.tenant_id='"+h.tenant+"' and e.event_type='LeadContactResultRecordedV1'"));
            assertEquals("OpportunityOpened:opportunity.opportunity:01900000-0000-7000-8000-000000000110:0:1:{}:R1_PROJECTION",scalar(x,"select e.event_type||':'||e.source_fact_type||':'||e.source_fact_id||':'||e.source_fact_revision||':'||e.event_schema_version||':'||e.event_payload||':'||o.queue_owner from execution.domain_event e join execution.domain_event_outbox o using (tenant_id,domain_event_id) where e.tenant_id='"+h.tenant+"' and e.event_type='OpportunityOpened'"));
            assertEquals("DONE:1",scalar(x,"select state||':'||revision from responsibility.task_occurrence where tenant_id='"+h.tenant+"'"));
            assertEquals("1",scalar(x,"select count(*) from responsibility.task_occurrence where tenant_id='"+h.tenant+"'"));
            assertEquals("0",scalar(x,"select count(*) from responsibility.task_occurrence where tenant_id='"+h.tenant+"' and business_purpose_code<>'CONTACT_LEAD'"));return null;
        });}
        assertEquals(outcome,run(runtime,e));assertEquals(List.of(1L,1L,1L,2L,2L),counts(h.tenant));
    }
    @Test void missing_extra_duplicate_and_wrong_sources_roll_back_all_effects()throws Exception {
        for(var fault:List.of(Fault.DROP_OPPORTUNITY,Fault.ADD_OPPORTUNITY,Fault.WRONG_OPPORTUNITY,Fault.WRONG_CONTACT,Fault.WRONG_HASH,Fault.DUPLICATE,Fault.WRONG_OWNER,Fault.MISSING_OPPORTUNITY,Fault.OPPORTUNITY_REVISION,Fault.MISSING_DONE,Fault.MISSING_CONFIRM)) {
            var h=new ContactHandler(10+fault.ordinal(),fault==Fault.ADD_OPPORTUNITY?"NOT_CONNECTED":"CONNECTED_VALID",1,fault);
            assertEquals("22000",assertThrows(SQLException.class,()->run(runtime(h),h.envelope()),fault.name()).getSQLState(),fault.name());assertRolledBack(h);
        }
    }
    @Test void previously_confirmed_draft_without_current_confirm_write_rolls_back_command()throws Exception {
        var h=new ContactHandler(22,"CONNECTED_VALID",1,Fault.ALREADY_CONFIRMED_NO_CONFIRM_WRITE);
        String before;
        try(var c=database.apiConnection()){before=inTransaction(c,Capability.QUERY,x->scalar(x,"select row_to_json(d)::text from responsibility.action_draft d where tenant_id='"+h.tenant+"'"));}
        var failure=assertThrows(SQLException.class,()->run(runtime(h),h.envelope()));
        assertEquals("22000",failure.getSQLState());
        assertEquals(List.of(0L,0L,0L,0L,0L),counts(h.tenant));
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
            assertEquals("0",scalar(x,"select count(*) from lead.lead_contact_result where tenant_id='"+h.tenant+"'"));
            assertEquals("0",scalar(x,"select count(*) from opportunity.opportunity where tenant_id='"+h.tenant+"'"));
            assertEquals("OPEN:0",scalar(x,"select state||':'||revision from responsibility.task_occurrence where tenant_id='"+h.tenant+"'"));
            assertEquals(before,scalar(x,"select row_to_json(d)::text from responsibility.action_draft d where tenant_id='"+h.tenant+"'"));return null;
        });}
    }
    @Test void previously_committed_contact_and_opportunity_without_current_inserts_roll_back_command()throws Exception {
        var h=new ContactHandler(23,"CONNECTED_VALID",1,Fault.PREEXISTING_CONTACT_OPPORTUNITY_NO_INSERTS);
        // Commit both facts before the command; its only business writes will complete Task and confirm Draft.
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            sql(x,"insert into lead.lead_contact_result (tenant_id,lead_contact_result_id,lead_id,lead_assignment_id,contact_no,contact_task_id,contact_channel_code,result_code,resulted_at,created_at) values (?,?,?,?,1,?,'PHONE','CONNECTED_VALID','2026-09-05T00:00:00.123456Z','2026-09-05T00:00:00.123456Z')",h.tenant,h.contact,h.lead,h.assignment,h.task);
            sql(x,"insert into opportunity.opportunity (tenant_id,opportunity_id,source_lead_id,source_assignment_id,source_contact_result_id,owner_appointment_id,legal_need_ciphertext,legal_need_digest,created_at) values (?,?,?,?,?,?,decode('01','hex'),decode(repeat('00',32),'hex'),clock_timestamp())",h.tenant,h.opportunity,h.lead,h.assignment,h.contact,h.owner);return null;
        });}
        var before=contactBusinessRows(h);
        assertEquals(List.of(0L,0L,0L,0L,0L),counts(h.tenant));
        var failure=assertThrows(SQLException.class,()->run(runtime(h),h.envelope()));
        assertEquals("22000",failure.getSQLState());
        assertEquals(List.of(0L,0L,0L,0L,0L),counts(h.tenant));
        assertEquals(before,contactBusinessRows(h),"Prior ContactResult/Opportunity and every Task/Draft column must remain unchanged");
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
            assertEquals("1",scalar(x,"select count(*) from lead.lead_contact_result where tenant_id='"+h.tenant+"'"));
            assertEquals("1",scalar(x,"select count(*) from opportunity.opportunity where tenant_id='"+h.tenant+"'"));
            assertEquals("OPEN:0",scalar(x,"select state||':'||revision from responsibility.task_occurrence where tenant_id='"+h.tenant+"'"));
            assertEquals("DRAFT:0",scalar(x,"select state||':'||revision from responsibility.action_draft where tenant_id='"+h.tenant+"'"));return null;
        });}
    }
    List<String> contactBusinessRows(ContactHandler h)throws Exception {
        try(var c=database.apiConnection()){return inTransaction(c,Capability.QUERY,x->{
            var rows=new ArrayList<String>();
            for(String table:List.of("lead.lead_contact_result","opportunity.opportunity","responsibility.task_occurrence","responsibility.action_draft"))
                rows.add(scalar(x,"select row_to_json(f)::text from "+table+" f where tenant_id='"+h.tenant+"'"));
            return rows;
        });}
    }
    @Test void contact_no_selects_retry_or_exhausted_and_suspect_has_one_event()throws Exception {
        String[][] rows={{"NOT_CONNECTED","1","LeadContactResultRecordedV1"},{"NOT_CONNECTED","2","LeadContactResultRecordedV1"},{"NOT_CONNECTED","3","LeadContactRetryExhaustedV1"},{"SUSPECT_INVALID","1","LeadContactResultRecordedV1"}};
        for(int i=0;i<rows.length;i++) {
            var row=rows[i];var h=new ContactHandler(30+i,row[0],Integer.parseInt(row[1]),Fault.NONE);run(runtime(h),h.envelope());
            assertEquals(List.of(1L,1L,1L,1L,1L),counts(h.tenant));
            try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{assertEquals(row[2],scalar(x,"select event_type from execution.domain_event where tenant_id='"+h.tenant+"'"));return null;});}
        }
    }
    @Test void connected_audit_failure_and_lost_commit_ack_have_atomic_outcomes()throws Exception {
        var failed=new ContactHandler(40,"CONNECTED_VALID",1,Fault.NONE);
        var audit=AuditAppender.databaseBacked("CLOSURE_IT");
        assertThrows(SQLException.class,()->run(runtime(failed,(c,e)->{audit.append(c,e);throw new SQLException("audit failure","08006");}),failed.envelope()));assertRolledBack(failed);
        var h=new ContactHandler(41,"CONNECTED_VALID",1,Fault.NONE);var runtime=runtime(h);var e=h.envelope();
        try(var c=database.apiConnection()) {
            var lost=(Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                try{var value=m.invoke(c,a);if(m.getName().equals("commit"))throw new SQLException("ack lost","08006");return value;}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
            });assertThrows(SQLException.class,()->runtime.execute(lost,e));
        }
        assertEquals(List.of(1L,1L,1L,2L,2L),counts(h.tenant));var receipt=run(runtime,e);assertEquals(receipt,run(runtime,e));assertEquals(List.of(1L,1L,1L,2L,2L),counts(h.tenant));
    }
    @Test void failure_on_second_real_connected_notification_rolls_back_first_event_and_both_facts()throws Exception {
        var h=new ContactHandler(42,"CONNECTED_VALID",1,Fault.NONE);var inserts=new java.util.concurrent.atomic.AtomicInteger();
        try(var c=database.apiConnection()) {
            var faulty=(Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                try{var value=m.invoke(c,a);
                    if(m.getName().equals("prepareStatement") && ((String)a[0]).startsWith("insert into \"execution\".\"domain_event\" ")) {
                        return java.lang.reflect.Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(p2,m2,a2)->{
                            if(m2.getName().equals("execute") && inserts.incrementAndGet()==2)throw new SQLException("second event failure","08006");
                            try{return m2.invoke(value,a2);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                        });
                    }return value;
                }catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
            });assertThrows(RuntimeException.class,()->runtime(h).execute(faulty,h.envelope()));
        }
        assertEquals(2,inserts.get());assertRolledBack(h);
    }
    @Test void immutable_wait_hash_has_literal_null_timestamp_and_binary_golden_vector()throws Exception {
        var h=new ContactHandler(60,"NOT_CONNECTED",1,Fault.NONE);
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            sql(x,"update responsibility.task_occurrence set state='WAITING',revision=revision+1 where tenant_id=? and task_occurrence_id=?",h.tenant,h.task);
            sql(x,"insert into responsibility.wait_receipt (tenant_id,wait_receipt_id,task_occurrence_id,task_revision,wait_sequence,wait_reason_code,wait_contract_code,wait_contract_version,entered_waiting_at,resume_due_at,recorded_by_appointment_id,awaited_fact_type,awaited_fact_id,awaited_fact_hash) values (?,?,?,1,1,'CONTACT_RETRY','CONTACT_RETRY_V1',1,'2026-09-04T00:00:00Z','2026-09-04T01:00:00Z',?,'lead.lead',?,decode(repeat('01',32),'hex'))",h.tenant,id(6013),h.task,h.owner,h.lead);return null;
        });}
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
            var wait=R1EventReaders.databaseBacked().latestWait(x,h.tenant,h.task);
            assertEquals("50qW-BnsU4UkYsAIfbQ-qqAuyT7BjZbBwhgbYegJL6E",wait.selector().hash());assertEquals(id(6013),wait.selector().id());return null;
        });}
    }
    @Test void routing_event_is_selected_from_persisted_decision_code_and_content_hash()throws Exception {
        for(int scenario=0;scenario<4;scenario++) {
            final int variant=scenario;int fixture=70+scenario;
            UUID routingTask=id(fixture*100+14),decision=id(fixture*100+15),routingGrant=id(fixture*100+16);
            String decisionCode=scenario==0?"SCHEDULE_ROUTING_REVIEW":scenario==1?"RETRY_ASSIGNMENT_NOW":"REQUEST_SOURCE_INTAKE_STOP";
            var h=new ContactHandler(fixture,"NOT_CONNECTED",1,Fault.NONE) {
                @Override public CommandEnvelope.Type type(){return CommandEnvelope.Type.RECORD_ROUTING_DISPOSITION;}
                @Override public Context resolve(Connection c,CommandEnvelope e) {
                    var subject=new Subject("lead.lead",lead,1L,null);
                    return new Context(CommandScope.task(tenant,type(),routingTask,subject,Map.of()),new Request(e.actor(),subject,org,new Requirement("LEAD_ROUTING_DECIDE","ROUTING_SUPERVISOR",Path.DIRECT,routingGrant)));
                }
                @Override public void validateBeforeWork(Connection c,CommandEnvelope e,Context context) {}
                @Override public Result execute(Connection c,CommandEnvelope e,Context context)throws SQLException {
                    sql(c,"insert into responsibility.decision_record (tenant_id,decision_record_id,task_occurrence_id,decision_version,decided_by_appointment_id,authority_slot_code,decision_contract_code,decision_contract_version,decision_code,content_digest,rationale_summary,decided_at,decision_subject_type,decision_subject_id,decision_subject_revision) values (?,?,?,1,?,'ROUTING_SUPERVISOR','LEAD_ROUTING_DISPOSITION',1,?,decode(repeat('42',32),'hex'),'fixture',clock_timestamp(),'lead.lead',?,1)",tenant,decision,routingTask,owner,decisionCode,lead);
                    sql(c,"update responsibility.task_occurrence set state='DONE',revision=revision+1,completed_at=clock_timestamp(),completion_fact_type='responsibility.decision_record',completion_fact_id=?,completion_fact_hash=decode(repeat('42',32),'hex') where tenant_id=? and task_occurrence_id=?",decision,tenant,routingTask);
                    return Result.succeeded(new Subject("responsibility.decision_record",decision,null,"QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI"),variant==2?Event.SourceIntakeStopRequestedV1:Event.LeadRoutingDispositionRecordedV1);
                }
            };
            try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
                sql(x,"insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,'LEAD_ROUTING_DECIDE',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",h.tenant,routingGrant,h.owner,h.owner,h.org);
                sql(x,"insert into responsibility.task_occurrence (tenant_id,task_occurrence_id,owner_appointment_id,business_purpose_code,primary_command_code,expected_completion_fact_type,original_sla_code,original_sla_seconds,original_sla_due_at,state,created_at,subject_type,subject_id,subject_revision) values (?,?,?,'RESOLVE_LEAD_ROUTING_GAP','RECORD_ROUTING_DISPOSITION','responsibility.decision_record','R1_BUSINESS_4H_V1',14400,clock_timestamp()+interval '4 hours','OPEN',clock_timestamp(),'lead.lead',?,1)",h.tenant,routingTask,h.owner,h.lead);return null;
            });}
            if(scenario==3){assertThrows(SQLException.class,()->run(runtime(h),h.envelope()));assertEquals(List.of(0L,0L,0L,0L,0L),counts(h.tenant));}
            else {
                var outcome=run(runtime(h),h.envelope());assertEquals("QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI",outcome.resultFact().hash());assertEquals(decision,outcome.resultFact().id());assertEquals(List.of(1L,1L,1L,1L,1L),counts(h.tenant));
                try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{assertEquals(variant==2?"SourceIntakeStopRequestedV1":"LeadRoutingDispositionRecordedV1",scalar(x,"select event_type from execution.domain_event where tenant_id='"+h.tenant+"'"));return null;});}
            }
        }
    }
}
