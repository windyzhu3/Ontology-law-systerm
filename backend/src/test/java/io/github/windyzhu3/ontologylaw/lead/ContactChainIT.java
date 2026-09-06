package io.github.windyzhu3.ontologylaw.lead;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ContactChainIT extends ContactFlowFixture {
    @ParameterizedTest @ValueSource(strings={"CONNECTED_VALID","NOT_CONNECTED","SUSPECT_INVALID"})
    void supervisor_explicitly_allows_fourth_and_fifth_without_resetting_global_ordinal(String fourth)throws Exception{
        setupContact();CommandOutcome third=null;
        for(int n=1;n<=3;n++){
            third=execute(prepare(contact("NOT_CONNECTED")));assertEquals(CommandOutcome.Status.SUCCEEDED,third.status());
            assertEquals(Integer.toString(n),scalar("select contact_no::text from lead.lead_contact_result where tenant_id=? and lead_contact_result_id=?",seed.tenant(),third.resultFact().id()));
            if(n<3)recoverContact();
        }
        selectTask(TaskFactory.Type.REVIEW_LEAD_VALIDITY);businessAt=businessAt.plusSeconds(1);
        assertEquals(CommandOutcome.Status.SUCCEEDED,execute(prepare(review(third,"REOPEN_CONTACT"))).status());
        selectTask(TaskFactory.Type.CONTACT_LEAD);assertEquals(0L,current.selector().revision());assertEquals("OPEN",current.state());businessAt=businessAt.plusSeconds(1);
        var command=prepare(contact(fourth));var before=counts();var fourthResult=execute(command);assertEquals(CommandOutcome.Status.SUCCEEDED,fourthResult.status());
        boolean connected=fourth.equals("CONNECTED_VALID");delta(before,1,connected?1:0,0,0,connected?0:1,0,1,1,1,connected?2:1,connected?2:1);
        assertEquals("4",scalar("select contact_no::text from lead.lead_contact_result where tenant_id=? and lead_contact_result_id=?",seed.tenant(),fourthResult.resultFact().id()));
        var after=counts();assertEquals(fourthResult,execute(command));assertEquals(after,counts());
        if(!connected){
            selectTask(TaskFactory.Type.REVIEW_LEAD_VALIDITY);businessAt=businessAt.plusSeconds(1);
            var stale=execute(prepare(review(third,"REOPEN_CONTACT")));assertEquals(CommandOutcome.Status.REJECTED,stale.status());assertEquals("STALE_SUBJECT",stale.rejectionCode());
            // A corrected revision of the same Draft is required; the rejected candidate is not silently rewritten.
            try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{var drafts=ActionDraftService.databaseBacked();drafts.save(x,seed.tenant(),current,drafts.read(x,seed.tenant(),current.selector().id()),CurrentLeadReader.validatedDraftValues(current.type().command,review(fourthResult,"REOPEN_CONTACT")),seed.appointment(),businessAt);return null;});}
            var corrected=existingDraftCommand();assertEquals(CommandOutcome.Status.SUCCEEDED,execute(corrected).status());
            selectTask(TaskFactory.Type.CONTACT_LEAD);businessAt=businessAt.plusSeconds(1);
            var fifth=execute(prepare(contact("NOT_CONNECTED")));assertEquals(CommandOutcome.Status.SUCCEEDED,fifth.status());assertEquals("5",scalar("select contact_no::text from lead.lead_contact_result where tenant_id=? and lead_contact_result_id=?",seed.tenant(),fifth.resultFact().id()));
            selectTask(TaskFactory.Type.REVIEW_LEAD_VALIDITY);assertEquals("OPEN",current.state());
            assertEquals("2",scalar("select count(*)::text from responsibility.wait_receipt where tenant_id=?",seed.tenant()));
        }
    }
    private CommandEnvelope existingDraftCommand()throws Exception{
        try(var c=database.apiConnection()){return inTransaction(c,Capability.QUERY,x->{var draft=ActionDraftService.databaseBacked().read(x,seed.tenant(),current.selector().id());var p=new TreeMap<String,Object>(draft.values());p.put("draftId",draft.selector().id().toString());p.put("expectedDraftRevision",draft.selector().revision());p.put("draftDigest",draft.digest());return new CommandEnvelope(CommandEnvelope.Type.REVIEW_LEAD_VALIDITY,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),p,new CommandEnvelope.TaskPrecondition(current.selector().id(),R1ResourceTags.task(seed.request().actor(),current.selector(),current.state())));});}
    }
    @Test void early_suspect_reopen_second_not_connected_retains_second_global_retry_window()throws Exception{
        setupContact();var first=execute(prepare(contact("SUSPECT_INVALID")));selectTask(TaskFactory.Type.REVIEW_LEAD_VALIDITY);businessAt=businessAt.plusSeconds(1);
        execute(prepare(review(first,"REOPEN_CONTACT")));selectTask(TaskFactory.Type.CONTACT_LEAD);businessAt=businessAt.plusSeconds(1);
        var second=execute(prepare(contact("NOT_CONNECTED")));assertEquals(CommandOutcome.Status.SUCCEEDED,second.status());selectTask(TaskFactory.Type.CONTACT_LEAD);
        assertEquals("2026-08-04 15:00:00",scalar("select to_char(resume_due_at at time zone 'Asia/Shanghai','YYYY-MM-DD HH24:MI:SS') from responsibility.wait_receipt where tenant_id=? and task_occurrence_id=?",seed.tenant(),current.selector().id()));
        assertEquals("2026-08-04 15:30:00",scalar("select to_char(original_sla_due_at at time zone 'Asia/Shanghai','YYYY-MM-DD HH24:MI:SS') from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),current.selector().id()));
        assertEquals("WAITING",current.state());assertEquals(1L,current.selector().revision());
    }
}
