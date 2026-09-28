package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.execution.CanonicalJson;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.responsibility.TaskFactory;
import io.github.windyzhu3.ontologylaw.responsibility.CurrentTaskReader;
import java.sql.*;
import java.time.*;
import java.util.UUID;

/** Internal composition only. Does not register an HTTP endpoint, worker or production card. */
public final class R2OpportunityProgressServices {
    private R2OpportunityProgressServices(){}
    public static OpportunityProgressService create(OpportunityProgressProtection protection){
        var tasks=TaskFactory.databaseBacked();
        return OpportunityProgressService.databaseBacked(protection,CanonicalJson::encode,new OpportunityProgressService.Responsibility(){
            public OpportunityProgressService.PendingTask lockAndRead(Connection c,UUID tenant,UUID id)throws SQLException{
                tasks.lock(c,tenant,id);var task=CurrentTaskReader.databaseBacked().read(c,tenant,id);
                return task==null?null:new OpportunityProgressService.PendingTask(task.selector(),task.owner(),task.subject(),task.type().name(),task.type().command,task.state());
            }
            public Subject completeAndSchedule(Connection c,UUID tenant,OpportunityProgressService.PendingTask expected,Subject fact,ZoneId zone,Instant now,Instant due)throws SQLException{
                var task=tasks.read(c,tenant,expected.selector().id());
                if(task==null||!task.selector().equals(expected.selector()))throw new OpportunityProgressService.Blocked("STALE_TASK");
                tasks.complete(c,tenant,task,fact,now);
                return tasks.createOpportunityFollowup(c,tenant,tasks.read(c,tenant,task.selector().id()),fact,zone,now,due).selector();
            }
        });
    }
}
