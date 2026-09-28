package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import io.github.windyzhu3.ontologylaw.execution.CommandHandler;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import io.github.windyzhu3.ontologylaw.opportunity.QuoteFollowupReader;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Validates quote-owned causal facts before reopening the same Responsibility-owned task. */
public final class QuoteFollowupRecovery {
    private QuoteFollowupRecovery() {}
    public static QuoteFollowupReader.Source source(Connection c,UUID tenant,UUID taskId)throws SQLException {
        var tasks=TaskFactory.databaseBacked();var task=tasks.read(c,tenant,taskId);
        if(task==null||task.type()!=TaskFactory.Type.RECORD_QUOTE_REPLY)throw new CommandHandler.Rejected("STALE_TASK");
        var maintenance=OpportunityMaintenanceTasks.databaseBacked();var causal=maintenance.waitProgress(c,tenant,taskId);
        var origin=task;var seen=new HashSet<UUID>();
        while(true){
            if(!seen.add(origin.selector().id())||seen.size()>65)throw new CommandHandler.Rejected("STALE_TASK");
            var predecessor=maintenance.handoffPredecessor(c,tenant,origin.selector().id());
            if(predecessor==null)break;origin=predecessor;
        }
        var source=QuoteFollowupReader.databaseBacked().read(c,tenant,origin.selector().id());
        var prior=source==null||source.priorTaskId()==null?null:tasks.read(c,tenant,source.priorTaskId());
        if(source==null||!causal.equals(source.response())||!source.opportunityId().equals(task.subject().id())
            ||!source.owner().equals(origin.owner())||prior==null||prior.type()!=TaskFactory.Type.RECORD_QUOTE_REPLY
            ||!"DONE".equals(prior.state())||!source.response().equals(prior.completion())||!prior.subject().equals(task.subject())||!prior.owner().equals(origin.owner()))
            throw new CommandHandler.Rejected("STALE_TASK");
        if(!origin.selector().id().equals(taskId)){
            var detail=CurrentTaskReader.databaseBacked().read(c,tenant,taskId);
            source=new QuoteFollowupReader.Source(source.response(),source.opportunityId(),task.owner(),source.priorTaskId(),detail.responsibilityBasis(),source.due());
        }
        return source;
    }
    public static Subject reopen(Connection c,UUID tenant,Subject expected,Subject opportunity,UUID owner,Subject expectedWait,Subject response,Instant due)throws SQLException {
        if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw new SQLException("Quote recovery requires transaction","25001");
        var tasks=TaskFactory.databaseBacked();tasks.lock(c,tenant,expected.id());
        var task=tasks.read(c,tenant,expected.id());var wait=EventResponsibilityReader.databaseBacked().latestWait(c,tenant,expected.id());
        var source=source(c,tenant,expected.id());
        if(task==null||!task.selector().equals(expected)||!"WAITING".equals(task.state())||!task.subject().equals(opportunity)||!task.owner().equals(owner)
            ||wait==null||!wait.selector().equals(expectedWait)||wait.taskRevision()!=expected.revision()||wait.version()!=1
            ||!Set.of("R2_QUOTE_FOLLOWUP_V1","R2_QUOTE_HANDOFF_WAIT_V1").contains(wait.profile())||!due.equals(wait.resumeDue())||due.isAfter(tasks.now(c))
            ||!source.response().equals(response)||!source.due().equals(due))throw new CommandHandler.Rejected("STALE_TASK");
        return tasks.reopen(c,tenant,task).selector();
    }
}
