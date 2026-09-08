package io.github.windyzhu3.ontologylaw.lead;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import io.github.windyzhu3.ontologylaw.evidence.EvidenceReferenceReader;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;

class EvidenceReferenceIT extends ContactFlowFixture {
    private UUID submission,binding;
    @Test void delegated_complete_contact_path_qualifies_reference_and_submitter_or_object_allow_does_not_replace_path()throws Exception{
        setupContact();evidence(current.lead(),true);UUID principal=UUID.randomUUID(),appointment=UUID.randomUUID(),delegation=UUID.randomUUID();
        mutate("insert into identity.principal (tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) values (?,?,'HUMAN','DELEGATE',?,'Delegate','ACTIVE',clock_timestamp())",seed.tenant(),principal,CanonicalJson.digest(principal.toString()));
        mutate("insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'DELEGATE',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),appointment,principal,seed.org());
        mutate("insert into identity.delegation_grant (tenant_id,delegation_grant_id,source_authority_grant_id,delegator_appointment_id,delegate_appointment_id,scope_organization_unit_id,valid_from,state,created_at) values (?,?,?,?,?,?,clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),delegation,seed.grant(),seed.appointment(),appointment,seed.org());
        var actor=new AuthorizationService.Actor(seed.tenant(),principal,appointment,seed.principal(),seed.appointment());var values=contact("CONNECTED_VALID");values.put("evidenceSubmissionId",submission.toString());var original=prepare(values);
        var command=new CommandEnvelope(original.type(),original.commandId(),original.correlationId(),actor,original.payload(),new CommandEnvelope.TaskPrecondition(current.selector().id(),R1ResourceTags.task(actor,current.selector(),current.state())));
        assertEquals(CommandOutcome.Status.SUCCEEDED,execute(command).status());
        assertEquals("DELEGATED",scalar("select authorization_path_code from audit.audit_entry_classified_v where tenant_id=? and command_id=?",seed.tenant(),command.commandId()));
    }
    @Test void submitter_with_object_only_allow_cannot_bypass_missing_complete_sales_grant()throws Exception{
        setupContact();evidence(current.lead(),true);var values=contact("CONNECTED_VALID");values.put("evidenceSubmissionId",submission.toString());var command=prepare(values);
        mutate("insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values (?,?,?,?,'SALES_CONTACT_OWNER','ALLOW',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),'lead.lead',?,?)",seed.tenant(),UUID.randomUUID(),seed.principal(),seed.appointment(),current.lead().id(),current.lead().revision());
        mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_grant_id=?",seed.tenant(),seed.grant());var before=counts();
        try(var c=database.apiConnection()){assertEquals("NOT_AUTHORIZED",assertThrows(CommandHandler.Rejected.class,()->runtime.execute(c,command)).code());}assertEquals(before,counts());
    }
    @Test void foreign_submission_is_invisible_and_cannot_change_its_tenant_sentinel()throws Exception{
        setupContact();evidence(current.lead(),true);UUID foreign=seed.tenant(),foreignSubmission=submission;String before=scalar("select row_to_json(e)::text from evidence.evidence_submission e where tenant_id=? and evidence_submission_id=?",foreign,foreignSubmission);setupContact();var values=contact("CONNECTED_VALID");values.put("evidenceSubmissionId",foreignSubmission.toString());var command=prepare(values);var counts=counts();
        try(var c=database.apiConnection()){assertEquals("NOT_FOUND",assertThrows(CommandHandler.Rejected.class,()->runtime.execute(c,command)).code());}assertEquals(counts,counts());assertEquals(before,scalar("select row_to_json(e)::text from evidence.evidence_submission e where tenant_id=? and evidence_submission_id=?",foreign,foreignSubmission));
    }
    @Test void hidden_evidence_card_selects_next_eligible_card_without_changing_saved_candidate()throws Exception{
        setupContact();evidence(current.lead(),true);var values=contact("NOT_CONNECTED");values.put("evidenceSubmissionId",submission.toString());prepare(values);
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"LEAD_INGRESS_COMPLETE");return null;});}
        var next=addTask(UUID.randomUUID(),businessAt.plusSeconds(1),businessAt.plusSeconds(14400),"OPEN");revoke();var response=readCard(null);assertEquals(200,response.status());String body=CanonicalJson.encode(response.body());assertTrue(body.contains(next.selector().id().toString()));assertFalse(body.contains(submission.toString()));
    }
    @ParameterizedTest @ValueSource(strings={"TASK","LEAD","SUBMISSION","BINDING"})
    void final_exact_subject_deny_rolls_back_business_and_keeps_only_rejected_terminal_writes(String target)throws Exception{
        setupContact();evidence(current.lead(),true);var ref=reference();var values=contact("CONNECTED_VALID");values.put("evidenceSubmissionId",submission.toString());var command=prepare(values);
        var denied=switch(target){case "TASK"->new Subject(current.selector().type(),current.selector().id(),1L,null);case "LEAD"->current.lead();case "SUBMISSION"->ref.submission();default->ref.binding();};
        var handlers=new ArrayList<CommandHandler>();for(var handler:new LeadCommands(policies,protection).handlers())handlers.add(handler.type()==command.type()?afterBusiness(handler,()->{
            try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{AuthorizationService.databaseBacked().lockForMutation(x,seed.tenant());sql(x,"insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision,object_subject_hash) values (?,?,?,?,'SALES_CONTACT_OWNER','DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),?,?,?,?)",seed.tenant(),UUID.randomUUID(),seed.principal(),seed.appointment(),denied.type(),denied.id(),denied.revision(),denied.hash()==null?null:Base64.getUrlDecoder().decode(denied.hash()));return null;});}
        }):handler);
        runtime=new CommandRuntime(handlers,AuthorizationService.databaseBacked(),io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked("EVIDENCE_FINAL_IT"),R1AuthorizationReaders.databaseBacked(policies),R1EventReaders.databaseBacked());var before=counts();var outcome=execute(command);assertEquals(CommandOutcome.Status.REJECTED,outcome.status());assertEquals(target.equals("TASK")||target.equals("LEAD")?"NOT_AUTHORIZED":"NOT_FOUND",outcome.rejectionCode());delta(before,0,0,0,0,0,0,1,1,1,0,0);
    }
    @FunctionalInterface private interface SqlAction{void run()throws SQLException;}
    private CommandHandler afterBusiness(CommandHandler delegate,SqlAction after){return new CommandHandler(){
        public CommandEnvelope.Type type(){return delegate.type();}public Context resolve(Connection c,CommandEnvelope e)throws SQLException{return delegate.resolve(c,e);}public void lockRoots(Connection c,CommandEnvelope e,Context x)throws SQLException{delegate.lockRoots(c,e,x);}public void recoveryEligibility(Connection c,CommandEnvelope e,Context x)throws SQLException{delegate.recoveryEligibility(c,e,x);}public void validateBeforeWork(Connection c,CommandEnvelope e,Context x)throws SQLException{delegate.validateBeforeWork(c,e,x);}public Result execute(Connection c,CommandEnvelope e,Context x)throws SQLException{var result=delegate.execute(c,e,x);after.run();return result;}public void validateBeforeCommit(Connection c,CommandEnvelope e,Context x,Result r)throws SQLException{delegate.validateBeforeCommit(c,e,x,r);}
    };}
    @Override protected void setupContact()throws Exception{
        super.setupContact();var handlers=new ArrayList<CommandHandler>(new LeadCommands(policies,protection).handlers());handlers.addAll(new ActionDraftCommands(protection).handlers());
        runtime=new CommandRuntime(handlers,io.github.windyzhu3.ontologylaw.identity.AuthorizationService.databaseBacked(),io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked("EVIDENCE_REFERENCE_IT"),R1AuthorizationReaders.databaseBacked(policies),R1EventReaders.databaseBacked());
        try(var c=database.apiConnection()){businessAt=inTransaction(c,Capability.QUERY,x->io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.databaseBacked().now(x));}
    }
    @Test void absent_reference_reads_no_evidence_and_present_reference_uses_only_two_tables_in_query_role()throws Exception{
        setupContact();var noEvidence=prepare(contact("CONNECTED_VALID"));var queries=new ArrayList<String>();
        try(var c=database.apiConnection()){assertEquals(CommandOutcome.Status.SUCCEEDED,((CommandOutcome)runtime.execute(observed(c,queries),noEvidence)).status());}assertTrue(queries.isEmpty());
        setupContact();evidence(current.lead(),true);var values=contact("CONNECTED_VALID");values.put("evidenceSubmissionId",submission.toString());var command=prepare(values);
        try(var c=database.apiConnection()){assertEquals(CommandOutcome.Status.SUCCEEDED,((CommandOutcome)runtime.execute(observed(c,queries),command)).status());}
        assertEquals(4,queries.size(),"Initial and final QUERY read exactly Submission + Binding");assertTrue(queries.stream().allMatch(q->q.startsWith("law_app_query:")&&!q.contains("upload_session")&&!q.contains("from \"evidence\".\"received_source_object\"")));
    }
    private Connection observed(Connection delegate,List<String> reads){return (Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
        if(method.getName().equals("prepareStatement")&&args[0] instanceof String query&&query.toLowerCase(Locale.ROOT).startsWith("select")&&query.contains("from \"evidence\".")){
            try(var s=delegate.createStatement();var r=s.executeQuery("select current_user")){r.next();reads.add(r.getString(1)+":"+query);}
        }
        try{return method.invoke(delegate,args);}catch(java.lang.reflect.InvocationTargetException ex){throw ex.getCause();}
    });}
    @Test void binding_writer_wait_fence_prevents_stale_reference_from_committing_business_facts()throws Exception{
        setupContact();evidence(current.lead(),true);var values=contact("CONNECTED_VALID");values.put("evidenceSubmissionId",submission.toString());var command=prepare(values);var before=counts();
        try(var writer=database.apiConnection();var runner=database.apiConnection();var observer=database.apiConnection();var pool=java.util.concurrent.Executors.newSingleThreadExecutor()){
            writer.setAutoCommit(false);setLocalRole(writer,Capability.COMMAND);R1BusinessFence.databaseBacked().exclusive(writer,seed.tenant());int pid;try(var s=runner.createStatement();var r=s.executeQuery("select pg_backend_pid()")){r.next();pid=r.getInt(1);}
            var future=pool.submit(()->runtime.execute(runner,command));
            try{LeadBusinessFixture.awaitBlocked(observer,pid);sql(writer,"update evidence.evidence_binding set revoked_at=clock_timestamp(),revoked_by_appointment_id=?,revocation_authorization_digest=?,revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and evidence_binding_id=?",seed.appointment(),CanonicalJson.digest("revoke"),seed.tenant(),binding);writer.commit();}finally{writer.rollback();}
            var result=assertInstanceOf(CommandOutcome.class,future.get(15,java.util.concurrent.TimeUnit.SECONDS));assertEquals(CommandOutcome.Status.REJECTED,result.status());assertEquals("NOT_FOUND",result.rejectionCode());
        }
        delta(before,0,0,0,0,0,0,1,1,1,0,0);
        assertEquals("DRAFT",scalar("select state from responsibility.action_draft where tenant_id=? and task_occurrence_id=?",seed.tenant(),current.selector().id()));
    }
    /** Import fixture exercises the frozen physical chain; it never disables constraints or changes grants. */
    private void evidence(Subject target,boolean bind)throws Exception{
        submission=UUID.randomUUID();binding=UUID.randomUUID();UUID session=UUID.randomUUID(),object=UUID.randomUUID();String key=UUID.randomUUID().toString();byte[] digest=CanonicalJson.digest("fixture bytes");
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            sql(x,"insert into evidence.upload_session (tenant_id,upload_session_id,object_store_code,object_key,purpose_code,intake_contract_code,intake_contract_version,intake_contract_digest,upload_capability_hash,status,created_by_appointment_id,created_at,expires_at,target_type,target_id,target_revision,target_hash) values (?,?,'FIXTURE',?,'CONTACT','FIXTURE',1,?,?,'OPEN',?,clock_timestamp(),clock_timestamp()+interval '1 hour',?,?,?,?)",seed.tenant(),session,key,digest,digest,seed.appointment(),target.type(),target.id(),target.revision(),target.hash()==null?null:Base64.getUrlDecoder().decode(target.hash()));
            sql(x,"update evidence.upload_session set status='OBJECT_RECEIVED',received_at=clock_timestamp(),revision=1 where tenant_id=? and upload_session_id=?",seed.tenant(),session);
            sql(x,"insert into evidence.received_source_object (tenant_id,received_source_object_id,upload_session_id,object_store_code,object_key,object_version,size_bytes,server_sha256,detected_media_type,scan_result,scan_engine_code,scan_contract_version,scanned_at,received_at) values (?,?,?,'FIXTURE',?,'immutable-fixture-version',13,?,'text/plain','PASSED','FIXTURE',1,clock_timestamp(),clock_timestamp())",seed.tenant(),object,session,key,digest);
            sql(x,"insert into evidence.evidence_submission (tenant_id,evidence_submission_id,received_source_object_id,submission_contract_code,submission_contract_version,submitted_by_appointment_id,submitted_at) values (?,?,?,'FIXTURE',1,?,clock_timestamp())",seed.tenant(),submission,object,seed.appointment());
            if(bind)sql(x,"insert into evidence.evidence_binding (tenant_id,evidence_binding_id,evidence_submission_id,purpose_code,bound_by_appointment_id,bound_at,target_type,target_id,target_revision,target_hash) values (?,?,?,'CONTACT',?,clock_timestamp(),?,?,?,?)",seed.tenant(),binding,submission,seed.appointment(),target.type(),target.id(),target.revision(),target.hash()==null?null:Base64.getUrlDecoder().decode(target.hash()));
            else {setLocalRole(x,Capability.QUERY);assertNull(EvidenceReferenceReader.databaseBacked().read(x,seed.tenant(),submission));setLocalRole(x,Capability.COMMAND);}
            sql(x,"update evidence.upload_session set status='FINALIZED',finalized_at=clock_timestamp(),revision=2 where tenant_id=? and upload_session_id=?",seed.tenant(),session);return null;
        });}
    }
    @Test void qualified_existing_submission_is_recorded_and_disclosed_from_saved_contact_draft()throws Exception{
        setupContact();evidence(current.lead(),true);var values=contact("CONNECTED_VALID");values.put("evidenceSubmissionId",submission.toString());var command=prepare(values);
        var card=readCard(null);assertEquals(200,card.status(),card.errorCode());assertTrue(CanonicalJson.encode(card.body()).contains(submission.toString()),CanonicalJson.encode(card.body()));
        var before=counts();var outcome=execute(command);assertEquals(CommandOutcome.Status.SUCCEEDED,outcome.status());delta(before,1,1,0,0,0,0,1,1,1,2,2);
        assertEquals(submission.toString(),scalar("select evidence_submission_id::text from lead.lead_contact_result where tenant_id=? and lead_contact_result_id=?",seed.tenant(),outcome.resultFact().id()));
    }
    @ParameterizedTest @ValueSource(strings={"MISSING","OTHER_LEAD","OLD_REVISION","OTHER_TARGET","REVOKED","SUBMISSION_DENY","BINDING_DENY"})
    void unqualified_reference_is_uniform_pre_slot_not_found_and_never_disclosed(String defect)throws Exception{
        setupContact();Subject target=switch(defect){case "OTHER_LEAD"->new Subject("lead.lead",UUID.randomUUID(),current.lead().revision(),null);case "OLD_REVISION"->new Subject("lead.lead",current.lead().id(),0L,null);case "OTHER_TARGET"->current.selector();default->current.lead();};
        evidence(target,!defect.equals("NO_BINDING"));
        if(defect.equals("MISSING"))submission=UUID.randomUUID();
        if(defect.equals("REVOKED"))revoke();
        if(defect.endsWith("DENY")){var ref=reference();deny(defect.equals("SUBMISSION_DENY")?ref.submission():ref.binding(),"SALES_CONTACT_OWNER");}
        var values=contact("CONNECTED_VALID");values.put("evidenceSubmissionId",submission.toString());var command=prepare(values);var before=counts();
        try(var c=database.apiConnection()){assertEquals("NOT_FOUND",assertThrows(CommandHandler.Rejected.class,()->runtime.execute(c,command)).code());}
        assertEquals(before,counts());var card=readCard(null);assertEquals(200,card.status());assertFalse(CanonicalJson.encode(card.body()).contains(submission.toString()));
        assertEquals(submission.toString(),scalar("select candidate_payload->>'evidenceSubmissionId' from responsibility.action_draft where tenant_id=? and task_occurrence_id=?",seed.tenant(),current.selector().id()));
    }
    @Test void unbound_submission_is_ineligible_even_before_physical_promotion_constraint_rejects_commit()throws Exception{
        setupContact();var before=counts();var failure=assertThrows(SQLException.class,()->evidence(current.lead(),false));assertEquals("23514",failure.getSQLState());assertEquals(before,counts());
        assertEquals("0",scalar("select count(*)::text from evidence.evidence_submission where tenant_id=?",seed.tenant()));
    }
    private EvidenceReferenceReader.Reference reference()throws Exception{try(var c=database.apiConnection()){return inTransaction(c,Capability.QUERY,x->EvidenceReferenceReader.databaseBacked().read(x,seed.tenant(),submission));}}
    private void revoke()throws Exception{try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{R1BusinessFence.databaseBacked().exclusive(x,seed.tenant());sql(x,"update evidence.evidence_binding set revoked_at=clock_timestamp(),revoked_by_appointment_id=?,revocation_authorization_digest=?,revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and evidence_binding_id=?",seed.appointment(),CanonicalJson.digest("revoke"),seed.tenant(),binding);return null;});}}
    @Test void cached_disclosure_reaudits_two_exact_evidence_sources_and_revocation_hides_without_rewriting()throws Exception{
        setupContact();evidence(current.lead(),true);var values=contact("NOT_CONNECTED");values.put("evidenceSubmissionId",submission.toString());
        var save=new CommandEnvelope(CommandEnvelope.Type.SAVE_ACTION_DRAFT,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),Map.of("actionCode","RECORD_CONTACT_RESULT","schemaVersion",1,"values",values),null,new CommandEnvelope.DraftPrecondition(current.selector().id(),null,"*"));assertEquals(CommandOutcome.Status.SUCCEEDED,execute(save).status());long commandAudits=auditCount();
        var card=readCard(null);assertEquals(200,card.status());long count=auditCount()-commandAudits;var cached=readCard(card.etag());assertEquals(304,cached.status());assertNull(cached.body());assertEquals(count*2+commandAudits,auditCount());
        assertEquals("4",scalar("select count(*)::text from audit.audit_entry_classified_v where tenant_id=? and subject_type in ('evidence.evidence_submission','evidence.evidence_binding')",seed.tenant()));
        revoke();var hidden=readCard(card.etag());assertEquals(200,hidden.status());assertNotEquals(card.etag(),hidden.etag());assertFalse(CanonicalJson.encode(hidden.body()).contains(submission.toString()));assertEquals(count*2+commandAudits,auditCount());
        assertEquals(submission.toString(),scalar("select candidate_payload->>'evidenceSubmissionId' from responsibility.action_draft where tenant_id=? and task_occurrence_id=?",seed.tenant(),current.selector().id()));
    }
}
