package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import io.github.windyzhu3.ontologylaw.execution.CommandHandler;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Separates a truthful attempt from an effective progress or a customer reply. */
public final class FollowupAttemptRecovery {
    private FollowupAttemptRecovery(){}
    public static Subject source(Connection c,UUID tenant,UUID taskId)throws SQLException {
        var tasks=TaskFactory.databaseBacked();var maintenance=OpportunityMaintenanceTasks.databaseBacked();
        var fact=maintenance.waitProgress(c,tenant,taskId);
        if(!FollowupAttemptService.FACT.equals(fact.type()))return "opportunity.quote_response".equals(fact.type())?QuoteFollowupRecovery.source(c,tenant,taskId).response():fact;
        var task=tasks.read(c,tenant,taskId);var origin=task;var seen=new HashSet<UUID>();
        while(true){if(origin==null||!seen.add(origin.selector().id())||seen.size()>65)throw stale();var prior=maintenance.handoffPredecessor(c,tenant,origin.selector().id());if(prior==null)break;origin=prior;}
        var m=FollowupAttemptService.readRecovery(c,tenant,fact);
        var wait=EventResponsibilityReader.databaseBacked().latestWait(c,tenant,taskId);
        if(m==null||!m.nextTask().equals(origin.selector().id())||!m.actor().equals(origin.owner())||!m.basis().opportunity().equals(task.subject())||wait==null||!m.nextCheckAt().equals(wait.resumeDue())||("QUOTE".equals(m.context()))!=(task.type()==TaskFactory.Type.RECORD_QUOTE_REPLY)||R2SalesStageGuards.contractTakenOver(c,tenant,task.subject().id()))throw stale();
        return fact;
    }
    public static Subject reopen(Connection c,UUID tenant,Subject expected,Subject opportunity,UUID owner,Subject expectedWait,Subject attempt,Instant due)throws SQLException {
        var tasks=TaskFactory.databaseBacked();tasks.lock(c,tenant,expected.id());var task=tasks.read(c,tenant,expected.id());var wait=EventResponsibilityReader.databaseBacked().latestWait(c,tenant,expected.id());
        if(task==null||!task.selector().equals(expected)||!"WAITING".equals(task.state())||!task.subject().equals(opportunity)||!owner.equals(task.owner())||wait==null||!expectedWait.equals(wait.selector())||wait.taskRevision()!=expected.revision()||wait.version()!=1||!Set.of("R2_SALES_ATTEMPT_WAIT_V1","R2_ATTEMPT_HANDOFF_WAIT_V1").contains(wait.profile())||!due.equals(wait.resumeDue())||due.isAfter(tasks.now(c))||!attempt.equals(source(c,tenant,expected.id())))throw stale();
        return tasks.reopenFollowupAttempt(c,tenant,expected,opportunity,owner,expectedWait,attempt,due).selector();
    }
    private static CommandHandler.Rejected stale(){return new CommandHandler.Rejected("STALE_TASK");}
}
