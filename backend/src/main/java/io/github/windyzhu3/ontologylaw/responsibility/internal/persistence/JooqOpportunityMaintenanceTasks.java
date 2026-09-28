package io.github.windyzhu3.ontologylaw.responsibility.internal.persistence;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.jooq.impl.DSL;
import org.jooq.SQLDialect;
import static io.github.windyzhu3.ontologylaw.responsibility.internal.persistence.jooq.Tables.*;
public final class JooqOpportunityMaintenanceTasks implements OpportunityMaintenanceTasks {
    private static org.jooq.DSLContext db(Connection c){return DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false));}
    public TaskFactory.Task initial(Connection c,UUID tenant,UUID opportunity){
        var t=TASK_OCCURRENCE;var rows=db(c).select(t.TASK_OCCURRENCE_ID).from(t).where(t.TENANT_ID.eq(tenant)).and(t.SUBJECT_TYPE.eq("opportunity.opportunity")).and(t.SUBJECT_ID.eq(opportunity)).and(t.BUSINESS_PURPOSE_CODE.eq("PROGRESS_OPPORTUNITY")).and(t.PREDECESSOR_TASK_OCCURRENCE_ID.isNull()).and(DSL.field("handoff_predecessor_task_occurrence_id",UUID.class).isNull()).limit(2).fetch(t.TASK_OCCURRENCE_ID);
        if(rows.size()>1)throw new IllegalStateException("Duplicate initial responsibility");
        return rows.isEmpty()?null:new JooqTaskRepository().read(c,tenant,rows.getFirst());
    }
    public boolean initialExists(Connection c,UUID tenant,UUID opportunity){
        var t=TASK_OCCURRENCE;return db(c).fetchExists(DSL.selectOne().from(t).where(t.TENANT_ID.eq(tenant)).and(t.SUBJECT_TYPE.eq("opportunity.opportunity"))
                .and(t.SUBJECT_ID.eq(opportunity)).and(t.BUSINESS_PURPOSE_CODE.eq("PROGRESS_OPPORTUNITY")).and(t.PREDECESSOR_TASK_OCCURRENCE_ID.isNull()).and(DSL.field("handoff_predecessor_task_occurrence_id",UUID.class).isNull()));
    }
    public io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject waitProgress(Connection c,UUID tenant,UUID id)throws SQLException {
        var task=new JooqTaskRepository().read(c,tenant,id);var receipt=new JooqEventResponsibilityReader().latestWait(c,tenant,id);
        if(task==null||receipt==null)throw new io.github.windyzhu3.ontologylaw.execution.CommandHandler.Rejected("STALE_TASK");
        return JooqOpportunityTaskHandoff.verifyWait(c,tenant,task,receipt,JooqOpportunityTaskHandoff.row(c,tenant,id).get("original_sla_due_at",OffsetDateTime.class).toInstant());
    }
    public TaskFactory.Task predecessor(Connection c,UUID tenant,UUID task){
        var t=TASK_OCCURRENCE;var id=db(c).select(t.PREDECESSOR_TASK_OCCURRENCE_ID).from(t).where(t.TENANT_ID.eq(tenant)).and(t.TASK_OCCURRENCE_ID.eq(task)).fetchOne(t.PREDECESSOR_TASK_OCCURRENCE_ID);
        return id==null?null:new JooqTaskRepository().read(c,tenant,id);
    }
    public TaskFactory.Task handoffPredecessor(Connection c,UUID tenant,UUID task){
        var r=JooqOpportunityTaskHandoff.row(c,tenant,task);var id=r==null?null:r.get("handoff_predecessor_task_occurrence_id",UUID.class);
        return id==null?null:new JooqTaskRepository().read(c,tenant,id);
    }
    public List<Position> due(Connection c,UUID tenant,Set<UUID> owners,Instant observed,Position after,int limit){
        if(limit<1||limit>100)throw new IllegalArgumentException("Bounded scan required");if(owners.isEmpty())return List.of();
        var t=TASK_OCCURRENCE;var w=WAIT_RECEIPT;var later=WAIT_RECEIPT.as("later_wait");
        var predicate=t.TENANT_ID.eq(tenant).and(t.OWNER_APPOINTMENT_ID.in(owners)).and(t.SUBJECT_TYPE.eq("opportunity.opportunity"))
                .and(t.BUSINESS_PURPOSE_CODE.eq("RECORD_QUOTE_REPLY").or(t.BUSINESS_PURPOSE_CODE.eq("PROGRESS_OPPORTUNITY").and(t.PREDECESSOR_TASK_OCCURRENCE_ID.isNotNull().or(DSL.field("handoff_predecessor_task_occurrence_id",UUID.class).isNotNull())))).and(t.STATE.eq("WAITING"))
                .and(w.RESUME_DUE_AT.le(observed.atOffset(ZoneOffset.UTC)))
                .and(DSL.notExists(DSL.selectOne().from(later).where(later.TENANT_ID.eq(w.TENANT_ID)).and(later.TASK_OCCURRENCE_ID.eq(w.TASK_OCCURRENCE_ID)).and(later.WAIT_SEQUENCE.gt(w.WAIT_SEQUENCE))));
        if(after!=null){var at=after.at().atOffset(ZoneOffset.UTC);predicate=predicate.and(w.RESUME_DUE_AT.gt(at).or(w.RESUME_DUE_AT.eq(at).and(t.TASK_OCCURRENCE_ID.gt(after.id()))));}
        return db(c).select(w.RESUME_DUE_AT,t.TASK_OCCURRENCE_ID).from(t).join(w).on(w.TENANT_ID.eq(t.TENANT_ID).and(w.TASK_OCCURRENCE_ID.eq(t.TASK_OCCURRENCE_ID)))
                .where(predicate).orderBy(w.RESUME_DUE_AT,t.TASK_OCCURRENCE_ID).limit(limit).fetch(r->new Position(r.value1().toInstant(),r.value2()));
    }
}


