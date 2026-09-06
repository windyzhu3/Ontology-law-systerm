package io.github.windyzhu3.ontologylaw.lead;

import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.responsibility.TaskFactory;
import io.github.windyzhu3.ontologylaw.execution.CommandOutcome;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import io.github.windyzhu3.ontologylaw.execution.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import java.util.*;
import java.sql.SQLException;

class LeadValidityReviewIT extends ContactFlowFixture {
    @Test void persisted_event_policy_rejects_a_noncausal_trigger_even_after_a_real_valid_review()throws Exception{
        setupContact();var source=execute(prepare(contact("SUSPECT_INVALID")));selectTask(TaskFactory.Type.REVIEW_LEAD_VALIDITY);var command=prepare(review(source,"CONFIRM_INVALID"));
        var handler=new ContactCommands(policies,protection,()->businessAt).handlers().stream().filter(h->h.type()==command.type()).findFirst().orElseThrow();var policy=new R1EventPolicy(R1EventReaders.databaseBacked());CommandHandler.Context context;
        try(var c=database.apiConnection()){context=inTransaction(c,Capability.QUERY,x->{var resolved=handler.resolve(x,command);policy.beforeWork(x,command,resolved);return resolved;});}
        var receipt=execute(command);var result=CommandHandler.Result.succeeded(receipt.resultFact(),CommandHandler.Event.LeadValidityReviewedV1);var changed=new TreeMap<String,Object>((Map<String,Object>)command.payload());changed.put("triggeringContactResultId",UUID.randomUUID().toString());
        var forged=new CommandEnvelope(command.type(),command.commandId(),command.correlationId(),command.actor(),changed,command.taskPrecondition());
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{policy.validate(x,command,context,result);assertThrows(SQLException.class,()->policy.validate(x,forged,context,result));return null;});}
    }
    @ParameterizedTest @ValueSource(strings={"CONFIRM_INVALID","CLOSE_UNREACHED","REOPEN_CONTACT"})
    void supervisor_decision_completes_exact_causal_review_and_only_reopen_creates_new_contact(String decision)throws Exception {
        setupContact();var source=execute(prepare(contact("SUSPECT_INVALID")));assertEquals(CommandOutcome.Status.SUCCEEDED,source.status());
        var original=selectTask(TaskFactory.Type.REVIEW_LEAD_VALIDITY);businessAt=businessAt.plusSeconds(10);
        var command=prepare(review(source,decision));var before=counts();var receipt=execute(command);
        assertEquals(CommandOutcome.Status.SUCCEEDED,receipt.status());assertEquals("responsibility.decision_record",receipt.resultFact().type());
        delta(before,0,0,1,0,decision.equals("REOPEN_CONTACT")?1:0,0,1,1,1,1,1);
        assertEquals("DONE",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),original.selector().id()));
        if(decision.equals("REOPEN_CONTACT")){var next=selectTask(TaskFactory.Type.CONTACT_LEAD);assertEquals("OPEN",next.state());assertEquals(0L,next.selector().revision());assertNotEquals(original.selector().id(),next.selector().id());}
        var after=counts();assertEquals(receipt,execute(command));assertEquals(after,counts());
    }
}
