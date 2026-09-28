package io.github.windyzhu3.ontologylaw.api;

import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class QuoteDueRecoveryIT extends R2QuoteWorkflowIT {
    @Test void due_quote_reply_is_discovered_and_reopened_once_with_original_sla() throws Exception {
        var evidence=delivered();
        var due=now().plusSeconds(3);
        quoteCommand("RECORD_QUOTE_RESPONSE",Map.of("kind","NOT_ACCEPTED","statement","稍后再联系",
            "occurredAt",now().toString(),"nextCheckAt",due.toString(),"evidence",R2CustomerRequirementsServices.selector(evidence)));
        var actor=service("OPPORTUNITY_TASK_RECOVER");
        var discovery=new R2OpportunityDiscoveryService(new byte[32]);
        try(var c=database.apiConnection()) {
            assertTrue(discovery.list(c,actor,R2OpportunityDiscoveryService.Kind.DUE,50,null).page().candidates().isEmpty());
        }
        long millis=Duration.between(Instant.now(),due.plusMillis(20)).toMillis();
        if(millis>0)Thread.sleep(millis);
        R2OpportunityDiscoveryService.Candidate candidate;
        try(var c=database.apiConnection()) {
            var response=discovery.list(c,actor,R2OpportunityDiscoveryService.Kind.DUE,50,null);
            assertEquals(200,response.status());assertEquals(1,response.page().candidates().size());
            candidate=response.page().candidates().getFirst();
        }
        assertEquals("opportunity.quote_response",candidate.progress().type());
        String sla=scalar("select original_sla_due_at::text from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),candidate.task().id());
        var command=R2OpportunityCommandRuntime.recovery(actor,candidate,UUID.randomUUID());
        var runtime=R2OpportunityCommandRuntime.fromSourcePolicy(policies,protection,cipher,null,"QUOTE_DUE_IT");
        CommandResult first;
        try(var c=database.apiConnection()){first=runtime.execute(c,command);}
        assertEquals(CommandOutcome.Status.SUCCEEDED,assertInstanceOf(CommandOutcome.class,first).status());
        try(var c=database.apiConnection()){assertEquals(first,runtime.execute(c,command));}
        assertEquals("OPEN",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),candidate.task().id()));
        assertEquals(sla,scalar("select original_sla_due_at::text from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),candidate.task().id()));
        assertEquals(Long.toString(candidate.task().revision()+1),scalar("select revision from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),candidate.task().id()));
    }
}
