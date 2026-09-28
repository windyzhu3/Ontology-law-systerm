package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;import java.time.*;import java.util.*;
public final class R2FollowupAttemptServices {
    private R2FollowupAttemptServices(){}
    public static FollowupAttemptService create(OpportunityProgressProtection protection){return FollowupAttemptService.databaseBacked(protection,new QuoteDraftService.Codec(){public String encode(Map<String,Object> value){return io.github.windyzhu3.ontologylaw.execution.CanonicalJson.encode(value);}@SuppressWarnings("unchecked") public Map<String,Object> decode(String value){return (Map<String,Object>)tools.jackson.databind.json.JsonMapper.builder().build().readValue(value,Map.class);}},new FollowupAttemptService.Ports(){
        private final TaskFactory tasks=TaskFactory.databaseBacked();
        public FollowupAttemptService.Task read(Connection c,UUID tenant,UUID id)throws SQLException{
            var t=tasks.read(c,tenant,id);if(t==null)return null;var current=CurrentTaskReader.databaseBacked().read(c,tenant,id);var wait="WAITING".equals(t.state())?EventResponsibilityReader.databaseBacked().latestWait(c,tenant,id):null;
            return new FollowupAttemptService.Task(t.selector(),t.subject(),current.responsibilityBasis(),t.owner(),t.type().name(),t.state(),wait==null?null:wait.selector());
        }
        public FollowupAttemptService.Task current(Connection c,UUID tenant,UUID id)throws SQLException{var t=tasks.currentTask(c,tenant,id);return t==null?null:read(c,tenant,t.selector().id());}
        public boolean contractTakenOver(Connection c,UUID tenant,UUID opportunity)throws SQLException{return R2SalesStageGuards.contractTakenOver(c,tenant,opportunity);}
        public Subject arrange(Connection c,UUID tenant,FollowupAttemptService.Task prior,Subject fact,ZoneId zone,Instant due,Instant now)throws SQLException{
            var t=tasks.read(c,tenant,prior.selector().id());if(t==null||!t.selector().equals(prior.selector()))throw new FollowupAttemptService.Blocked("STALE_TASK");
            return tasks.arrangeFollowupAttempt(c,tenant,t,fact,zone,now,due).selector();
        }
    });}
    public static io.github.windyzhu3.ontologylaw.execution.R1AuthorizationFacts.FollowupAttempt authorization(Connection c,UUID tenant,io.github.windyzhu3.ontologylaw.execution.CommandAuthorizationBinding.FollowupAttempt b,Subject receipt)throws SQLException {
        var opening=EventOpportunityReader.databaseBacked().byId(c,tenant,b.opportunity().id());if(opening==null)return null;
        var task=TaskFactory.databaseBacked().read(c,tenant,b.task().id());if(task==null||!task.subject().equals(b.opportunity()))return null;
        var current=OpportunityResponsibilityReader.databaseBacked().current(c,tenant,opening.selector());if(current==null)return null;
        var all=new LinkedHashSet<Subject>(R2CustomerRequirementsServices.sourceFacts(c,tenant,opening.selector(),null));
        all.add(opening.selector());all.add(current.basis());all.add(b.opportunity());all.add(b.basis());all.add(b.task());all.add(task.selector());
        if(b.waitReceipt()!=null)all.add(b.waitReceipt());if(b.workflow()!=null){all.add(b.workflow());all.addAll(R2QuoteServices.facts(c,tenant,b.opportunity().id()));}
        if(receipt!=null){var m=FollowupAttemptService.readMetadata(c,tenant,receipt);if(m==null||!m.basis().equals(R2FollowupAttemptInput.basis(b)))return null;all.add(receipt);var next=TaskFactory.databaseBacked().read(c,tenant,m.nextTask());if(next==null)return null;all.add(next.selector());}
        return new io.github.windyzhu3.ontologylaw.execution.R1AuthorizationFacts.FollowupAttempt(List.copyOf(all),R2OpportunityOwnerExceptionAssembly.organization(c,tenant,opening.selector()),task.owner());
    }

}
