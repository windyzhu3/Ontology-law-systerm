package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;

class R2FollowupAttemptIT extends R2QuoteWorkflowIT {
    Map<String,Object> attemptPayload(boolean quote)throws Exception {
        if(quote){var current=(Map<?,?>)context().get("opportunity");opportunity=new Subject("opportunity.opportunity",opportunity.id(),((Number)current.get("revision")).longValue(),null);}
        try(var c=database.apiConnection()){return inTransaction(c,Capability.QUERY,x->{
            var tasks=TaskFactory.databaseBacked().activeForLead(x,seed.tenant(),opportunity);
            var task=tasks.stream().filter(t->t.type()==(quote?TaskFactory.Type.RECORD_QUOTE_REPLY:TaskFactory.Type.PROGRESS_OPPORTUNITY)).findFirst().orElseThrow();
            var current=CurrentTaskReader.databaseBacked().read(x,seed.tenant(),task.selector().id());
            var wait=EventResponsibilityReader.databaseBacked().latestWait(x,seed.tenant(),task.selector().id());
            var p=new LinkedHashMap<String,Object>();p.put("opportunityId",opportunity.id().toString());p.put("expectedOpportunityRevision",opportunity.revision());
            p.put("responsibilityBasis",CommandScope.selector(current.responsibilityBasis()));p.put("task",Map.of("id",task.selector().id().toString(),"revision",task.selector().revision()));
            p.put("waitReceipt",wait==null?null:Map.of("id",wait.selector().id().toString(),"hash",wait.selector().hash()));
            var workflow=quote?(Map<?,?>)quotes().context(x,seed.request().actor(),opportunity.id()).get("workflow"):null;
            p.put("expectedWorkflow",workflow==null?null:workflow.get("selector"));
            p.put("values",Map.of("type","NO_REPLY","summary","本次尚未收到回复，按真实情况安排下一次联系","occurredAt",now().toString(),"nextCheckAt",now().plusSeconds(86400).toString()));return p;
        });}
    }
    void verifyAttempt(boolean quote)throws Exception {verifyAttempt(quote,86400);}
    void verifyAttempt(boolean quote,long delay)throws Exception {
        String before=scalar("select count(*) from opportunity.opportunity_progress where tenant_id=?",seed.tenant());var payload=attemptPayload(quote);
        var v=new LinkedHashMap<>((Map<String,Object>)payload.get("values"));v.put("nextCheckAt",now().plusSeconds(delay).toString());payload.put("values",v);
        var task=(Map<?,?>)payload.get("task");UUID prior=UUID.fromString((String)task.get("id"));String deadline=scalar("select original_sla_due_at::text from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),prior);
        var type=assertDoesNotThrow(()->CommandEnvelope.Type.valueOf(quote?"RECORD_QUOTE_FOLLOWUP_ATTEMPT":"RECORD_OPPORTUNITY_FOLLOWUP_ATTEMPT"));
        var e=new CommandEnvelope(type,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),payload);var result=execute(e);
        assertEquals(CommandOutcome.Status.SUCCEEDED,result.status(),result.rejectionCode());assertEquals("opportunity.followup_attempt",result.resultFact().type());assertEquals(result,execute(e));
        assertEquals(before,scalar("select count(*) from opportunity.opportunity_progress where tenant_id=?",seed.tenant()));
        assertEquals("0",scalar("select count(*) from opportunity.quote_response where tenant_id=?",seed.tenant()));
        assertEquals("1",scalar("select count(*) from opportunity.followup_attempt where tenant_id=?",seed.tenant()));
        assertEquals("CANCELLED",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),prior));
        assertEquals(deadline,scalar("select original_sla_due_at::text from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),prior));
        assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and state='WAITING'",seed.tenant(),opportunity.id()));
    }
    @Test void attempt_without_customer_confirmation_has_one_waiting_successor_and_no_effective_progress()throws Exception{setup(true,true);verifyAttempt(false);}
    @Test void ledger_discloses_followup_wait_basis_and_honors_exact_denial()throws Exception {
        setup(true,true);verifyAttempt(false);
        var reader=new R2OpportunityLedgerReadService(new byte[32],protection,cipher,io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked("R25_ATTEMPT_LEDGER_IT"));
        try(var c=database.apiConnection()){
            var page=reader.read(c,seed.request().actor(),"list",null,20,null,null,null);
            assertEquals("WAITING",((Map<?,?>)((List<?>)page.get("items")).getFirst()).get("taskState"));
        }
        assertTrue(Long.parseLong(scalar("select count(*) from audit.audit_entry_classified_v where tenant_id=? and action_code='READ_OPPORTUNITY_LEDGER' and change_summary::text like '%opportunity.followup_attempt%'",seed.tenant()))>0);
        mutate("insert into identity.object_access_grant(tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_hash) select ?,?,?,?,'SALES_OPPORTUNITY_OWNER','DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),'opportunity.followup_attempt',followup_attempt_id,body_digest from opportunity.followup_attempt where tenant_id=?",seed.tenant(),UUID.randomUUID(),seed.request().actor().principalId(),seed.appointment(),seed.tenant());
        try(var c=database.apiConnection()){assertTrue(((List<?>)reader.read(c,seed.request().actor(),"list",null,20,null,null,null).get("items")).isEmpty());}
    }
    @Test void attempt_without_quote_reply_never_invents_customer_response()throws Exception{delivered();verifyAttempt(true);}
    void recoveryAfterAttempt(boolean quote)throws Exception {
        if(quote)delivered();else setup(true,true);
        var payload=attemptPayload(quote);var values=new LinkedHashMap<>((Map<String,Object>)payload.get("values"));var due=now().plusSeconds(2);values.put("nextCheckAt",due.toString());payload.put("values",values);
        var recorded=execute(new CommandEnvelope(CommandEnvelope.Type.valueOf(quote?"RECORD_QUOTE_FOLLOWUP_ATTEMPT":"RECORD_OPPORTUNITY_FOLLOWUP_ATTEMPT"),UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),payload));
        assertEquals(CommandOutcome.Status.SUCCEEDED,recorded.status(),recorded.rejectionCode());
        while(now().isBefore(due))Thread.sleep(50);
        var actor=service("OPPORTUNITY_TASK_RECOVER");var discovery=new R2OpportunityDiscoveryService(new byte[32]);
        R2OpportunityDiscoveryService.Response page;try(var c=database.apiConnection()){page=discovery.list(c,actor,R2OpportunityDiscoveryService.Kind.DUE,100,null);}
        assertEquals(200,page.status());assertEquals(1,page.page().candidates().size());var candidate=page.page().candidates().getFirst();assertEquals(recorded.resultFact(),candidate.progress());
        var runtime=R2OpportunityCommandRuntime.fromSourcePolicy(policies,protection,cipher,null,"ATTEMPT_RECOVERY_IT");var command=R2OpportunityCommandRuntime.recovery(actor,candidate,UUID.randomUUID());
        try(var c=database.apiConnection()){var result=assertInstanceOf(CommandOutcome.class,runtime.execute(c,command));assertEquals(CommandOutcome.Status.SUCCEEDED,result.status(),result.rejectionCode());assertEquals(result,runtime.execute(c,command));}
        assertEquals("OPEN",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),candidate.task().id()));
        if(!quote){
            var at=now();try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{var task=TaskFactory.databaseBacked().read(x,seed.tenant(),candidate.task().id());R2OpportunityProgressServices.create(cipher).record(x,seed.request().actor(),opportunity,task.selector(),new io.github.windyzhu3.ontologylaw.opportunity.OpportunityProgressInput("PHONE_CONNECTED","本次实际联系并确认业务进展",at,at.plusSeconds(86400)),java.time.ZoneId.of("Asia/Shanghai"),at);return null;});}
            assertEquals("DONE",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),candidate.task().id()));
            assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and state='WAITING'",seed.tenant(),opportunity.id()));
        }
        assertEquals("0",scalar("select count(*) from opportunity.quote_response where tenant_id=?",seed.tenant()));
    }
    @Test void attempt_ordinary_wait_is_discovered_reopened_and_replayed_once()throws Exception{recoveryAfterAttempt(false);}
    @Test void attempt_quote_wait_is_discovered_reopened_and_replayed_once()throws Exception{recoveryAfterAttempt(true);}
    @Test void attempt_past_due_and_future_contact_are_rejected_without_closing_task()throws Exception {
        setup(true,true);var payload=attemptPayload(false);var task=(Map<?,?>)payload.get("task");
        for(String field:List.of("nextCheckAt","occurredAt")){var values=new LinkedHashMap<>((Map<String,Object>)payload.get("values"));values.put(field,(field.equals("nextCheckAt")?now().minusSeconds(1):now().plusSeconds(3600)).toString());var p=new LinkedHashMap<>(payload);p.put("values",values);var result=execute(new CommandEnvelope(CommandEnvelope.Type.RECORD_OPPORTUNITY_FOLLOWUP_ATTEMPT,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),p));assertEquals("VALIDATION_FAILED",result.rejectionCode());}
        assertEquals("0",scalar("select count(*) from opportunity.followup_attempt where tenant_id=?",seed.tenant()));assertEquals("WAITING",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),UUID.fromString((String)task.get("id"))));
    }
    @Test void attempt_early_reschedule_history_is_audited_and_recovery_revoked_with_owner()throws Exception {
        setup(true,true);var payload=attemptPayload(false);var e=new CommandEnvelope(CommandEnvelope.Type.RECORD_OPPORTUNITY_FOLLOWUP_ATTEMPT,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),payload);assertEquals(CommandOutcome.Status.SUCCEEDED,execute(e).status());
        var second=attemptPayload(false);assertEquals(CommandOutcome.Status.SUCCEEDED,execute(new CommandEnvelope(e.type(),UUID.randomUUID(),UUID.randomUUID(),e.actor(),second)).status());
        try(var c=database.apiConnection()){var read=new R2FollowupAttemptReadService(cipher,io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked("ATTEMPT_READ_IT")).read(c,e.actor(),opportunity.id());assertEquals(2,((List<?>)read.get("history")).size());assertEquals("WAITING",read.get("taskState"));assertEquals(e.type().name(),read.get("command"));assertEquals(200,new CommandReceiptRecoveryService(policies,protection,null,"ATTEMPT_RECEIPT_IT").read(c,e.actor(),e.commandId(),UUID.randomUUID()).status());}
        assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and state='WAITING'",seed.tenant(),opportunity.id()));
        mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_code='SALES_OPPORTUNITY_OWNER'",seed.tenant());
        try(var c=database.apiConnection()){assertEquals(403,new CommandReceiptRecoveryService(policies,protection,null,"ATTEMPT_REVOKED_IT").read(c,e.actor(),e.commandId(),UUID.randomUUID()).status());assertThrows(R1ServiceReadRuntime.Failure.class,()->new R2FollowupAttemptReadService(cipher,io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked("ATTEMPT_READ_IT")).read(c,e.actor(),opportunity.id()));}
    }
    @Test void attempt_followed_by_actual_quote_acceptance_finishes_the_wait()throws Exception {
        var evidence=delivered();verifyAttempt(true);
        quoteCommand("RECORD_QUOTE_RESPONSE",Map.of("kind","ACCEPTED","statement","本次明确接受","occurredAt",now().toString(),"evidence",R2CustomerRequirementsServices.selector(evidence)));
        assertEquals("1",scalar("select count(*) from opportunity.quote_response where tenant_id=?",seed.tenant()));assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and business_purpose_code='RECORD_QUOTE_REPLY' and state in ('OPEN','WAITING')",seed.tenant(),opportunity.id()));
    }
    void transferAttempt(boolean quote)throws Exception {transferAttempt(quote,false);}
    void transferAttempt(boolean quote,boolean recoverInherited)throws Exception {transferAttempt(quote,recoverInherited,false);}
    void transferAttempt(boolean quote,boolean recoverInherited,boolean preparation)throws Exception {
        if(preparation){setup(true,true);confirmed();try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"QUOTE_READ");grant(x,"QUOTE_PREPARE");return null;});}quoteCommand("START_QUOTE_PREPARATION",Map.of());}else{if(quote)delivered();else setup(true,true);verifyAttempt(quote,recoverInherited?2:86400);}
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"OPPORTUNITY_OWNER_EXCEPTION_READ");grant(x,"OPPORTUNITY_OWNER_EXCEPTION_RESOLVE");return null;});}
        UUID principal=UUID.randomUUID(),receiver=UUID.randomUUID();
        mutate("insert into identity.principal(tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) values(?,?,'HUMAN','FIXTURE',?,'Attempt receiver','ACTIVE',clock_timestamp())",seed.tenant(),principal,CanonicalJson.digest(principal.toString()));
        mutate("insert into identity.appointment(tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values(?,?,?,?,'OWNER',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),receiver,principal,seed.org());
        for(String code:List.of("SALES_OPPORTUNITY_OWNER","QUOTE_READ","QUOTE_RESPONSE","QUOTE_PREPARE"))mutate("insert into identity.authority_grant(tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values(?,?,?,?,?,?,clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),receiver,seed.appointment(),seed.org(),code);
        mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and grantee_appointment_id=? and authority_code=?",seed.tenant(),seed.appointment(),preparation?"QUOTE_PREPARE":quote?"QUOTE_RESPONSE":"SALES_OPPORTUNITY_OWNER");
        var observer=service("OPPORTUNITY_OWNER_EXCEPTION_DISCOVER");var observed=execute(new CommandEnvelope(CommandEnvelope.Type.OBSERVE_OPPORTUNITY_OWNER_EXCEPTION,UUID.randomUUID(),UUID.randomUUID(),observer,Map.of("opportunityId",opportunity.id().toString(),"expectedOpportunityRevision",opportunity.revision())));assertEquals(CommandOutcome.Status.SUCCEEDED,observed.status(),observed.rejectionCode());
        io.github.windyzhu3.ontologylaw.opportunity.OpportunityOwnerExceptionService.Snapshot before;
        try(var c=database.apiConnection()){before=inTransaction(c,Capability.QUERY,x->R2OpportunityOwnerExceptionAssembly.service().read(x,seed.tenant(),observed.resultFact()));}
        String deadline=scalar("select original_sla_due_at::text from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),before.task().id());
        var p=new LinkedHashMap<String,Object>();p.put("opportunityId",opportunity.id().toString());p.put("expectedOpportunityRevision",opportunity.revision());p.put("exceptionId",before.selector().id().toString());p.put("expectedExceptionRevision",before.selector().revision());p.put("expectedBasis",CommandScope.selector(before.responsibility().basis()));p.put("expectedTask",CommandScope.selector(before.task()));p.put("expectedWait",CommandScope.selector(before.waitReceipt()));p.put("reason","主管确认交接本次等待");p.put("receiverAppointmentId",receiver.toString());
        var transferred=execute(new CommandEnvelope(CommandEnvelope.Type.TRANSFER_OPPORTUNITY_RESPONSIBILITY,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),p));assertEquals(CommandOutcome.Status.SUCCEEDED,transferred.status(),transferred.rejectionCode());
        var actor=new io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor(seed.tenant(),principal,receiver,null,null);
        if(preparation){try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{var ctx=quotes().context(x,actor,opportunity.id());var workflow=(Map<?,?>)ctx.get("workflow");var t=(Map<?,?>)workflow.get("task");assertEquals(receiver,TaskFactory.databaseBacked().read(x,seed.tenant(),UUID.fromString((String)t.get("id"))).owner());assertEquals("OPEN",workflow.get("state"));return null;});}return;}
        Map<String,Object> ctx;try(var c=database.apiConnection()){ctx=new R2FollowupAttemptReadService(cipher,io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked("ATTEMPT_HANDOFF_IT")).read(c,actor,opportunity.id());}
        assertEquals(quote?"RECORD_QUOTE_FOLLOWUP_ATTEMPT":"RECORD_OPPORTUNITY_FOLLOWUP_ATTEMPT",ctx.get("command"));var nextTask=(Map<?,?>)ctx.get("task");assertEquals("WAITING",ctx.get("taskState"));assertEquals(deadline,scalar("select original_sla_due_at::text from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),UUID.fromString((String)nextTask.get("id"))));
        if(recoverInherited){
            var recoveryActor=service("OPPORTUNITY_TASK_RECOVER");var discovery=new R2OpportunityDiscoveryService(new byte[32]);R2OpportunityDiscoveryService.Response candidates;
            try(var c=database.apiConnection()){candidates=discovery.list(c,recoveryActor,R2OpportunityDiscoveryService.Kind.DUE,100,null);}assertEquals(1,candidates.page().candidates().size());
            var command=R2OpportunityCommandRuntime.recovery(recoveryActor,candidates.page().candidates().getFirst(),UUID.randomUUID());
            try(var c=database.apiConnection()){var fresh=R2OpportunityCommandRuntime.fromSourcePolicy(policies,protection,cipher,null,"ATTEMPT_HANDOFF_RECOVERY_IT");var result=assertInstanceOf(CommandOutcome.class,fresh.execute(c,command));assertEquals(CommandOutcome.Status.SUCCEEDED,result.status(),result.rejectionCode());var restarted=R2OpportunityCommandRuntime.fromSourcePolicy(policies,protection,cipher,null,"ATTEMPT_RESTART_IT");assertEquals(result,restarted.execute(c,command));}
            try(var c=database.apiConnection()){ctx=new R2FollowupAttemptReadService(cipher,io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked("ATTEMPT_HANDOFF_IT")).read(c,actor,opportunity.id());}assertEquals("OPEN",ctx.get("taskState"));
        }
        var body=new LinkedHashMap<String,Object>();body.put("opportunityId",opportunity.id().toString());body.put("expectedOpportunityRevision",opportunity.revision());for(String k:List.of("responsibilityBasis","task","waitReceipt","expectedWorkflow"))body.put(k,ctx.get(k));body.put("values",Map.of("type","NO_REPLY","summary","交接后提前安排下一次联系","occurredAt",now().toString(),"nextCheckAt",now().plusSeconds(90000).toString()));
        var result=execute(new CommandEnvelope(CommandEnvelope.Type.valueOf((String)ctx.get("command")),UUID.randomUUID(),UUID.randomUUID(),actor,body));assertEquals(CommandOutcome.Status.SUCCEEDED,result.status(),result.rejectionCode());
    }
    @Test void attempt_ordinary_handoff_preserves_wait_and_allows_new_owner_to_reschedule()throws Exception{transferAttempt(false);}
    @Test void attempt_quote_handoff_preserves_wait_and_allows_new_owner_to_reschedule()throws Exception{transferAttempt(true);}
    @Test void attempt_command_audit_failure_rolls_back_fact_task_wait_event_and_receipt()throws Exception {
        setup(true,true);var p=attemptPayload(false);String before=scalar("select count(*) from responsibility.task_occurrence where tenant_id=?",seed.tenant());
        var fail=new io.github.windyzhu3.ontologylaw.audit.AuditAppender(){public void append(java.sql.Connection c,Entry e)throws java.sql.SQLException{throw new java.sql.SQLException("Injected audit failure");}};
        var isolated=new CommandRuntime(List.of(new R2FollowupAttemptCommand(CommandEnvelope.Type.RECORD_OPPORTUNITY_FOLLOWUP_ATTEMPT,cipher,(c,a,o)->java.time.ZoneId.of("Asia/Shanghai"))),io.github.windyzhu3.ontologylaw.identity.AuthorizationService.databaseBacked(),fail,R2OpportunityCommandRuntime.authorization(io.github.windyzhu3.ontologylaw.lead.R1AuthorizationReaders.databaseBacked(policies)),R2OpportunityCommandRuntime.events(io.github.windyzhu3.ontologylaw.lead.R1EventReaders.databaseBacked(),cipher));
        var e=new CommandEnvelope(CommandEnvelope.Type.RECORD_OPPORTUNITY_FOLLOWUP_ATTEMPT,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),p);
        try(var c=database.apiConnection()){assertThrows(java.sql.SQLException.class,()->isolated.execute(c,e));}
        assertEquals("0",scalar("select count(*) from opportunity.followup_attempt where tenant_id=?",seed.tenant()));assertEquals(before,scalar("select count(*) from responsibility.task_occurrence where tenant_id=?",seed.tenant()));
        assertEquals("0",scalar("select count(*) from execution.domain_event where tenant_id=? and event_type='SalesFollowupAttemptRecordedV1'",seed.tenant()));
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{assertNull(CommandReceiptReader.databaseBacked().read(x,seed.tenant(),e.commandId()));return null;});}
    }
    @Test void attempt_database_rejects_cancellation_without_the_exact_attempt_fact()throws Exception {
        setup(true,true);var p=attemptPayload(false);var t=(Map<?,?>)p.get("task");
        try(var c=database.apiConnection()){
            var error=assertThrows(java.sql.SQLException.class,()->inTransaction(c,Capability.COMMAND,x->{try(var update=x.prepareStatement("update responsibility.task_occurrence set state='CANCELLED',revision=revision+1,cancelled_at=clock_timestamp(),cancellation_reason_code='R2_FOLLOWUP_ATTEMPT_V1',cancellation_fact_type='opportunity.followup_attempt',cancellation_fact_id=?,cancellation_fact_hash=? where tenant_id=? and task_occurrence_id=?")){update.setObject(1,UUID.randomUUID());update.setBytes(2,new byte[32]);update.setObject(3,seed.tenant());update.setObject(4,UUID.fromString((String)t.get("id")));update.executeUpdate();}return null;}));
            assertEquals("23514",error.getSQLState());assertTrue(error.getMessage().contains("missing exact attempt"),error.getMessage());
        }
        assertEquals("WAITING",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),UUID.fromString((String)t.get("id"))));
    }
    @Test void attempt_history_disclosure_audit_failure_releases_no_body()throws Exception {
        setup(true,true);verifyAttempt(false);var fail=new io.github.windyzhu3.ontologylaw.audit.AuditAppender(){public void append(java.sql.Connection c,Entry e)throws java.sql.SQLException{throw new java.sql.SQLException("Injected");}public void append(java.sql.Connection c,FollowupAttemptDisclosureEntry e)throws java.sql.SQLException{throw new java.sql.SQLException("Injected");}};
        try(var c=database.apiConnection()){assertThrows(java.sql.SQLException.class,()->new R2FollowupAttemptReadService(cipher,fail).read(c,seed.request().actor(),opportunity.id()));}
    }
    @Test void attempt_existing_quote_preparation_handoff_resolves_current_task()throws Exception{transferAttempt(true,false,true);}
    @Test void attempt_ordinary_inherited_wait_recovers_after_runtime_restart()throws Exception{transferAttempt(false,true);}
    @Test void attempt_quote_inherited_wait_recovers_after_runtime_restart()throws Exception{transferAttempt(true,true);}
    @Test void attempt_successor_uses_source_timezone_across_weekend_and_dst_boundary()throws Exception {
        setup(true,true);var old=policies.find("FIXTURE");policies=new io.github.windyzhu3.ontologylaw.lead.R1SourcePolicyRegistry(Map.of("FIXTURE",new io.github.windyzhu3.ontologylaw.lead.R1SourcePolicyRegistry.SourcePolicy(old.assignmentMode(),old.routingOrganizationRootCodes(),old.routingSupervisorRootCode(),old.sourceIntakeRootCode(),"America/New_York")));
        runtime=R2OpportunityCommandRuntime.fromSourcePolicy(policies,protection,cipher,null,"ATTEMPT_ZONE_IT");var payload=attemptPayload(false);var values=new LinkedHashMap<>((Map<String,Object>)payload.get("values"));values.put("nextCheckAt","2031-11-02T07:00:00Z");payload.put("values",values);
        var result=execute(new CommandEnvelope(CommandEnvelope.Type.RECORD_OPPORTUNITY_FOLLOWUP_ATTEMPT,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),payload));assertEquals(CommandOutcome.Status.SUCCEEDED,result.status(),result.rejectionCode());
        assertEquals(Long.toString(java.time.Instant.parse("2031-11-03T18:00:00Z").getEpochSecond()),scalar("select extract(epoch from t.original_sla_due_at)::bigint::text from responsibility.task_occurrence t join opportunity.followup_attempt a on a.tenant_id=t.tenant_id and a.task_id=t.task_occurrence_id where a.tenant_id=?",seed.tenant()));
    }

}
