package io.github.windyzhu3.ontologylaw.api;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;

class R2QuotePreparationIntentIT extends R2QuoteWorkflowIT {
    @Test void preparation_intent_command_replays_without_duplicate_tasks()throws Exception {
        setup(true,true);confirmed();
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"QUOTE_PREPARE");grant(x,"QUOTE_READ");return null;});}
        var envelope=new io.github.windyzhu3.ontologylaw.execution.CommandEnvelope(io.github.windyzhu3.ontologylaw.execution.CommandEnvelope.Type.START_QUOTE_PREPARATION,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),quotePayload(Map.of()));
        var result=execute(envelope);assertEquals(io.github.windyzhu3.ontologylaw.execution.CommandOutcome.Status.SUCCEEDED,result.status(),result.rejectionCode());
        assertEquals(result,execute(envelope));
        assertEquals("1",scalar("select count(*) from opportunity.quote_preparation_intent where tenant_id=?",seed.tenant()));
        assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='PREPARE_QUOTE'",seed.tenant()));
        assertFalse(((List<?>)context().get("allowedActions")).contains("START_QUOTE_PREPARATION"));
        assertThrows(Exception.class,()->quoteCommand("START_QUOTE_PREPARATION",Map.of()));
    }
    @Test void preparation_intent_rejects_revoked_authority_without_handoff()throws Exception {
        setup(true,true);confirmed();
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"QUOTE_PREPARE");grant(x,"QUOTE_READ");return null;});}
        mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_code='QUOTE_PREPARE'",seed.tenant());
        assertThrows(Exception.class,()->quoteCommand("START_QUOTE_PREPARATION",Map.of()));
        assertEquals("0",scalar("select count(*) from opportunity.quote_preparation_intent where tenant_id=?",seed.tenant()));
        assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and state='WAITING'",seed.tenant(),opportunity.id()));
    }
    @Test void preparation_intent_takes_over_only_after_explicit_command()throws Exception {
        setup(true,true);confirmed();policy("SELF_AUTHORIZED",List.of(seed.appointment()));
        String due=scalar("select original_sla_due_at::text from responsibility.task_occurrence where tenant_id=? and subject_id=? and state='WAITING'",seed.tenant(),opportunity.id());
        context();quoteCommand("SAVE_QUOTE_DRAFT",Map.of("scope","尚在准备的范围"));
        assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and state='WAITING'",seed.tenant(),opportunity.id()));
        var intent=assertDoesNotThrow(()->quoteCommand("START_QUOTE_PREPARATION",Map.of()));
        assertEquals("opportunity.quote_preparation_intent",intent.type());
        assertEquals("0",scalar("select count(*) from opportunity.quote_revision where tenant_id=?",seed.tenant()));
        assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and business_purpose_code='PROGRESS_OPPORTUNITY' and state in ('OPEN','WAITING')",seed.tenant(),opportunity.id()));
        assertEquals(due,scalar("select original_sla_due_at::text from responsibility.task_occurrence where tenant_id=? and subject_id=? and cancellation_reason_code='QUOTE_WORKFLOW_TAKEOVER'",seed.tenant(),opportunity.id()));
        assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and business_purpose_code='PREPARE_QUOTE' and state='OPEN'",seed.tenant(),opportunity.id()));
        assertEquals("PREPARE",((Map<?,?>)context().get("workflow")).get("stage"));
        assertFalse(((List<?>)context().get("allowedActions")).contains("START_QUOTE_PREPARATION"));
    }
    @Test void preparation_intent_is_durable_and_form_reuses_its_task()throws Exception {
        setup(true,true);confirmed();policy("SELF_AUTHORIZED",List.of(seed.appointment()));
        assertDoesNotThrow(()->quoteCommand("START_QUOTE_PREPARATION",Map.of()));
        String prepared=scalar("select task_occurrence_id::text from responsibility.task_occurrence where tenant_id=? and subject_id=? and business_purpose_code='PREPARE_QUOTE' and state='OPEN'",seed.tenant(),opportunity.id());
        var quote=quoteCommand("FORM_QUOTE",commercial());
        assertEquals("opportunity.quote_revision",quote.type());
        assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and business_purpose_code='PREPARE_QUOTE'",seed.tenant(),opportunity.id()));
        assertEquals("DONE",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),UUID.fromString(prepared)));
    }
}
