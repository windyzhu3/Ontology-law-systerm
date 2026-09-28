package io.github.windyzhu3.ontologylaw.api;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import io.github.windyzhu3.ontologylaw.execution.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Sales termination preserves exact quote history and ends every unfinished related responsibility. */
class R2QuoteTerminationIT extends R2QuoteWorkflowIT {
    private void permission() throws Exception {try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"OPPORTUNITY_CLOSE");return null;});}}
    Map<String,Object> reason(){return Map.of("reasonCode","CLIENT_DECLINED","summary","客户不再继续本次委托，保留原报价与回复记录。");}
    void stage(String stage)throws Exception {
        if(Set.of("AWAIT_REPLY","FOLLOW_UP","CLARIFY_REPLY","SALES_DISPOSITION","ACCEPTED").contains(stage)){
            var evidence=delivered();
            if(stage.equals("ACCEPTED"))try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"CONTRACT_PREPARE");return null;});}
            if(!stage.equals("AWAIT_REPLY")){String kind=switch(stage){case "FOLLOW_UP"->"NOT_ACCEPTED";case "CLARIFY_REPLY"->"AMBIGUOUS";case "SALES_DISPOSITION"->"REJECTED";default->"ACCEPTED";};var v=new LinkedHashMap<String,Object>();v.put("kind",kind);v.put("statement","客户真实回复");v.put("occurredAt",now().toString());v.put("evidence",R2CustomerRequirementsServices.selector(evidence));if(!kind.equals("ACCEPTED"))v.put("nextCheckAt",now().plusSeconds(3600).toString());quoteCommand("RECORD_QUOTE_RESPONSE",v);}
        }else{
            setup(true,true);confirmed();policy(stage.equals("DELIVER")?"SELF_AUTHORIZED":"REQUIRE_APPROVAL",List.of(seed.appointment()));
            if(stage.equals("PREPARE"))quoteCommand("START_QUOTE_PREPARATION",Map.of());
            else {quoteCommand("FORM_QUOTE",commercial());if(Set.of("AWAIT_APPROVAL","RETURNED").contains(stage))quoteCommand("REQUEST_QUOTE_APPROVAL",Map.of());if(stage.equals("RETURNED"))quoteCommand("RECORD_QUOTE_DECISION",Map.of("decision","RETURNED","reason","需调整"));}
        }
        permission();assertEquals(stage,((Map<?,?>)context().get("workflow")).get("stage"));
    }
    @ParameterizedTest @ValueSource(strings={"PREPARE","SUBMIT_APPROVAL","AWAIT_APPROVAL","RETURNED","DELIVER","AWAIT_REPLY","FOLLOW_UP","CLARIFY_REPLY","SALES_DISPOSITION","ACCEPTED"})
    void termination_closes_each_quote_stage_without_rewriting_history(String stage)throws Exception {
        stage(stage);String quotes=scalar("select count(*) from opportunity.quote_revision where tenant_id=?",seed.tenant());String replies=scalar("select count(*) from opportunity.quote_response where tenant_id=?",seed.tenant());String done=scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and state='DONE'",seed.tenant(),opportunity.id());
        var stale=quotePayload(reason());var fact=quoteCommand("END_QUOTE_NEGOTIATION",reason());assertEquals("opportunity.quote_termination",fact.type());
        assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and state in ('OPEN','WAITING')",seed.tenant(),opportunity.id()));
        assertEquals("1",scalar("select count(*) from opportunity.closure where tenant_id=? and opportunity_id=?",seed.tenant(),opportunity.id()));
        assertEquals(quotes,scalar("select count(*) from opportunity.quote_revision where tenant_id=?",seed.tenant()));assertEquals(replies,scalar("select count(*) from opportunity.quote_response where tenant_id=?",seed.tenant()));assertEquals(done,scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and state='DONE'",seed.tenant(),opportunity.id()));
        assertEquals(true,context().get("readonly"));assertEquals(List.of(),context().get("allowedActions"));
        try(var c=database.apiConnection()){assertThrows(QuoteWorkflowService.Blocked.class,()->inTransaction(c,Capability.COMMAND,x->quotes().execute(x,"END_QUOTE_NEGOTIATION",seed.request().actor(),stale)));}
    }
    @Test void termination_requires_current_owner_close_authority()throws Exception {
        stage("AWAIT_APPROVAL");mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_code='OPPORTUNITY_CLOSE'",seed.tenant());
        var error=assertThrows(QuoteWorkflowService.Blocked.class,()->quoteCommand("END_QUOTE_NEGOTIATION",reason()));assertEquals("NOT_AUTHORIZED",error.code());assertEquals("0",scalar("select count(*) from opportunity.closure where tenant_id=?",seed.tenant()));assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='APPROVE_QUOTE' and state='OPEN'",seed.tenant()));
    }
    @Test void termination_and_all_cancellations_roll_back_on_later_failure()throws Exception {
        stage("AWAIT_REPLY");var payload=quotePayload(reason());try(var c=database.apiConnection()){assertThrows(IllegalStateException.class,()->inTransaction(c,Capability.COMMAND,x->{quotes().execute(x,"END_QUOTE_NEGOTIATION",seed.request().actor(),payload);throw new IllegalStateException("audit failed");}));}
        assertEquals("0",scalar("select count(*) from opportunity.closure where tenant_id=?",seed.tenant()));assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and state='OPEN'",seed.tenant(),opportunity.id()));
    }
    @Test void termination_runtime_is_exactly_once_and_receipt_revocation_is_current()throws Exception {
        stage("AWAIT_APPROVAL");var command=new CommandEnvelope(CommandEnvelope.Type.END_QUOTE_NEGOTIATION,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),quotePayload(reason()));var result=execute(command);assertEquals(CommandOutcome.Status.SUCCEEDED,result.status(),result.rejectionCode());assertEquals(result,execute(command));assertEquals("1",scalar("select count(*) from opportunity.quote_termination where tenant_id=?",seed.tenant()));
        mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_code='OPPORTUNITY_CLOSE'",seed.tenant());
        try(var c=database.apiConnection()){assertEquals("NOT_AUTHORIZED",assertThrows(CommandHandler.Rejected.class,()->runtime.execute(c,command)).code());assertEquals(403,new CommandReceiptRecoveryService(policies,protection,null,"TERMINATION_REVOKED_IT").read(c,command.actor(),command.commandId(),UUID.randomUUID()).status());}
    }
    @Test void termination_cancels_all_parallel_approvers_and_preserves_completed_history()throws Exception {
        setup(true,true);confirmed();UUID principal=UUID.randomUUID(),appointment=UUID.randomUUID();mutate("insert into identity.principal(tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) values(?,?,'HUMAN','FIXTURE',?,'审批人乙','ACTIVE',clock_timestamp())",seed.tenant(),principal,CanonicalJson.digest(principal.toString()));mutate("insert into identity.appointment(tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values(?,?,?,?,'APPROVER',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),appointment,principal,seed.org());mutate("insert into identity.authority_grant(tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values(?,?,?,?,?,'QUOTE_APPROVE',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),appointment,seed.appointment(),seed.org());policy("REQUIRE_APPROVAL",List.of(seed.appointment(),appointment));permission();quoteCommand("FORM_QUOTE",commercial());quoteCommand("REQUEST_QUOTE_APPROVAL",Map.of());
        assertEquals("2",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='APPROVE_QUOTE' and state='OPEN'",seed.tenant()));
        var fact=quoteCommand("END_QUOTE_NEGOTIATION",reason());assertEquals("2",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='APPROVE_QUOTE' and state='CANCELLED' and cancellation_fact_id=?",seed.tenant(),fact.id()));assertEquals("0",scalar("select count(*) from opportunity.quote_approval_decision where tenant_id=?",seed.tenant()));
    }
    @Test void termination_database_rejects_an_orphan_cancel_fact()throws Exception {
        stage("AWAIT_REPLY");UUID task=UUID.fromString(scalar("select task_occurrence_id from responsibility.task_occurrence where tenant_id=? and subject_id=? and state='OPEN'",seed.tenant(),opportunity.id()));
        try(var c=database.apiConnection()){var error=assertThrows(java.sql.SQLException.class,()->inTransaction(c,Capability.COMMAND,x->{io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql(x,"update responsibility.task_occurrence set state='CANCELLED',revision=revision+1,cancelled_at=clock_timestamp(),cancellation_reason_code='R2_QUOTE_TERMINATION_V1',cancellation_fact_type='opportunity.quote_termination',cancellation_fact_id=?,cancellation_fact_revision=0 where tenant_id=? and task_occurrence_id=?",UUID.randomUUID(),seed.tenant(),task);return null;}));assertEquals("23514",error.getSQLState());}
        assertEquals("OPEN",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),task));
    }
    @Test void termination_task_deny_blocks_whole_transaction()throws Exception {
        stage("AWAIT_APPROVAL");UUID task=UUID.fromString(scalar("select task_occurrence_id from responsibility.task_occurrence where tenant_id=? and subject_id=? and business_purpose_code='APPROVE_QUOTE' and state='OPEN'",seed.tenant(),opportunity.id()));
        mutate("insert into identity.object_access_grant(tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values(?,?,?,?, 'OPPORTUNITY_CLOSE','DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),'responsibility.task_occurrence',?,0)",seed.tenant(),UUID.randomUUID(),seed.request().actor().principalId(),seed.appointment(),task);
        var error=assertThrows(QuoteWorkflowService.Blocked.class,()->quoteCommand("END_QUOTE_NEGOTIATION",reason()));assertEquals("NOT_AUTHORIZED",error.code());assertEquals("0",scalar("select count(*) from opportunity.closure where tenant_id=?",seed.tenant()));
    }
}
