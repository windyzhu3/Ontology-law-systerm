package io.github.windyzhu3.ontologylaw.lead;

import io.github.windyzhu3.ontologylaw.api.CommandReceiptRecoveryService;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandReceiptTaskRecoveryIT extends ContactFlowFixture {
    private CommandReceiptRecoveryService.Response read(CommandEnvelope command)throws Exception {
        try(var c=database.apiConnection()){return new CommandReceiptRecoveryService(policies,protection,null,"TASK_RECEIPT_IT").read(c,command.actor(),command.commandId(),UUID.randomUUID());}
    }
    @Test void done_task_and_confirmed_draft_do_not_reapply_new_command_eligibility()throws Exception {
        setupContact();var command=prepare(contact("CONNECTED_VALID"));var outcome=execute(command);assertEquals(CommandOutcome.Status.SUCCEEDED,outcome.status());
        var before=counts();var response=read(command);assertEquals(200,response.status());assertEquals(outcome.receiptId().toString(),response.body().get("receiptId"));
        delta(before,0,0,0,0,0,0,0,0,1,0,0);
    }
    @Test void rejected_stale_contact_assignment_attempt_still_reads_under_current_task_owner_authorization()throws Exception {
        setupContact();var values=contact("CONNECTED_VALID");values.put("leadAssignmentRevision",99L);var command=prepare(values);
        assertEquals(CommandOutcome.Status.REJECTED,execute(command).status());var before=counts();var response=read(command);
        assertEquals(200,response.status());assertEquals("REJECTED",response.body().get("outcome"));delta(before,0,0,0,0,0,0,0,0,1,0,0);
    }
    @Test void old_draft_write_receipt_remains_readable_after_confirmation_and_task_completion()throws Exception {
        setupContact();var handlers=new ArrayList<CommandHandler>(new LeadCommands(policies,protection).handlers());handlers.addAll(new ActionDraftCommands(protection).handlers());
        runtime=new CommandRuntime(handlers,io.github.windyzhu3.ontologylaw.identity.AuthorizationService.databaseBacked(),io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked("DRAFT_RECEIPT_IT"),R1AuthorizationReaders.databaseBacked(policies),R1EventReaders.databaseBacked());
        var draftCommand=new CommandEnvelope(CommandEnvelope.Type.SAVE_ACTION_DRAFT,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),Map.of("actionCode","RECORD_CONTACT_RESULT","schemaVersion",1,"values",contact("CONNECTED_VALID")),null,new CommandEnvelope.DraftPrecondition(current.selector().id(),null,"*"));
        assertEquals(CommandOutcome.Status.SUCCEEDED,execute(draftCommand).status());
        ActionDraftService.Draft draft;try(var c=database.apiConnection()){draft=io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.inTransaction(c,io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.Capability.QUERY,x->ActionDraftService.databaseBacked().read(x,seed.tenant(),current.selector().id()));}
        var payload=new TreeMap<String,Object>(draft.values());payload.put("draftId",draft.selector().id().toString());payload.put("expectedDraftRevision",draft.selector().revision());payload.put("draftDigest",draft.digest());
        var command=new CommandEnvelope(CommandEnvelope.Type.RECORD_CONTACT_RESULT,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),payload,new CommandEnvelope.TaskPrecondition(current.selector().id(),R1ResourceTags.task(seed.request().actor(),current.selector(),current.state())));
        assertEquals(CommandOutcome.Status.SUCCEEDED,execute(command).status());assertEquals(200,read(draftCommand).status());
    }
}
