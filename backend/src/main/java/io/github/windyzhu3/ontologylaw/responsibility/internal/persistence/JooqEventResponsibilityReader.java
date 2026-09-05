package io.github.windyzhu3.ontologylaw.responsibility.internal.persistence;

import io.github.windyzhu3.ontologylaw.execution.CanonicalJson;
import io.github.windyzhu3.ontologylaw.execution.R1EventFacts;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import io.github.windyzhu3.ontologylaw.responsibility.EventResponsibilityReader;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import static io.github.windyzhu3.ontologylaw.responsibility.internal.persistence.jooq.Tables.*;

public final class JooqEventResponsibilityReader implements EventResponsibilityReader {
    public R1EventFacts.Task task(Connection c,UUID tenant,UUID id) {
        var db=DSL.using(c,SQLDialect.POSTGRES);var t=TASK_OCCURRENCE;var d=ACTION_DRAFT;
        var r=db.selectFrom(t).where(t.TENANT_ID.eq(tenant)).and(t.TASK_OCCURRENCE_ID.eq(id)).fetchOne();
        if(r==null)return null;
        // Candidate payload is deliberately excluded from event/authorization metadata reads.
        var draft=db.select(d.ACTION_DRAFT_ID,d.REVISION,d.ACTION_CODE,d.PAYLOAD_SCHEMA_CODE,d.PAYLOAD_SCHEMA_VERSION,d.STATE)
                .from(d).where(d.TENANT_ID.eq(tenant)).and(d.TASK_OCCURRENCE_ID.eq(id)).fetchOne();
        return new R1EventFacts.Task(new Subject("responsibility.task_occurrence",id,r.get(t.REVISION),null),
                subject(r.get(t.SUBJECT_TYPE),r.get(t.SUBJECT_ID),r.get(t.SUBJECT_REVISION),r.get(t.SUBJECT_HASH)),
                r.get(t.OWNER_APPOINTMENT_ID),r.get(t.BUSINESS_PURPOSE_CODE),r.get(t.PRIMARY_COMMAND_CODE),r.get(t.STATE),
                r.get(t.ORIGINAL_SLA_CODE),r.get(t.ORIGINAL_SLA_SECONDS),r.get(t.ORIGINAL_SLA_DUE_AT).toInstant(),
                subject(r.get(t.COMPLETION_FACT_TYPE),r.get(t.COMPLETION_FACT_ID),r.get(t.COMPLETION_FACT_REVISION),r.get(t.COMPLETION_FACT_HASH)),
                draft==null?null:new R1EventFacts.Draft(new Subject("responsibility.action_draft",draft.value1(),draft.value2(),null),id,draft.value3(),draft.value4(),draft.value5(),draft.value6()));
    }
    public R1EventFacts.Decision decision(Connection c,UUID tenant,UUID id) {
        var d=DECISION_RECORD;
        var r=DSL.using(c,SQLDialect.POSTGRES).selectFrom(d).where(d.TENANT_ID.eq(tenant)).and(d.DECISION_RECORD_ID.eq(id)).fetchOne();
        return r==null?null:new R1EventFacts.Decision(subject("responsibility.decision_record",id,null,r.get(d.CONTENT_DIGEST)),r.get(d.TASK_OCCURRENCE_ID),
                subject(r.get(d.DECISION_SUBJECT_TYPE),r.get(d.DECISION_SUBJECT_ID),r.get(d.DECISION_SUBJECT_REVISION),r.get(d.DECISION_SUBJECT_HASH)),
                r.get(d.DECISION_CONTRACT_CODE),r.get(d.DECISION_CONTRACT_VERSION),r.get(d.DECISION_CODE));
    }
    public R1EventFacts.Wait latestWait(Connection c,UUID tenant,UUID taskId) {
        var w=WAIT_RECEIPT;
        var r=DSL.using(c,SQLDialect.POSTGRES).selectFrom(w).where(w.TENANT_ID.eq(tenant)).and(w.TASK_OCCURRENCE_ID.eq(taskId))
                .orderBy(w.WAIT_SEQUENCE.desc()).limit(1).fetchOne();
        if(r==null)return null;
        // R1 immutable-row encoding: persisted column names, explicit nulls, only tenantId renamed.
        var fields=new TreeMap<String,Object>();
        fields.put("tenantId",tenant.toString());fields.put("wait_receipt_id",r.get(w.WAIT_RECEIPT_ID).toString());
        fields.put("task_occurrence_id",taskId.toString());fields.put("task_revision",r.get(w.TASK_REVISION));fields.put("wait_sequence",r.get(w.WAIT_SEQUENCE));
        fields.put("wait_reason_code",r.get(w.WAIT_REASON_CODE));fields.put("wait_contract_code",r.get(w.WAIT_CONTRACT_CODE));fields.put("wait_contract_version",r.get(w.WAIT_CONTRACT_VERSION));
        fields.put("entered_waiting_at",timestamp(r.get(w.ENTERED_WAITING_AT)));fields.put("resume_due_at",timestamp(r.get(w.RESUME_DUE_AT)));
        fields.put("recorded_by_appointment_id",r.get(w.RECORDED_BY_APPOINTMENT_ID).toString());fields.put("awaited_fact_type",r.get(w.AWAITED_FACT_TYPE));
        fields.put("awaited_fact_id",r.get(w.AWAITED_FACT_ID)==null?null:r.get(w.AWAITED_FACT_ID).toString());fields.put("awaited_fact_revision",r.get(w.AWAITED_FACT_REVISION));fields.put("awaited_fact_hash",base64(r.get(w.AWAITED_FACT_HASH)));
        return new R1EventFacts.Wait(new Subject("responsibility.wait_receipt",r.get(w.WAIT_RECEIPT_ID),null,base64(CanonicalJson.digest(CanonicalJson.encode(fields)))),
                taskId,r.get(w.TASK_REVISION),r.get(w.WAIT_CONTRACT_CODE),r.get(w.WAIT_CONTRACT_VERSION),r.get(w.RESUME_DUE_AT)==null?null:r.get(w.RESUME_DUE_AT).toInstant());
    }
    private static Subject subject(String type,UUID id,Long revision,byte[] hash){return type==null?null:new Subject(type,id,revision,base64(hash));}
    private static String base64(byte[] value){return value==null?null:Base64.getUrlEncoder().withoutPadding().encodeToString(value);}
    private static String timestamp(OffsetDateTime value){return value==null?null:new DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(value.toInstant());}
}
