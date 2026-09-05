package io.github.windyzhu3.ontologylaw.responsibility.internal.persistence;

import io.github.windyzhu3.ontologylaw.responsibility.AuthorizationTaskReader;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.util.*;
import org.jooq.*;
import org.jooq.impl.DSL;
import static io.github.windyzhu3.ontologylaw.responsibility.internal.persistence.jooq.Tables.*;

public final class JooqAuthorizationTaskReader implements AuthorizationTaskReader {
    public Task read(Connection c,UUID tenant,UUID taskId) {
        var db=DSL.using(c,SQLDialect.POSTGRES);var t=TASK_OCCURRENCE;var d=ACTION_DRAFT;
        var task=db.select(t.REVISION,t.OWNER_APPOINTMENT_ID,t.BUSINESS_PURPOSE_CODE,t.PRIMARY_COMMAND_CODE,t.SUBJECT_TYPE,t.SUBJECT_ID,t.SUBJECT_REVISION,t.SUBJECT_HASH)
                .from(t).where(t.TENANT_ID.eq(tenant)).and(t.TASK_OCCURRENCE_ID.eq(taskId)).fetchOne();
        if(task==null)return null;
        var draft=db.select(d.ACTION_DRAFT_ID,d.REVISION,d.ACTION_CODE,d.PAYLOAD_SCHEMA_CODE,d.PAYLOAD_SCHEMA_VERSION)
                .from(d).where(d.TENANT_ID.eq(tenant)).and(d.TASK_OCCURRENCE_ID.eq(taskId)).fetchOne();
        Draft value=draft==null?null:new Draft(new Subject("responsibility.action_draft",draft.get(d.ACTION_DRAFT_ID),draft.get(d.REVISION),null),
                taskId,draft.get(d.ACTION_CODE),draft.get(d.PAYLOAD_SCHEMA_CODE),draft.get(d.PAYLOAD_SCHEMA_VERSION));
        byte[] hash=task.get(t.SUBJECT_HASH);
        return new Task(new Subject("responsibility.task_occurrence",taskId,task.get(t.REVISION),null),task.get(t.OWNER_APPOINTMENT_ID),
                task.get(t.BUSINESS_PURPOSE_CODE),task.get(t.PRIMARY_COMMAND_CODE),
                new Subject(task.get(t.SUBJECT_TYPE),task.get(t.SUBJECT_ID),task.get(t.SUBJECT_REVISION),hash==null?null:Base64.getUrlEncoder().withoutPadding().encodeToString(hash)),value);
    }
}
