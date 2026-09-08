package io.github.windyzhu3.ontologylaw.api;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.api.adapter.generated.model.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.lead.ContactFlowFixture;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.util.*;
import java.time.*;
import org.junit.jupiter.api.Test;

class DueR1TaskDiscoveryIT extends ContactFlowFixture {
    @Test void object_allow_and_delegation_rows_never_replace_exact_service_direct_recovery_grant()throws Exception{
        setupFlow(TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP);waiting();var selected=service("CONTACT_TASK_RECOVER");var delegator=service("ROUTING_REVIEW_TASK_RECOVER");
        mutate("insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values (?,?,?,?,'ROUTING_REVIEW_TASK_RECOVER','ALLOW',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),'responsibility.task_occurrence',?,1)",seed.tenant(),UUID.randomUUID(),selected.principalId(),seed.appointment(),current.selector().id());
        var grant=UUID.fromString(scalar("select authority_grant_id::text from identity.authority_grant where tenant_id=? and grantee_appointment_id=?",seed.tenant(),delegator.appointmentId()));
        mutate("insert into identity.delegation_grant (tenant_id,delegation_grant_id,source_authority_grant_id,delegator_appointment_id,delegate_appointment_id,scope_organization_unit_id,valid_from,state,created_at) values (?,?,?,?,?,?,clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),grant,delegator.appointmentId(),selected.appointmentId(),seed.org());
        var before=counts();assertEquals(403,list(selected,RecoveryTypeV1.ROUTING_REVIEW_TASK,50,null).status());assertEquals(before,counts());assertEquals(1,list(delegator,RecoveryTypeV1.ROUTING_REVIEW_TASK,50,null).page().getCandidates().size());
    }
    @Test void denied_first_row_advances_cursor_to_later_valid_row_and_current_owner_inactivity_excludes_due_work()throws Exception{
        setupFlow(TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP);waiting();var actor=service("ROUTING_REVIEW_TASK_RECOVER");UUID first=UUID.fromString("01900000-0000-7000-8000-000000000002"),second=UUID.fromString("01900000-0000-7000-8000-000000000003");addTask(first,businessAt.minusSeconds(3600),businessAt,"WAITING");addTask(second,businessAt.minusSeconds(3600),businessAt,"WAITING");
        mutate("insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values (?,?,?,?,'ROUTING_REVIEW_TASK_RECOVER','DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),'responsibility.task_occurrence',?,1)",seed.tenant(),UUID.randomUUID(),actor.principalId(),seed.appointment(),first);
        var before=counts();var denied=list(actor,RecoveryTypeV1.ROUTING_REVIEW_TASK,1,null);assertEquals(200,denied.status());assertTrue(denied.page().getCandidates().isEmpty());assertNotNull(denied.page().getNextCursor());var next=list(actor,RecoveryTypeV1.ROUTING_REVIEW_TASK,1,denied.page().getNextCursor());assertEquals(second,next.page().getCandidates().getFirst().getTaskId());assertEquals(before,counts());
        mutate("update identity.appointment set state='SUSPENDED',revision=revision+1 where tenant_id=? and appointment_id=?",seed.tenant(),seed.appointment());assertTrue(list(actor,RecoveryTypeV1.ROUTING_REVIEW_TASK,100,null).page().getCandidates().isEmpty());
    }
    @Test void same_principal_other_appointment_and_on_behalf_cannot_widen_selected_service()throws Exception{
        setupFlow(TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP);waiting();var selected=service("CONTACT_TASK_RECOVER");var other=UUID.randomUUID();
        mutate("insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'RECOVERY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),other,selected.principalId(),seed.org());
        mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,'ROUTING_REVIEW_TASK_RECOVER',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),other,seed.appointment(),seed.org());
        assertEquals(403,list(selected,RecoveryTypeV1.ROUTING_REVIEW_TASK,100,null).status());assertThrows(IllegalArgumentException.class,()->new Actor(selected.tenantId(),selected.principalId(),selected.appointmentId(),selected.principalId(),other,PrincipalKind.SERVICE));
    }
    @Test void correctly_signed_expired_cursor_is_rejected_and_a_new_first_page_is_available()throws Exception{
        setupFlow(TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP);waiting();var actor=service("ROUTING_REVIEW_TASK_RECOVER");String cursor=list(actor,RecoveryTypeV1.ROUTING_REVIEW_TASK,1,null).page().getNextCursor();var value=new String(Base64.getUrlDecoder().decode(cursor.split("\\.")[0]),java.nio.charset.StandardCharsets.UTF_8).split("\n");value[5]=Instant.now().minusSeconds(301).toString();byte[] bytes=String.join("\n",value).getBytes(java.nio.charset.StandardCharsets.UTF_8);var signer=javax.crypto.Mac.getInstance("HmacSHA256");signer.init(new javax.crypto.spec.SecretKeySpec(new byte[32],"HmacSHA256"));String expired=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(signer.doFinal(bytes));assertEquals(400,list(actor,RecoveryTypeV1.ROUTING_REVIEW_TASK,1,expired).status());assertEquals(200,list(actor,RecoveryTypeV1.ROUTING_REVIEW_TASK,1,null).status());
    }
    final DueR1TaskDiscoveryService discovery=new DueR1TaskDiscoveryService(new byte[32]);
    DueR1TaskDiscoveryService.Response list(Actor actor,RecoveryTypeV1 type,Integer limit,String cursor)throws Exception {try(var c=database.apiConnection()){return discovery.list(c,actor,type,limit,cursor);}}
    void waiting()throws Exception {try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{TaskFactory.databaseBacked().waitUntil(x,seed.tenant(),current,seed.appointment(),businessAt.plusSeconds(3600),businessAt);return null;});}}
    @Test void both_types_return_exact_wait_selector_and_repeated_discovery_replays_real_recovery()throws Exception {
        for(var type:List.of(RecoveryTypeV1.CONTACT_TASK,RecoveryTypeV1.ROUTING_REVIEW_TASK)) {
            setupFlow(type==RecoveryTypeV1.CONTACT_TASK?TaskFactory.Type.CONTACT_LEAD:TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP);waiting();
            var actor=service(type==RecoveryTypeV1.CONTACT_TASK?"CONTACT_TASK_RECOVER":"ROUTING_REVIEW_TASK_RECOVER");var before=counts();
            var result=list(actor,type,null,null);assertEquals(200,result.status());assertEquals(1,result.page().getCandidates().size());
            var candidate=result.page().getCandidates().getFirst();assertEquals(current.selector().id(),candidate.getTaskId());assertEquals(1L,candidate.getExpectedTaskRevision());assertEquals(businessAt.plusSeconds(3600),candidate.getDueCutoff().toInstant());
            assertEquals(result.page(),list(actor,type,null,null).page());assertEquals(before,counts());
            var commandType=type==RecoveryTypeV1.CONTACT_TASK?CommandEnvelope.Type.REOPEN_DUE_CONTACT_TASKS:CommandEnvelope.Type.REOPEN_DUE_ROUTING_REVIEW_TASKS;
            var command=new CommandEnvelope(commandType,candidate.getIdempotencyKey(),UUID.randomUUID(),actor,Map.of("taskId",candidate.getTaskId().toString(),"expectedTaskRevision",candidate.getExpectedTaskRevision(),"waitReceiptId",candidate.getWaitReceiptId().toString(),"waitReceiptHash",candidate.getWaitReceiptHash(),"dueCutoff",candidate.getDueCutoff().toInstant().toString()));
            var first=execute(command);assertEquals(CommandOutcome.Status.SUCCEEDED,first.status());assertEquals(first,execute(command));
            assertTrue(list(actor,type,null,null).page().getCandidates().isEmpty());
        }
    }
    @Test void current_appointment_and_recovery_authority_do_not_borrow_other_grants()throws Exception {
        setupFlow(TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP);waiting();var good=service("ROUTING_REVIEW_TASK_RECOVER");
        var found=list(good,RecoveryTypeV1.ROUTING_REVIEW_TASK,50,null);assertEquals(200,found.status());assertEquals(1,found.page().getCandidates().size());
        assertEquals(403,list(service("CONTACT_TASK_RECOVER"),RecoveryTypeV1.ROUTING_REVIEW_TASK,50,null).status());
        assertEquals(403,list(seed.request().actor(),RecoveryTypeV1.ROUTING_REVIEW_TASK,50,null).status());
        assertEquals(403,list(service("ROUTING_REVIEW_TASK_RECOVER",true),RecoveryTypeV1.ROUTING_REVIEW_TASK,50,null).status());
    }
    @Test void equal_time_keyset_pages_are_stable_and_cursor_is_bound_to_actor_and_type()throws Exception {
        setupFlow(TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP);waiting();var actor=service("ROUTING_REVIEW_TASK_RECOVER");
        UUID second=UUID.fromString("01900000-0000-7000-8000-000000000002"),third=UUID.fromString("01900000-0000-7000-8000-000000000003");
        addTask(second,businessAt.minusSeconds(3600),businessAt,"WAITING");addTask(third,businessAt.minusSeconds(3600),businessAt,"WAITING");
        var first=list(actor,RecoveryTypeV1.ROUTING_REVIEW_TASK,1,null);assertEquals(200,first.status());assertEquals(second,first.page().getCandidates().getFirst().getTaskId());assertNotNull(first.page().getNextCursor());
        var cursor=first.page().getNextCursor();var next=list(actor,RecoveryTypeV1.ROUTING_REVIEW_TASK,1,cursor);assertEquals(third,next.page().getCandidates().getFirst().getTaskId());
        assertEquals(next.page(),list(actor,RecoveryTypeV1.ROUTING_REVIEW_TASK,1,cursor).page());
        var last=list(actor,RecoveryTypeV1.ROUTING_REVIEW_TASK,1,next.page().getNextCursor());assertEquals(current.selector().id(),last.page().getCandidates().getFirst().getTaskId());var exhausted=list(actor,RecoveryTypeV1.ROUTING_REVIEW_TASK,1,last.page().getNextCursor());assertTrue(exhausted.page().getCandidates().isEmpty());assertNull(exhausted.page().getNextCursor());
        assertEquals(400,list(actor,RecoveryTypeV1.ROUTING_REVIEW_TASK,1,cursor+"x").status());
        assertEquals(400,list(service("ROUTING_REVIEW_TASK_RECOVER"),RecoveryTypeV1.ROUTING_REVIEW_TASK,1,cursor).status());
        assertEquals(400,list(actor,RecoveryTypeV1.CONTACT_TASK,1,cursor).status());
        assertEquals(400,list(actor,RecoveryTypeV1.ROUTING_REVIEW_TASK,101,null).status());
    }
    @Test void uuid5_vectors_use_standard_url_namespace_exact_name_and_distinct_command_type() {
        var tenant=UUID.fromString("01900000-0000-7000-8000-000000000001");var task=UUID.fromString("01900000-0000-7000-8000-000000000002");var wait=UUID.fromString("01900000-0000-7000-8000-000000000003");
        assertEquals(UUID.fromString("ab0fdcb0-3144-5360-80e2-a5b7b7bdb1b1"),DueR1TaskDiscoveryService.recoveryKey(tenant,RecoveryTypeV1.CONTACT_TASK,task,wait,"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));
        assertEquals(UUID.fromString("cf4491a1-f6b5-583d-bb50-64ba57a01db5"),DueR1TaskDiscoveryService.recoveryKey(tenant,RecoveryTypeV1.ROUTING_REVIEW_TASK,task,wait,"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));
    }
}
