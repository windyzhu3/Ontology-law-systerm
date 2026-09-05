package io.github.windyzhu3.ontologylaw.execution;

import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.lead.*;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.*;

class R1CommandPolicyIT extends CommandRuntimeIT {
    final AuthorizationService auth=AuthorizationService.databaseBacked();
    R1SourcePolicyRegistry sources(String root) {
        var value=new R1SourcePolicyRegistry.SourcePolicy(R1SourcePolicyRegistry.AssignmentMode.MANUAL,List.of(root),root,root,"Asia/Shanghai");
        return new R1SourcePolicyRegistry(Map.of("FIXTURE",value,"SECOND",value));
    }
    R1AuthorizationFacts readers=R1AuthorizationReaders.databaseBacked(sources("ROOT"));
    String hash(String value) {return Base64.getUrlEncoder().withoutPadding().encodeToString(CanonicalJson.digest(value));}
    void mutate(Handler h,String query,Object... args)throws Exception {
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{auth.lockForMutation(x,h.seed.tenant());sql(x,query,args);return null;});}
    }
    Handler captureHandler()throws Exception {return new Handler(AuthorizationServiceIT.seed(database,"HUMAN","LEAD_CAPTURE"));}
    CommandEnvelope envelope(Handler h,CommandEnvelope.Type type,Actor actor,Object payload) {
        return new CommandEnvelope(type,UUID.randomUUID(),UUID.randomUUID(),actor,payload);
    }
    CommandHandler.Context capture(Handler h,String account,Requirement requirement) {
        var org=new Subject("identity.organization_unit",h.seed.org(),0L,null);
        var digest=hash(h.seed.subject().toString());
        return new CommandHandler.Context(CommandScope.capture(h.seed.tenant(),account,digest),
                new Request(h.seed.request().actor(),org,h.seed.org(),requirement),new CommandAuthorizationBinding.Capture(account,digest,org));
    }
    Requirement captureGrant(Handler h) {return new Requirement("LEAD_CAPTURE","SOURCE_INTAKE_OWNER",Path.DIRECT,h.seed.grant());}
    AuthorizationSnapshot decision(CommandEnvelope e,CommandHandler.Context context)throws Exception {
        try(var c=database.apiConnection()) {return inTransaction(c,Capability.QUERY,x->new R1CommandPolicy(auth,readers).authorize(x,e,context,true));}
    }
    CommandHandler.Context request(CommandHandler.Context ctx,Actor actor,UUID organization,Requirement grant) {
        return new CommandHandler.Context(ctx.scope(),new Request(actor,ctx.authorization().subject(),organization,grant),ctx.binding());
    }
    void deny(Handler h,Subject subject,String code,UUID principal)throws Exception {
        mutate(h,"insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values (?,?,?,?,?,'DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),?,?,?)",
                h.seed.tenant(),UUID.randomUUID(),principal,h.seed.appointment(),code,subject.type(),subject.id(),subject.revision());
    }
    final String[][] draftRows={
        {"RESOLVE_LEAD_DUPLICATE","RESOLVE_DUPLICATE_LEAD","SOURCE_INTAKE_OWNER","LEAD_INGRESS_RESOLVE","ResolveDuplicateLeadV1","responsibility.decision_record"},
        {"COMPLETE_LEAD_INGRESS","COMPLETE_LEAD_INGRESS","SOURCE_INTAKE_OWNER","LEAD_INGRESS_COMPLETE","CompleteLeadIngressV1","lead.lead"},
        {"ASSIGN_LEAD","ASSIGN_LEAD","ROUTING_SUPERVISOR","LEAD_ASSIGN","AssignLeadV1","lead.lead_assignment"},
        {"RESOLVE_LEAD_ROUTING_GAP","RECORD_ROUTING_DISPOSITION","ROUTING_SUPERVISOR","LEAD_ROUTING_DECIDE","RecordRoutingDispositionV1","responsibility.decision_record"},
        {"ACK_SOURCE_INTAKE_STOP_REQUEST","ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST","SOURCE_INTAKE_OWNER","SOURCE_INTAKE_REQUEST_ACK","AcknowledgeSourceIntakeStopRequestV1","responsibility.decision_record"},
        {"CONTACT_LEAD","RECORD_CONTACT_RESULT","ASSIGNMENT_OWNER","SALES_CONTACT_OWNER","RecordContactResultV1","lead.lead_contact_result"},
        {"REVIEW_LEAD_VALIDITY","REVIEW_LEAD_VALIDITY","ROUTING_SUPERVISOR","LEAD_VALIDITY_REVIEW","ReviewLeadValidityV1","responsibility.decision_record"}};
    Handler task(String[] row)throws Exception {
        return new Handler(AuthorizationServiceIT.seed(database,"HUMAN",row[3]),row[0],row[1],row[5]);
    }
    UUID draft(Handler h,String[] row)throws Exception {
        UUID id=UUID.randomUUID();
        mutate(h,"insert into responsibility.action_draft (tenant_id,action_draft_id,task_occurrence_id,action_code,payload_schema_code,payload_schema_version,candidate_payload,candidate_payload_digest,state,created_by_appointment_id,created_at,last_edited_at) values (?,?,?,?,?,1,'{}',decode(repeat('00',32),'hex'),'DRAFT',?,clock_timestamp(),clock_timestamp())",
                h.seed.tenant(),id,h.task,row[1],row[4],h.seed.appointment());return id;
    }
    CommandHandler.Context draftContext(Handler h,String[] row,UUID draft) {
        var action=CommandEnvelope.Type.valueOf(row[1]);
        return new CommandHandler.Context(CommandScope.draft(h.seed.tenant(),h.task,action),
                new Request(h.seed.request().actor(),new Subject("responsibility.task_occurrence",h.task,0L,null),h.seed.org(),new Requirement(row[3],row[2],Path.DIRECT,h.seed.grant())),
                new CommandAuthorizationBinding.Draft(h.task,h.seed.request().subject(),0L,draft,draft==null?null:0L,action,1));
    }
    CommandEnvelope draftEnvelope(Handler h,String[] row,Actor actor) {return envelope(h,CommandEnvelope.Type.SAVE_ACTION_DRAFT,actor,Map.of("actionCode",row[1],"schemaVersion",1,"values",Map.of()));}
    record Service(Actor actor,UUID grant) {}
    Service service(Handler h,String code,UUID scope)throws Exception {
        return identity(h,code,scope,"SERVICE");
    }
    Service identity(Handler h,String code,UUID scope,String kind)throws Exception {
        UUID principal=UUID.randomUUID(),app=UUID.randomUUID(),grant=UUID.randomUUID();
        mutate(h,"insert into identity.principal (tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) values (?,?,?,?,decode(repeat('02',32),'hex'),'service','ACTIVE',clock_timestamp())",h.seed.tenant(),principal,kind,principal.toString());
        mutate(h,"insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'SERVICE',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",h.seed.tenant(),app,principal,h.seed.org());
        mutate(h,"insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,?,clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",h.seed.tenant(),grant,app,h.seed.appointment(),scope,code);
        return new Service(new Actor(h.seed.tenant(),principal,app,null,null),grant);
    }
    CommandHandler.Context recovery(Handler h,Service service,CommandEnvelope.Type type,String code) {
        UUID wait=UUID.randomUUID();String hash=hash("wait");
        return new CommandHandler.Context(CommandScope.reopen(h.seed.tenant(),type,h.task,wait,hash),
                new Request(service.actor(),new Subject("responsibility.task_occurrence",h.task,0L,null),h.seed.org(),new Requirement(code,"SYSTEM_RECOVERY",Path.SYSTEM,service.grant())),
                new CommandAuthorizationBinding.Recovery(h.task,h.seed.request().subject(),0L,wait,hash));
    }
    CommandEnvelope recoveryEnvelope(Handler h,CommandHandler.Context context) {
        var b=(CommandAuthorizationBinding.Recovery)context.binding();
        return envelope(h,context.scope().type(),context.authorization().actor(),Map.of("taskId",b.taskId().toString(),"waitReceiptId",b.waitReceiptId().toString(),"waitReceiptHash",b.waitReceiptHash(),"expectedTaskRevision",b.taskRevision(),"dueCutoff","2026-01-01T00:00:00Z"));
    }

    @Test void frozen_capture_handler_can_register() throws Exception {
        var captureHandler = new Handler() {
            @Override public CommandEnvelope.Type type() { return CommandEnvelope.Type.CAPTURE_LEAD; }
        };
        assertDoesNotThrow(() -> new CommandRuntime(List.of(captureHandler),
                AuthorizationService.databaseBacked(), "POLICY_IT"));
    }
    @Test void capture_uses_real_root_shared_sources_and_exact_existing_lead_deny()throws Exception {
        var h=captureHandler();
        for(String account:List.of("FIXTURE","SECOND")) {
            var context=capture(h,account,captureGrant(h));
            var result=decision(envelope(h,CommandEnvelope.Type.CAPTURE_LEAD,h.seed.request().actor(),Map.of("sourceAccountCode",account)),context);
            assertTrue(result.allowed(),result.evidence());assertEquals("identity.organization_unit",result.request().subject().type());
        }
        deny(h,h.seed.request().subject(),"LEAD_CAPTURE",h.seed.principal());
        var context=capture(h,"FIXTURE",captureGrant(h));
        assertFalse(decision(envelope(h,CommandEnvelope.Type.CAPTURE_LEAD,h.seed.request().actor(),Map.of("sourceAccountCode","FIXTURE")),context).allowed());
        assertEquals(List.of(0L,0L,0L,0L,0L),counts(h).subList(0,5));
    }
    @Test void capture_wrong_source_root_code_and_object_path_fail_closed()throws Exception {
        var h=captureHandler();var e=envelope(h,CommandEnvelope.Type.CAPTURE_LEAD,h.seed.request().actor(),Map.of("sourceAccountCode","FIXTURE"));
        var context=capture(h,"FIXTURE",captureGrant(h));
        readers=R1AuthorizationReaders.databaseBacked(sources("MISSING"));assertFalse(decision(e,context).allowed());
        readers=R1AuthorizationReaders.databaseBacked(sources("ROOT"));
        assertFalse(decision(e,capture(h,"UNKNOWN",captureGrant(h))).allowed());
        assertFalse(decision(e,capture(h,"FIXTURE",new Requirement("LEAD_INGRESS_COMPLETE","SOURCE_INTAKE_OWNER",Path.DIRECT,h.seed.grant()))).allowed());
        assertFalse(decision(e,capture(h,"FIXTURE",new Requirement("LEAD_CAPTURE","SOURCE_INTAKE_OWNER",Path.OBJECT,h.seed.grant()))).allowed());
        var other=new Handler();assertFalse(decision(e,request(context,e.actor(),other.seed.org(),captureGrant(h))).allowed());
    }
    @Test void capture_org_revision_and_revocation_are_reloaded_before_final_check()throws Exception {
        for(boolean revoke:List.of(false,true)) {
            var h=captureHandler();var context=capture(h,"FIXTURE",captureGrant(h));
            var e=envelope(h,CommandEnvelope.Type.CAPTURE_LEAD,h.seed.request().actor(),Map.of("sourceAccountCode","FIXTURE"));
            try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
                var policy=new R1CommandPolicy(auth,readers);assertTrue(policy.authorize(x,e,context,false).allowed());
                try(var w=database.apiConnection()){inTransaction(w,Capability.COMMAND,y->{auth.lockForMutation(y,h.seed.tenant());
                    if(revoke)sql(y,"update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='TEST',revision=revision+1 where tenant_id=? and authority_grant_id=?",h.seed.tenant(),h.seed.grant());
                    else sql(y,"update identity.organization_unit set display_name='changed',revision=revision+1 where tenant_id=? and organization_unit_id=?",h.seed.tenant(),h.seed.org());return null;});}
                var finalDecision=policy.authorize(x,e,context,true);assertFalse(finalDecision.allowed());
                assertEquals(0L,finalDecision.request().subject().revision());return null;
            });}
        }
    }
    @Test void all_seven_draft_policies_require_actual_owner_and_exact_task_action_schema()throws Exception {
        for(var row:draftRows) {
            var h=task(row);UUID draft=draft(h,row);var context=draftContext(h,row,draft);var e=draftEnvelope(h,row,h.seed.request().actor());
            assertTrue(decision(e,context).allowed(),row[0]);
            var other=identity(h,row[3],h.seed.org(),"HUMAN");
            // Even an active HUMAN with the exact same direct authority is not the Task Owner.
            var otherRequest=request(context,other.actor(),h.seed.org(),new Requirement(row[3],row[2],Path.DIRECT,other.grant()));
            assertFalse(decision(draftEnvelope(h,row,other.actor()),otherRequest).allowed());
            assertFalse(decision(envelope(h,e.type(),e.actor(),Map.of("actionCode",row[1],"schemaVersion",2)),context).allowed());
            assertFalse(decision(envelope(h,e.type(),e.actor(),Map.of("actionCode","SAVE_ACTION_DRAFT","schemaVersion",1)),context).allowed());
            var b=(CommandAuthorizationBinding.Draft)context.binding();
            var wrong=new CommandAuthorizationBinding.Draft(b.taskId(),b.lead(),b.taskRevision(),UUID.randomUUID(),0L,b.actionCode(),1);
            assertFalse(decision(e,new CommandHandler.Context(context.scope(),context.authorization(),wrong)).allowed());
            assertEquals(List.of(0L,0L,0L,0L,0L),counts(h).subList(0,5));
        }
    }
    @Test void draft_checks_task_and_lead_deny_and_keeps_original_selectors_after_own_write()throws Exception {
        for(boolean taskDeny:List.of(false,true)) {
            var row=draftRows[1];var h=task(row);UUID draft=draft(h,row);var context=draftContext(h,row,draft);var e=draftEnvelope(h,row,h.seed.request().actor());
            try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
                var policy=new R1CommandPolicy(auth,readers);assertTrue(policy.authorize(x,e,context,false).allowed());
                sql(x,"update responsibility.action_draft set candidate_payload='{\"sourceSummary\":\"changed\"}',candidate_payload_digest=?,last_edited_at=clock_timestamp(),revision=revision+1 where tenant_id=? and action_draft_id=?",CanonicalJson.digest("changed"),h.seed.tenant(),draft);
                assertTrue(policy.authorize(x,e,context,true).allowed());return null;
            });}
            deny(h,taskDeny?context.authorization().subject():h.seed.request().subject(),row[3],h.seed.principal());
            assertFalse(decision(e,context).allowed());
        }
    }
    @Test void draft_delegation_must_represent_actual_owner_and_stay_inside_source_scope()throws Exception {
        var row=draftRows[1];var h=task(row);var context=draftContext(h,row,draft(h,row));
        var delegate=identity(h,row[3],h.seed.org(),"HUMAN");
        UUID delegation=UUID.randomUUID();
        mutate(h,"insert into identity.delegation_grant (tenant_id,delegation_grant_id,source_authority_grant_id,delegator_appointment_id,delegate_appointment_id,scope_organization_unit_id,valid_from,state,created_at) values (?,?,?,?,?,?,clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",h.seed.tenant(),delegation,h.seed.grant(),h.seed.appointment(),delegate.actor().appointmentId(),h.seed.org());
        var actor=new Actor(h.seed.tenant(),delegate.actor().principalId(),delegate.actor().appointmentId(),h.seed.principal(),h.seed.appointment());
        var delegated=request(context,actor,h.seed.org(),new Requirement(row[3],row[2],Path.DELEGATED,delegation));
        assertTrue(decision(draftEnvelope(h,row,actor),delegated).allowed());
        UUID child=UUID.randomUUID();
        mutate(h,"insert into identity.organization_unit (tenant_id,organization_unit_id,unit_code,display_name,parent_organization_unit_id,state,created_at) values (?,?,'CHILD','child',?,'ACTIVE',clock_timestamp())",h.seed.tenant(),child,h.seed.org());
        UUID narrow=UUID.randomUUID();
        mutate(h,"insert into identity.delegation_grant (tenant_id,delegation_grant_id,source_authority_grant_id,delegator_appointment_id,delegate_appointment_id,scope_organization_unit_id,valid_from,state,created_at) values (?,?,?,?,?,?,clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",h.seed.tenant(),narrow,h.seed.grant(),h.seed.appointment(),delegate.actor().appointmentId(),child);
        assertFalse(decision(draftEnvelope(h,row,actor),request(context,actor,h.seed.org(),new Requirement(row[3],row[2],Path.DELEGATED,narrow))).allowed());
    }
    @Test void both_recovery_policies_require_real_service_grant_actual_owner_scope_and_liveness()throws Exception {
        for(int index=0;index<2;index++) {
            var type=index==0?CommandEnvelope.Type.REOPEN_DUE_CONTACT_TASKS:CommandEnvelope.Type.REOPEN_DUE_ROUTING_REVIEW_TASKS;
            String code=index==0?"CONTACT_TASK_RECOVER":"ROUTING_REVIEW_TASK_RECOVER";
            var h=task(draftRows[index==0?5:3]);var service=service(h,code,h.seed.org());var context=recovery(h,service,type,code);
            var e=recoveryEnvelope(h,context);assertTrue(decision(e,context).allowed());
            var wrong=request(context,service.actor(),h.seed.org(),new Requirement("CONTACT_TASK_RECOVER".equals(code)?"ROUTING_REVIEW_TASK_RECOVER":"CONTACT_TASK_RECOVER","SYSTEM_RECOVERY",Path.SYSTEM,service.grant()));
            assertFalse(decision(e,wrong).allowed());
            var human=request(context,h.seed.request().actor(),h.seed.org(),new Requirement(code,"SYSTEM_RECOVERY",Path.SYSTEM,h.seed.grant()));
            assertFalse(decision(recoveryEnvelope(h,human),human).allowed());
            mutate(h,"update identity.appointment set state='SUSPENDED',revision=revision+1 where tenant_id=? and appointment_id=?",h.seed.tenant(),h.seed.appointment());
            var result=decision(e,context);assertFalse(result.allowed());assertEquals("NOT_AUTHORIZED",result.rejectionCode());
            assertEquals(List.of(0L,0L,0L,0L,0L),counts(h).subList(0,5));
        }
    }
    @Test void recovery_current_authorization_survives_own_cas_but_observes_original_and_current_denies()throws Exception {
        var h=task(draftRows[5]);var service=service(h,"CONTACT_TASK_RECOVER",h.seed.org());var context=recovery(h,service,CommandEnvelope.Type.REOPEN_DUE_CONTACT_TASKS,"CONTACT_TASK_RECOVER");
        var e=recoveryEnvelope(h,context);
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            var policy=new R1CommandPolicy(auth,readers);assertTrue(policy.authorize(x,e,context,false).allowed());
            sql(x,"update responsibility.task_occurrence set state='WAITING',revision=revision+1 where tenant_id=? and task_occurrence_id=?",h.seed.tenant(),h.task);
            sql(x,"update responsibility.task_occurrence set state='OPEN',revision=revision+1 where tenant_id=? and task_occurrence_id=?",h.seed.tenant(),h.task);
            var result=policy.authorize(x,e,context,true);assertTrue(result.allowed());assertEquals(0L,result.request().subject().revision());
            assertTrue(result.evidence().contains("revision=2"));return null;
        });}
        deny(h,new Subject("responsibility.task_occurrence",h.task,2L,null),"CONTACT_TASK_RECOVER",service.actor().principalId());
        assertFalse(decision(e,context).allowed());
    }
    @Test void recovery_maps_service_identity_inactivity_to_internal_not_authorized()throws Exception {
        var h=task(draftRows[5]);var service=service(h,"CONTACT_TASK_RECOVER",h.seed.org());var context=recovery(h,service,CommandEnvelope.Type.REOPEN_DUE_CONTACT_TASKS,"CONTACT_TASK_RECOVER");
        mutate(h,"update identity.appointment set state='SUSPENDED',revision=revision+1 where tenant_id=? and appointment_id=?",h.seed.tenant(),service.actor().appointmentId());
        var result=decision(recoveryEnvelope(h,context),context);
        assertFalse(result.allowed());assertEquals("NOT_AUTHORIZED",result.rejectionCode());
    }
    @Test void source_registry_rejects_duplicate_roots_invalid_codes_and_fixed_offsets() {
        assertThrows(IllegalArgumentException.class,()->new R1SourcePolicyRegistry.SourcePolicy(R1SourcePolicyRegistry.AssignmentMode.MANUAL,List.of("ROOT","ROOT"),"ROOT","ROOT","Asia/Shanghai"));
        assertThrows(IllegalArgumentException.class,()->new R1SourcePolicyRegistry.SourcePolicy(R1SourcePolicyRegistry.AssignmentMode.MANUAL,List.of("ROOT"),"ROOT","ROOT","+08:00"));
        assertThrows(IllegalArgumentException.class,()->new R1SourcePolicyRegistry.SourcePolicy(R1SourcePolicyRegistry.AssignmentMode.MANUAL,List.of("ROOT"),"bad code","ROOT","Asia/Shanghai"));
    }
    class NoChangeHandler implements CommandHandler {
        final Handler fixture;final Context context;final Subject result;int eligibilityCalls;
        NoChangeHandler(Handler fixture,Context context,Subject result) {this.fixture=fixture;this.context=context;this.result=result;}
        public CommandEnvelope.Type type(){return context.scope().type();}
        public Context resolve(Connection c,CommandEnvelope e){return context;}
        public void lockRoots(Connection c,CommandEnvelope e,Context context)throws SQLException {fixture.lockRoots(c,e,context);}
        public void recoveryEligibility(Connection c,CommandEnvelope e,Context context)throws SQLException {
            eligibilityCalls++;fixture.recoveryEligibility(c,e,context);
        }
        public void validateBeforeWork(Connection c,CommandEnvelope e,Context context) {}
        public Result execute(Connection c,CommandEnvelope e,Context context) {return Result.noChange(result);}
        public void validateBeforeCommit(Connection c,CommandEnvelope e,Context context,Result result) {}
    }
    @Test void real_capture_no_change_replay_and_conflict_reauthorize_without_extra_audit()throws Exception {
        var h=captureHandler();var context=capture(h,"FIXTURE",captureGrant(h));var handler=new NoChangeHandler(h,context,h.seed.request().subject());
        var runtime=new CommandRuntime(List.of(handler),auth,"POLICY_IT",readers);
        var e=envelope(h,handler.type(),h.seed.request().actor(),Map.of("sourceAccountCode","FIXTURE"));
        var first=run(runtime,e);assertEquals(CommandOutcome.Status.NO_CHANGE,first.status());assertEquals(first,run(runtime,e));
        assertInstanceOf(CommandResult.Conflict.class,runResult(runtime,new CommandEnvelope(e.type(),e.commandId(),e.correlationId(),e.actor(),Map.of("sourceAccountCode","FIXTURE","legalNeedSummary","changed"))));
        assertEquals(List.of(1L,1L,1L,0L,0L),counts(h).subList(0,5));
        deny(h,h.seed.request().subject(),"LEAD_CAPTURE",h.seed.principal());
        assertThrows(CommandHandler.Rejected.class,()->run(runtime,e));assertEquals(List.of(1L,1L,1L,0L,0L),counts(h).subList(0,5));
    }
    @Test void real_policy_non_owner_draft_wrong_recovery_grant_and_missing_readers_have_zero_effects()throws Exception {
        var row=draftRows[1];var h=task(row);var context=draftContext(h,row,draft(h,row));var other=identity(h,row[3],h.seed.org(),"HUMAN");
        var denied=request(context,other.actor(),h.seed.org(),new Requirement(row[3],row[2],Path.DIRECT,other.grant()));
        var handler=new NoChangeHandler(h,denied,new Subject("responsibility.action_draft",((CommandAuthorizationBinding.Draft)context.binding()).draftId(),0L,null));
        assertThrows(CommandHandler.Rejected.class,()->run(new CommandRuntime(List.of(handler),auth,"POLICY_IT",readers),draftEnvelope(h,row,other.actor())));
        var recoveryService=service(h,"ROUTING_REVIEW_TASK_RECOVER",h.seed.org());
        var wrong=recovery(h,recoveryService,CommandEnvelope.Type.REOPEN_DUE_CONTACT_TASKS,"CONTACT_TASK_RECOVER");
        var recoveryHandler=new NoChangeHandler(h,wrong,wrong.authorization().subject());
        assertThrows(CommandHandler.Rejected.class,()->run(new CommandRuntime(List.of(recoveryHandler),auth,"POLICY_IT",readers),recoveryEnvelope(h,wrong)));
        var ownerHandler=new NoChangeHandler(h,context,handler.result);
        assertThrows(CommandHandler.Rejected.class,()->run(new CommandRuntime(List.of(ownerHandler),auth,"POLICY_IT"),draftEnvelope(h,row,h.seed.request().actor())));
        assertEquals(List.of(0L,0L,0L,0L,0L),counts(h).subList(0,5));
    }
    CommandHandler.Context recoveryContext(RecoveryHandler h,Service service) {
        return new CommandHandler.Context(CommandScope.reopen(h.seed.tenant(),h.type(),h.task,h.wait,h.waitHash),
                new Request(service.actor(),new Subject("responsibility.task_occurrence",h.task,1L,null),h.seed.org(),new Requirement("CONTACT_TASK_RECOVER","SYSTEM_RECOVERY",Path.SYSTEM,service.grant())),
                new CommandAuthorizationBinding.Recovery(h.task,h.seed.request().subject(),1L,h.wait,h.waitHash));
    }
    @Test void recovery_runtime_replays_before_new_key_eligibility_and_keeps_validation_failures_pre_slot()throws Exception {
        for(int scenario=0;scenario<4;scenario++) {
            var h=new RecoveryHandler(scenario==0,scenario==1,false,scenario==3?"R1_ROUTING_REVIEW_WAIT_V1":"CONTACT_RETRY_V1");if(scenario==2)h.expected=0;
            var service=service(h,"CONTACT_TASK_RECOVER",h.seed.org());var context=recoveryContext(h,service);
            var handler=new NoChangeHandler(h,context,new Subject("responsibility.task_occurrence",h.task,1L,null));
            var error=assertThrows(CommandHandler.Rejected.class,()->run(new CommandRuntime(List.of(handler),auth,"POLICY_IT",readers),recoveryEnvelope(h,context)));
            assertEquals("VALIDATION_FAILED",error.code());assertEquals(List.of(0L,0L,0L,0L,0L),counts(h).subList(0,5));
        }
        var h=new RecoveryHandler(false,false,true);var service=service(h,"CONTACT_TASK_RECOVER",h.seed.org());var context=recoveryContext(h,service);
        var handler=new NoChangeHandler(h,context,new Subject("responsibility.task_occurrence",h.task,2L,null));
        var runtime=new CommandRuntime(List.of(handler),auth,"POLICY_IT",readers);var e=recoveryEnvelope(h,context);
        var first=run(runtime,e);h.expected=999;
        assertEquals(first,run(runtime,e));assertEquals(1,handler.eligibilityCalls);
        assertEquals(List.of(1L,1L,1L,0L,0L),counts(h).subList(0,5));
    }
    @Test void final_lock_wait_observes_new_deny_and_revoked_service_grant()throws Exception {
        for(boolean revoke:List.of(false,true)) {
            var h=task(draftRows[5]);var service=service(h,"CONTACT_TASK_RECOVER",h.seed.org());
            var context=recovery(h,service,CommandEnvelope.Type.REOPEN_DUE_CONTACT_TASKS,"CONTACT_TASK_RECOVER");var e=recoveryEnvelope(h,context);
            try(var pool=java.util.concurrent.Executors.newSingleThreadExecutor();var writer=database.apiConnection()) {
                var initial=new java.util.concurrent.CountDownLatch(1);var proceed=new java.util.concurrent.CountDownLatch(1);
                var pending=pool.submit(()->{try(var c=database.apiConnection()){return inTransaction(c,Capability.QUERY,x->{
                    var policy=new R1CommandPolicy(auth,readers);assertTrue(policy.authorize(x,e,context,false).allowed());initial.countDown();
                    try {if(!proceed.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new SQLException("test synchronization timeout");}catch(InterruptedException interrupted){throw new SQLException(interrupted);}
                    return policy.authorize(x,e,context,true);
                });}});
                assertTrue(initial.await(10,java.util.concurrent.TimeUnit.SECONDS));
                inTransaction(writer,Capability.COMMAND,w->{
                    auth.lockForMutation(w,h.seed.tenant());proceed.countDown();
                    // Completion must wait for this transaction's exclusive identity lock.
                    if(revoke)sql(w,"update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='TEST',revision=revision+1 where tenant_id=? and authority_grant_id=?",h.seed.tenant(),service.grant());
                    else sql(w,"insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values (?,?,?,?,'CONTACT_TASK_RECOVER','DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),'lead.lead',?,0)",h.seed.tenant(),UUID.randomUUID(),service.actor().principalId(),h.seed.appointment(),h.seed.subject());
                    assertThrows(java.util.concurrent.TimeoutException.class,()->pending.get(100,java.util.concurrent.TimeUnit.MILLISECONDS));return null;
                });
                var result=pending.get(10,java.util.concurrent.TimeUnit.SECONDS);assertFalse(result.allowed());assertEquals("NOT_AUTHORIZED",result.rejectionCode());
            }
        }
    }
    @Test void capture_allows_only_matching_human_delegation_and_rejects_service_and_wrong_root()throws Exception {
        var h=captureHandler();var context=capture(h,"FIXTURE",captureGrant(h));
        var delegate=identity(h,"LEAD_CAPTURE",h.seed.org(),"HUMAN");UUID delegation=UUID.randomUUID();
        mutate(h,"insert into identity.delegation_grant (tenant_id,delegation_grant_id,source_authority_grant_id,delegator_appointment_id,delegate_appointment_id,scope_organization_unit_id,valid_from,state,created_at) values (?,?,?,?,?,?,clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",h.seed.tenant(),delegation,h.seed.grant(),h.seed.appointment(),delegate.actor().appointmentId(),h.seed.org());
        var actor=new Actor(h.seed.tenant(),delegate.actor().principalId(),delegate.actor().appointmentId(),h.seed.principal(),h.seed.appointment());
        var delegated=request(context,actor,h.seed.org(),new Requirement("LEAD_CAPTURE","SOURCE_INTAKE_OWNER",Path.DELEGATED,delegation));
        assertTrue(decision(envelope(h,CommandEnvelope.Type.CAPTURE_LEAD,actor,Map.of("sourceAccountCode","FIXTURE")),delegated).allowed());
        var service=service(h,"LEAD_CAPTURE",h.seed.org());
        for(var path:List.of(Path.DIRECT,Path.SYSTEM)) {
            var serviceContext=request(context,service.actor(),h.seed.org(),new Requirement("LEAD_CAPTURE","SOURCE_INTAKE_OWNER",path,service.grant()));
            assertFalse(decision(envelope(h,CommandEnvelope.Type.CAPTURE_LEAD,service.actor(),Map.of("sourceAccountCode","FIXTURE")),serviceContext).allowed());
        }
        UUID other=UUID.randomUUID();
        mutate(h,"insert into identity.organization_unit (tenant_id,organization_unit_id,unit_code,display_name,state,created_at) values (?,?,'OTHER','other','ACTIVE',clock_timestamp())",h.seed.tenant(),other);
        readers=R1AuthorizationReaders.databaseBacked(sources("OTHER"));
        var b=(CommandAuthorizationBinding.Capture)context.binding();var org=new Subject("identity.organization_unit",other,0L,null);
        var otherContext=new CommandHandler.Context(context.scope(),new Request(h.seed.request().actor(),org,other,captureGrant(h)),new CommandAuthorizationBinding.Capture(b.sourceAccountCode(),b.sourceRecordKeyDigest(),org));
        assertFalse(decision(envelope(h,CommandEnvelope.Type.CAPTURE_LEAD,h.seed.request().actor(),Map.of("sourceAccountCode","FIXTURE")),otherContext).allowed());
        readers=R1AuthorizationReaders.databaseBacked(sources("ROOT"));
    }
    @Test void draft_persisted_schema_and_task_lead_bindings_cannot_be_supplied_by_a_handler()throws Exception {
        for(int scenario=0;scenario<3;scenario++) {
            var row=draftRows[1];var h=task(row);var invalid=row.clone();if(scenario==0)invalid[4]="WrongSchemaV1";
            var context=draftContext(h,row,draft(h,invalid));
            if(scenario!=0) {
                var b=(CommandAuthorizationBinding.Draft)context.binding();
                var wrong=new CommandAuthorizationBinding.Draft(scenario==1?UUID.randomUUID():b.taskId(),scenario==2?new Subject("lead.lead",UUID.randomUUID(),0L,null):b.lead(),b.taskRevision(),b.draftId(),b.draftRevision(),b.actionCode(),b.schemaVersion());
                context=new CommandHandler.Context(context.scope(),context.authorization(),wrong);
            }
            assertFalse(decision(draftEnvelope(h,row,h.seed.request().actor()),context).allowed());
        }
    }
    @Test void denial_evidence_survives_a_later_real_allow_after_deny_revocation()throws Exception {
        var h=captureHandler();var context=capture(h,"FIXTURE",captureGrant(h));var e=envelope(h,CommandEnvelope.Type.CAPTURE_LEAD,h.seed.request().actor(),Map.of("sourceAccountCode","FIXTURE"));
        deny(h,h.seed.request().subject(),"LEAD_CAPTURE",h.seed.principal());var denied=decision(e,context);assertFalse(denied.allowed());
        mutate(h,"update identity.object_access_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='TEST',revision=revision+1 where tenant_id=?",h.seed.tenant());
        var allowed=decision(e,context);assertTrue(allowed.allowed());var retained=R1CommandPolicy.retainDenial(denied,allowed);
        assertFalse(retained.allowed());assertEquals(denied.rejectionCode(),retained.rejectionCode());
        assertTrue(retained.evidence().contains(denied.evidence()));assertTrue(retained.evidence().contains(allowed.evidence()));
    }
    @Test void capture_final_revocation_after_slot_rolls_back_to_rejected_receipt_and_audit()throws Exception {
        var h=captureHandler();var context=capture(h,"FIXTURE",captureGrant(h));
        var handler=new NoChangeHandler(h,context,h.seed.request().subject()) {
            @Override public void validateBeforeCommit(Connection c,CommandEnvelope e,Context context,Result result) {
                try {mutate(h,"update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='TEST',revision=revision+1 where tenant_id=? and authority_grant_id=?",h.seed.tenant(),h.seed.grant());}
                catch(Exception failure){throw new AssertionError(failure);}
            }
        };
        var result=run(new CommandRuntime(List.of(handler),auth,"POLICY_IT",readers),envelope(h,CommandEnvelope.Type.CAPTURE_LEAD,h.seed.request().actor(),Map.of("sourceAccountCode","FIXTURE")));
        assertEquals(CommandOutcome.Status.REJECTED,result.status());assertEquals("NOT_AUTHORIZED",result.rejectionCode());
        assertEquals(List.of(1L,1L,1L,0L,0L),counts(h).subList(0,5));
    }
}
