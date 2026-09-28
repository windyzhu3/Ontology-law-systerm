package io.github.windyzhu3.ontologylaw.api;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.lead.ContactFlowFixture;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.time.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class R2OpportunityDiscoveryIT extends ContactFlowFixture {
    private final OpportunityProgressProtection cipher=OpportunityProgressProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES"));
    private final R2OpportunityDiscoveryService discovery=new R2OpportunityDiscoveryService(new byte[32]);
    private Subject opportunity;
    private void opening()throws Exception {
        setupContact();var contact=execute(prepare(contact("CONNECTED_VALID")));
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            opportunity=EventOpportunityReader.databaseBacked().forContact(x,seed.tenant(),contact.resultFact().id()).selector();grant(x,"SALES_OPPORTUNITY_OWNER");return null;
        });}
    }
    private R2OpportunityDiscoveryService.Response list(Actor actor,R2OpportunityDiscoveryService.Kind kind,int limit,String cursor)throws Exception {
        try(var c=database.apiConnection()){return discovery.list(c,actor,kind,limit,cursor);}
    }
    private OpportunityProgressService.Result followup(Instant due)throws Exception {
        try(var c=database.apiConnection()){return inTransaction(c,Capability.COMMAND,x->{
            current=TaskFactory.databaseBacked().createInitialOpportunity(x,seed.tenant(),seed.appointment(),opportunity,ZoneId.of("Asia/Shanghai"),businessAt);
            return R2OpportunityProgressServices.create(cipher).record(x,seed.request().actor(),opportunity,current.selector(),new OpportunityProgressInput("MEETING","确认后续安排",businessAt,due),ZoneId.of("Asia/Shanghai"),businessAt.plusSeconds(60));
        });}
    }
    @Test void initial_discovery_is_read_only_and_old_projection_permission_cannot_activate()throws Exception {
        opening();var kind=R2OpportunityDiscoveryService.Kind.INITIAL;
        assertEquals(403,list(service("R1_PROJECTION_CONSUME"),kind,50,null).status());
        assertEquals(403,list(service("OPPORTUNITY_TASK_RECOVER"),kind,50,null).status());
        assertEquals(403,list(seed.request().actor(),kind,50,null).status());
        var actor=service("OPPORTUNITY_TASK_ACTIVATE");var before=counts();var result=list(actor,kind,50,null);
        assertEquals(200,result.status(),result.errorCode());assertEquals(1,result.page().candidates().size());
        var candidate=result.page().candidates().getFirst();assertEquals(opportunity,candidate.opportunity());assertNull(candidate.task());assertEquals(5,candidate.commandId().version());
        assertEquals(result.page(),list(actor,kind,50,null).page());assertEquals(before,counts());
    }
    @Test void existing_initial_task_in_any_state_is_not_resurrected()throws Exception {
        opening();var actor=service("OPPORTUNITY_TASK_ACTIVATE");
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{current=TaskFactory.databaseBacked().createInitialOpportunity(x,seed.tenant(),seed.appointment(),opportunity,ZoneId.of("Asia/Shanghai"),businessAt);return null;});}
        assertTrue(list(actor,R2OpportunityDiscoveryService.Kind.INITIAL,50,null).page().candidates().isEmpty());
        cancelCurrent();assertTrue(list(actor,R2OpportunityDiscoveryService.Kind.INITIAL,50,null).page().candidates().isEmpty());
    }
    @Test void due_discovery_returns_exact_wait_and_progress_and_wakeup_removes_candidate()throws Exception {
        opening();var due=businessAt.plusSeconds(86400);var progress=followup(due);
        var actor=service("OPPORTUNITY_TASK_RECOVER");var before=counts();var result=list(actor,R2OpportunityDiscoveryService.Kind.DUE,50,null);
        assertEquals(200,result.status(),result.errorCode());assertEquals(1,result.page().candidates().size());var candidate=result.page().candidates().getFirst();
        assertEquals(progress.nextTask(),candidate.task());assertEquals(progress.progress(),candidate.progress());assertEquals(due,candidate.due());assertEquals(before,counts());
        var command=R2OpportunityCommandRuntime.recovery(actor,candidate,UUID.randomUUID());assertEquals(candidate.commandId(),command.commandId());
        var runtime=R2OpportunityCommandRuntime.fromSourcePolicy(policies,protection,cipher,null,"R2_DISCOVERY_COMMAND_IT");
        io.github.windyzhu3.ontologylaw.execution.CommandResult receipt;
        try(var c=database.apiConnection()){receipt=runtime.execute(c,command);assertEquals(io.github.windyzhu3.ontologylaw.execution.CommandOutcome.Status.SUCCEEDED,assertInstanceOf(io.github.windyzhu3.ontologylaw.execution.CommandOutcome.class,receipt).status());}
        var after=counts();try(var c=database.apiConnection()){assertEquals(receipt,runtime.execute(c,command));}assertEquals(after,counts());
        assertTrue(list(actor,R2OpportunityDiscoveryService.Kind.DUE,50,null).page().candidates().isEmpty());
    }
    @Test void denied_row_advances_cursor_and_cursor_cannot_cross_actor_or_kind()throws Exception {
        opening();var actor=service("OPPORTUNITY_TASK_ACTIVATE");deny(opportunity,"OPPORTUNITY_TASK_ACTIVATE");
        // DENY belongs to the human fixture principal; add the same denial for the service.
        mutate("insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values (?,?,?,?,'OPPORTUNITY_TASK_ACTIVATE','DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),'opportunity.opportunity',?,?)",seed.tenant(),UUID.randomUUID(),actor.principalId(),seed.appointment(),opportunity.id(),opportunity.revision());
        var first=list(actor,R2OpportunityDiscoveryService.Kind.INITIAL,1,null);assertEquals(200,first.status());assertTrue(first.page().candidates().isEmpty());assertNotNull(first.page().nextCursor());
        assertTrue(list(actor,R2OpportunityDiscoveryService.Kind.INITIAL,1,first.page().nextCursor()).page().candidates().isEmpty());
        assertEquals(400,list(actor,R2OpportunityDiscoveryService.Kind.INITIAL,1,first.page().nextCursor()+"x").status());
        assertEquals(400,list(service("OPPORTUNITY_TASK_ACTIVATE"),R2OpportunityDiscoveryService.Kind.INITIAL,1,first.page().nextCursor()).status());
        assertEquals(400,list(actor,R2OpportunityDiscoveryService.Kind.DUE,1,first.page().nextCursor()).status());
    }
    @Test void owner_revocation_and_expired_service_grant_exclude_work()throws Exception {
        opening();assertEquals(403,list(service("OPPORTUNITY_TASK_ACTIVATE",true),R2OpportunityDiscoveryService.Kind.INITIAL,50,null).status());
        var actor=service("OPPORTUNITY_TASK_ACTIVATE");
        mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and grantee_appointment_id=? and authority_code='SALES_OPPORTUNITY_OWNER'",seed.tenant(),seed.appointment());
        assertTrue(list(actor,R2OpportunityDiscoveryService.Kind.INITIAL,50,null).page().candidates().isEmpty());
    }
    @Test void future_followups_are_not_discovered()throws Exception {
        opening();followup(Instant.now().plusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertTrue(list(service("OPPORTUNITY_TASK_RECOVER"),R2OpportunityDiscoveryService.Kind.DUE,50,null).page().candidates().isEmpty());
    }
    @Test void closed_opportunity_cannot_supply_due_work()throws Exception {
        opening();followup(businessAt.plusSeconds(86400));var actor=service("OPPORTUNITY_TASK_RECOVER");
        assertEquals(1,list(actor,R2OpportunityDiscoveryService.Kind.DUE,50,null).page().candidates().size());
        mutate("update opportunity.opportunity set closed_at=clock_timestamp(),close_outcome_code='FIXTURE_CLOSED',revision=revision+1 where tenant_id=? and opportunity_id=?",seed.tenant(),opportunity.id());
        assertTrue(list(actor,R2OpportunityDiscoveryService.Kind.DUE,50,null).page().candidates().isEmpty());
    }
    @Test void object_allow_does_not_replace_selected_service_direct_grant()throws Exception {
        opening();var actor=service("R1_PROJECTION_CONSUME");
        mutate("insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values (?,?,?,?,'OPPORTUNITY_TASK_ACTIVATE','ALLOW',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),'opportunity.opportunity',?,?)",seed.tenant(),UUID.randomUUID(),actor.principalId(),seed.appointment(),opportunity.id(),opportunity.revision());
        assertEquals(403,list(actor,R2OpportunityDiscoveryService.Kind.INITIAL,50,null).status());
    }
    @Test void validly_signed_expired_cursor_and_unbounded_request_are_rejected()throws Exception {
        opening();var actor=service("OPPORTUNITY_TASK_ACTIVATE");var kind=R2OpportunityDiscoveryService.Kind.INITIAL;
        var cursor=list(actor,kind,1,null).page().nextCursor();var parts=cursor.split("\\.");
        var fields=new String(Base64.getUrlDecoder().decode(parts[0]),java.nio.charset.StandardCharsets.UTF_8).split("\n");fields[5]=Instant.now().minusSeconds(600).toString();
        byte[] body=String.join("\n",fields).getBytes(java.nio.charset.StandardCharsets.UTF_8);var signer=javax.crypto.Mac.getInstance("HmacSHA256");signer.init(new SecretKeySpec(new byte[32],"HmacSHA256"));
        var expired=Base64.getUrlEncoder().withoutPadding().encodeToString(body)+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(signer.doFinal(body));
        assertEquals(400,list(actor,kind,1,expired).status());assertEquals(400,list(actor,kind,101,null).status());assertEquals(401,list(null,kind,50,null).status());
    }
}
