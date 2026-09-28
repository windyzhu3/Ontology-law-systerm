package io.github.windyzhu3.ontologylaw.responsibility.internal.persistence;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import io.github.windyzhu3.ontologylaw.execution.CommandHandler;
import java.sql.*;
import java.time.*;
import java.util.*;

public final class JooqOpportunityTaskClosure implements OpportunityTaskClosure {
    public Active active(Connection c,UUID tenant,Subject opportunity)throws SQLException {
        UUID id=null;
        try(var p=c.prepareStatement("select task_occurrence_id from responsibility.task_occurrence where tenant_id=? and subject_type='opportunity.opportunity' and subject_id=? and business_purpose_code in ('PROGRESS_OPPORTUNITY','PREPARE_QUOTE','DELIVER_QUOTE','RECORD_QUOTE_REPLY','RESOLVE_QUOTE_AUTHORITY','SUBMIT_QUOTE_APPROVAL','APPROVE_QUOTE') and state in ('OPEN','WAITING') order by task_occurrence_id limit 2")){
            p.setObject(1,tenant);p.setObject(2,opportunity.id());try(var r=p.executeQuery()){if(r.next())id=r.getObject(1,UUID.class);if(r.next())stale();}
        }
        if(id==null)return null;
        var task=CurrentTaskReader.databaseBacked().read(c,tenant,id);if(task==null||!task.subject().equals(opportunity))stale();
        Subject wait=null;if("WAITING".equals(task.state())){var w=EventResponsibilityReader.databaseBacked().latestWait(c,tenant,id);if(w==null||w.taskRevision()!=task.selector().revision())stale();wait=w.selector();}
        return new Active(task,wait);
    }
    public void cancel(Connection c,UUID tenant,Subject opportunity,Subject basis,UUID owner,Subject task,Subject wait,Subject closure,Instant at)throws SQLException{
        if(c.getAutoCommit())throw new SQLException("Closure requires transaction","25001");
        if(!"opportunity.closure".equals(closure.type())||!Long.valueOf(0).equals(closure.revision()))throw new IllegalArgumentException("Exact closure required");
        if(task!=null)TaskFactory.databaseBacked().lock(c,tenant,task.id());
        var active=active(c,tenant,opportunity);
        if(task==null){if(active!=null||wait!=null)stale();return;}
        if(active==null||!active.task().selector().equals(task)||!Objects.equals(active.waitReceipt(),wait)||!active.task().responsibilityBasis().equals(basis)||!active.task().owner().equals(owner))stale();
        CommandHandler.nextRevision(task.revision());
        try(var p=c.prepareStatement("update responsibility.task_occurrence set state='CANCELLED',revision=revision+1,cancelled_at=?,cancellation_reason_code='R2_OPPORTUNITY_CLOSE_V1',cancellation_fact_type='opportunity.closure',cancellation_fact_id=?,cancellation_fact_revision=0 where tenant_id=? and task_occurrence_id=? and revision=? and state in ('OPEN','WAITING')")){
            p.setObject(1,at.atOffset(ZoneOffset.UTC));p.setObject(2,closure.id());p.setObject(3,tenant);p.setObject(4,task.id());p.setLong(5,task.revision());if(p.executeUpdate()!=1)stale();
        }
    }
    private static void stale(){throw new CommandHandler.Rejected("STALE_TASK");}
}
