package io.github.windyzhu3.ontologylaw.responsibility.internal.persistence;

import io.github.windyzhu3.ontologylaw.responsibility.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.util.*;
import org.jooq.*;
import org.jooq.impl.DSL;
import static io.github.windyzhu3.ontologylaw.responsibility.internal.persistence.jooq.Tables.*;

public final class JooqCurrentTaskReader implements CurrentTaskReader {
    private static DSLContext db(Connection c) { return DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false)); }
    private static String hash(byte[] bytes) { return bytes==null?null:Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static Task task(org.jooq.Record r) {
        if(r==null)return null;var t=TASK_OCCURRENCE;
        var type=TaskFactory.Type.valueOf(r.get(t.BUSINESS_PURPOSE_CODE));
        if(Set.of("OPEN","WAITING").contains(r.get(t.STATE)) &&
                (!type.command.equals(r.get(t.PRIMARY_COMMAND_CODE)) ||
                 !type.completionType.equals(r.get(t.EXPECTED_COMPLETION_FACT_TYPE)) ||
                 !type.slaCode().equals(r.get(t.ORIGINAL_SLA_CODE)) ||
                 type.slaSeconds()!=r.get(t.ORIGINAL_SLA_SECONDS) ||
                 !"lead.lead".equals(r.get(t.SUBJECT_TYPE))))
            throw new IllegalArgumentException("Unregistered current Task contract");
        return new Task(new Subject("responsibility.task_occurrence",r.get(t.TASK_OCCURRENCE_ID),r.get(t.REVISION),null),
                r.get(t.OWNER_APPOINTMENT_ID),type,
                new Subject(r.get(t.SUBJECT_TYPE),r.get(t.SUBJECT_ID),r.get(t.SUBJECT_REVISION),hash(r.get(t.SUBJECT_HASH))),
                r.get(t.STATE),r.get(t.CREATED_AT).toInstant(),r.get(t.ORIGINAL_SLA_CODE),r.get(t.ORIGINAL_SLA_SECONDS),r.get(t.ORIGINAL_SLA_DUE_AT).toInstant(),
                r.get(t.COMPLETION_FACT_TYPE)==null?null:new Subject(r.get(t.COMPLETION_FACT_TYPE),r.get(t.COMPLETION_FACT_ID),r.get(t.COMPLETION_FACT_REVISION),hash(r.get(t.COMPLETION_FACT_HASH))));
    }
    public Task read(Connection c,UUID tenant,UUID id) {
        var t=TASK_OCCURRENCE;
        return task(db(c).selectFrom(t).where(t.TENANT_ID.eq(tenant)).and(t.TASK_OCCURRENCE_ID.eq(id)).fetchOne());
    }
    public List<Task> ownedTasks(Connection c,UUID tenant,UUID owner) {
        var t=TASK_OCCURRENCE;
        return db(c).selectFrom(t).where(t.TENANT_ID.eq(tenant)).and(t.OWNER_APPOINTMENT_ID.eq(owner))
                .orderBy(t.CREATED_AT,t.TASK_OCCURRENCE_ID).fetch().stream().map(JooqCurrentTaskReader::task).toList();
    }
    public Decision decision(Connection c,UUID tenant,UUID id) {
        var d=DECISION_RECORD;var r=db(c).selectFrom(d).where(d.TENANT_ID.eq(tenant)).and(d.DECISION_RECORD_ID.eq(id)).fetchOne();
        if(r==null)return null;
        return new Decision(new Subject("responsibility.decision_record",id,null,hash(r.get(d.CONTENT_DIGEST))),r.get(d.TASK_OCCURRENCE_ID),
                new Subject(r.get(d.DECISION_SUBJECT_TYPE),r.get(d.DECISION_SUBJECT_ID),r.get(d.DECISION_SUBJECT_REVISION),hash(r.get(d.DECISION_SUBJECT_HASH))),
                r.get(d.AUTHORITY_SLOT_CODE),r.get(d.DECISION_CONTRACT_CODE),r.get(d.DECISION_CONTRACT_VERSION),r.get(d.DECISION_CODE),r.get(d.RATIONALE_SUMMARY),r.get(d.DECIDED_AT).toInstant());
    }
    public Decision causalStop(Connection c,UUID tenant,Task task) {
        var selector=new JooqTaskRepository().causalStop(c,tenant,new TaskFactory.Task(task.selector(),task.owner(),task.type(),task.lead(),task.state(),task.createdAt(),task.completion()));
        return selector==null?null:decision(c,tenant,selector.id());
    }
    public List<Task> completedContactTasks(Connection c,UUID tenant,UUID leadId) {
        var t=TASK_OCCURRENCE;
        return db(c).selectFrom(t).where(t.TENANT_ID.eq(tenant)).and(t.SUBJECT_TYPE.eq("lead.lead")).and(t.SUBJECT_ID.eq(leadId))
            .and(t.BUSINESS_PURPOSE_CODE.eq("CONTACT_LEAD")).and(t.PRIMARY_COMMAND_CODE.eq("RECORD_CONTACT_RESULT"))
            .and(t.EXPECTED_COMPLETION_FACT_TYPE.eq("lead.lead_contact_result")).and(t.STATE.eq("DONE"))
            .and(t.COMPLETION_FACT_TYPE.eq("lead.lead_contact_result")).and(t.COMPLETION_FACT_REVISION.isNull())
            .fetch().stream().map(JooqCurrentTaskReader::task).toList();
    }
}
