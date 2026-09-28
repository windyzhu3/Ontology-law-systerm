package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.sql.*;
import java.util.*;

final class R2FollowupAttemptReadService {
    private final FollowupAttemptService attempts;private final OpportunityCommandReader opportunities;private final QuoteWorkflowService quotes;private final AuditAppender audit;
    R2FollowupAttemptReadService(OpportunityProgressProtection protection,AuditAppender audit){attempts=R2FollowupAttemptServices.create(protection);opportunities=OpportunityCommandReader.databaseBacked(protection);quotes=R2QuoteServices.create(protection);this.audit=audit;}
    Map<String,Object> read(Connection c,Actor actor,UUID id)throws SQLException{return FollowupAttemptReadRuntime.read(c,actor,audit,(tx,now)->{
        var root=opportunities.header(tx,actor.tenantId(),id);if(root==null)throw fail("NOT_FOUND");
        var current=OpportunityResponsibilityReader.databaseBacked().current(tx,actor.tenantId(),root.selector());
        var org=R2OpportunityOwnerExceptionAssembly.organization(tx,actor.tenantId(),root.selector());if(org==null)throw fail("NOT_AUTHORIZED");
        var rows=attempts.history(tx,actor.tenantId(),id);var facts=new LinkedHashSet<>(R2CustomerRequirementsServices.sourceFacts(tx,actor.tenantId(),root.selector(),null));facts.add(current.basis());
        var tasks=TaskFactory.databaseBacked().activeForLead(tx,actor.tenantId(),root.selector()).stream().filter(t->Set.of(TaskFactory.Type.PROGRESS_OPPORTUNITY,TaskFactory.Type.RECORD_QUOTE_REPLY).contains(t.type())).toList();
        // Multiple live main responsibilities are a blocked state; never choose one arbitrarily.
        var task=tasks.size()==1?tasks.getFirst():null;for(var t:tasks)facts.add(t.selector());
        var wait=task!=null&&"WAITING".equals(task.state())?EventResponsibilityReader.databaseBacked().latestWait(tx,actor.tenantId(),task.selector().id()):null;if(wait!=null)facts.add(wait.selector());
        boolean quote=task!=null&&task.type()==TaskFactory.Type.RECORD_QUOTE_REPLY;
        var workflow=quote?attempts.quoteBasis(tx,actor.tenantId(),id):null;if(quote)facts.addAll(quotes.protectedFacts(tx,actor.tenantId(),id));
        for(var m:rows){facts.add(m.selector());facts.add(m.basis().opportunity());facts.add(m.basis().responsibility());facts.add(m.basis().task());if(m.basis().waitReceipt()!=null)facts.add(m.basis().waitReceipt());if(m.basis().quoteWorkflow()!=null)facts.add(m.basis().quoteWorkflow());}
        boolean quoteHistory=quote||rows.stream().anyMatch(m->"QUOTE".equals(m.context()));
        String writeCode=quote?"QUOTE_RESPONSE":"SALES_OPPORTUNITY_OWNER";var authority=OpportunityLedgerAuthorityReader.databaseBacked();String readCode=quoteHistory?(authority.permitted(tx,actor,org,List.copyOf(facts),"QUOTE_RESPONSE")?"QUOTE_RESPONSE":"QUOTE_READ"):(authority.permitted(tx,actor,org,List.copyOf(facts),writeCode)?writeCode:"OPPORTUNITY_LEDGER_READ");
        var disclosures=new ArrayList<AuditAppender.FollowupAttemptDisclosureEntry>();for(var fact:facts){var permission=authority.evidence(tx,actor,org,fact,readCode);if(permission==null||!permission.allowed())throw fail("NOT_AUTHORIZED");disclosures.add(new AuditAppender.FollowupAttemptDisclosureEntry(UUID.randomUUID(),UUID.randomUUID(),fact,permission,"BODY"));}
        boolean allowed=!root.closed()&&task!=null&&task.owner().equals(actor.appointmentId())&&current.appointmentId().equals(actor.appointmentId())&&!R2SalesStageGuards.contractTakenOver(tx,actor.tenantId(),id)&&authority.permitted(tx,actor,org,List.copyOf(facts),writeCode);
        if(quote)allowed&=workflow!=null&&Objects.equals(workflow.task(),task.selector())&&Set.of("AWAIT_REPLY","FOLLOW_UP","CLARIFY_REPLY").contains(workflow.stage());else allowed&=R2SalesStageGuards.initialFollowupAllowed(tx,actor.tenantId(),id);
        var result=new LinkedHashMap<String,Object>();result.put("opportunity",R2CustomerRequirementsServices.selector(root.selector()));result.put("responsibilityBasis",CommandScope.selector(current.basis()));result.put("task",task==null?null:R2CustomerRequirementsServices.selector(task.selector()));result.put("taskState",task==null?null:task.state());result.put("waitReceipt",wait==null?null:Map.of("id",wait.selector().id().toString(),"hash",wait.selector().hash()));result.put("expectedWorkflow",workflow==null?null:R2CustomerRequirementsServices.selector(workflow.selector()));result.put("nextCheckAt",wait==null?null:wait.resumeDue().toString());result.put("command",allowed?(quote?"RECORD_QUOTE_FOLLOWUP_ATTEMPT":"RECORD_OPPORTUNITY_FOLLOWUP_ATTEMPT"):null);
        var history=new ArrayList<Map<String,Object>>();for(var m:rows)history.add(Map.of("selector",Map.of("id",m.selector().id().toString(),"hash",m.selector().hash()),"context",m.context(),"type",m.type(),"summary",attempts.summary(tx,actor.tenantId(),m),"occurredAt",m.occurredAt().toString(),"nextCheckAt",m.nextCheckAt().toString(),"recordedAt",m.createdAt().toString(),"actorAppointmentId",m.actor().toString()));result.put("history",history);
        if(!root.equals(opportunities.header(tx,actor.tenantId(),id))||!current.equals(OpportunityResponsibilityReader.databaseBacked().current(tx,actor.tenantId(),root.selector()))||!rows.equals(attempts.history(tx,actor.tenantId(),id))||task!=null&&!task.equals(TaskFactory.databaseBacked().read(tx,actor.tenantId(),task.selector().id())))throw fail("STALE_SUBJECT");
        for(var d:disclosures)if(!AuthorizationService.databaseBacked().evaluate(tx,d.authorization().request(),true).allowed())throw fail("NOT_AUTHORIZED");
        return new FollowupAttemptReadRuntime.Prepared<>(result,disclosures);
    });}
    private static R1ServiceReadRuntime.Failure fail(String code){return new R1ServiceReadRuntime.Failure(code.equals("NOT_FOUND")?404:code.equals("STALE_SUBJECT")?412:403,code);}
}
