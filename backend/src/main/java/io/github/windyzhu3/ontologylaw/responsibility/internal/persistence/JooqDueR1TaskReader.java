package io.github.windyzhu3.ontologylaw.responsibility.internal.persistence;

import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import org.jooq.*;
import org.jooq.impl.DSL;
import static io.github.windyzhu3.ontologylaw.responsibility.internal.persistence.jooq.Tables.*;

public final class JooqDueR1TaskReader implements DueR1TaskReader {
    public List<Position> scan(Connection c,UUID tenant,TaskFactory.Type type,Set<UUID> owners,Instant observed,Position after,int limit) {
        if(limit<1||limit>100||!Set.of(TaskFactory.Type.CONTACT_LEAD,TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP).contains(type))throw new IllegalArgumentException("Invalid due scan");
        if(owners.isEmpty())return List.of();var t=TASK_OCCURRENCE;var w=WAIT_RECEIPT;var later=WAIT_RECEIPT.as("later_wait");
        Condition predicate=t.TENANT_ID.eq(tenant).and(t.OWNER_APPOINTMENT_ID.in(owners)).and(t.BUSINESS_PURPOSE_CODE.eq(type.name())).and(t.STATE.eq("WAITING"))
            .and(w.RESUME_DUE_AT.le(observed.atOffset(ZoneOffset.UTC)))
            .and(DSL.notExists(DSL.selectOne().from(later).where(later.TENANT_ID.eq(w.TENANT_ID)).and(later.TASK_OCCURRENCE_ID.eq(w.TASK_OCCURRENCE_ID)).and(later.WAIT_SEQUENCE.gt(w.WAIT_SEQUENCE))));
        if(after!=null){var due=after.dueAt().atOffset(ZoneOffset.UTC);predicate=predicate.and(w.RESUME_DUE_AT.gt(due).or(w.RESUME_DUE_AT.eq(due).and(t.TASK_OCCURRENCE_ID.gt(after.taskId()))));}
        return DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false))
            .select(t.TASK_OCCURRENCE_ID,w.RESUME_DUE_AT).from(t).join(w).on(w.TENANT_ID.eq(t.TENANT_ID).and(w.TASK_OCCURRENCE_ID.eq(t.TASK_OCCURRENCE_ID)))
            .where(predicate).orderBy(w.RESUME_DUE_AT,t.TASK_OCCURRENCE_ID).limit(limit).fetch(r->new Position(r.value2().toInstant(),r.value1()));
    }
}
