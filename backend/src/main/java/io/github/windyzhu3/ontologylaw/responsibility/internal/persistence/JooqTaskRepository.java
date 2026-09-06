package io.github.windyzhu3.ontologylaw.responsibility.internal.persistence;
import io.github.windyzhu3.ontologylaw.responsibility.TaskFactory;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;import java.time.*;import java.util.*;
import io.github.windyzhu3.ontologylaw.responsibility.R1BusinessTime;
import io.github.windyzhu3.ontologylaw.execution.*;
import org.jooq.DSLContext;import org.jooq.SQLDialect;import org.jooq.impl.DSL;
import static io.github.windyzhu3.ontologylaw.responsibility.internal.persistence.jooq.Tables.*;
public final class JooqTaskRepository implements TaskFactory {
    private static DSLContext db(Connection c){return DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false));}
    private static UUID id(Connection c){return db(c).select(DSL.field("uuidv7()",UUID.class)).fetchOne(0,UUID.class);}
    private static OffsetDateTime time(Instant i){return i.atOffset(ZoneOffset.UTC);}
    private static String base64(byte[] b){return b==null?null:Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    public Task read(Connection c,UUID tenant,UUID id){
        var t=TASK_OCCURRENCE;var r=db(c).selectFrom(t).where(t.TENANT_ID.eq(tenant)).and(t.TASK_OCCURRENCE_ID.eq(id)).fetchOne();
        if(r==null)return null;
        return new Task(new Subject("responsibility.task_occurrence",id,r.get(t.REVISION),null),r.get(t.OWNER_APPOINTMENT_ID),Type.valueOf(r.get(t.BUSINESS_PURPOSE_CODE)),
            new Subject(r.get(t.SUBJECT_TYPE),r.get(t.SUBJECT_ID),r.get(t.SUBJECT_REVISION),base64(r.get(t.SUBJECT_HASH))),r.get(t.STATE),r.get(t.CREATED_AT).toInstant(),
            r.get(t.COMPLETION_FACT_TYPE)==null?null:new Subject(r.get(t.COMPLETION_FACT_TYPE),r.get(t.COMPLETION_FACT_ID),r.get(t.COMPLETION_FACT_REVISION),base64(r.get(t.COMPLETION_FACT_HASH))));
    }
    public void lock(Connection c,UUID tenant,UUID id){var t=TASK_OCCURRENCE;db(c).select(t.TASK_OCCURRENCE_ID).from(t).where(t.TENANT_ID.eq(tenant)).and(t.TASK_OCCURRENCE_ID.eq(id)).forUpdate().fetch();}
    public List<Task> activeForLead(Connection c,UUID tenant,Subject lead){var t=TASK_OCCURRENCE;return db(c).select(t.TASK_OCCURRENCE_ID).from(t).where(t.TENANT_ID.eq(tenant)).and(t.SUBJECT_TYPE.eq(lead.type())).and(t.SUBJECT_ID.eq(lead.id())).and(t.SUBJECT_REVISION.eq(lead.revision())).and(t.STATE.in("OPEN","WAITING")).fetch(t.TASK_OCCURRENCE_ID).stream().map(id->read(c,tenant,id)).toList();}
    public Task create(Connection c,UUID tenant,Type type,UUID owner,Subject lead,ZoneId zone,Instant now){
        if(!"lead.lead".equals(lead.type())||lead.revision()==null)throw new IllegalArgumentException("Exact Lead required");
        var t=TASK_OCCURRENCE;UUID id=id(c);
        db(c).insertInto(t).set(t.TENANT_ID,tenant).set(t.TASK_OCCURRENCE_ID,id).set(t.OWNER_APPOINTMENT_ID,owner)
            .set(t.BUSINESS_PURPOSE_CODE,type.name()).set(t.PRIMARY_COMMAND_CODE,type.command).set(t.EXPECTED_COMPLETION_FACT_TYPE,type.completionType)
            .set(t.ORIGINAL_SLA_CODE,type.slaCode()).set(t.ORIGINAL_SLA_SECONDS,type.slaSeconds()).set(t.ORIGINAL_SLA_DUE_AT,time(R1BusinessTime.due(now,type.slaSeconds(),zone)))
            .set(t.STATE,"OPEN").set(t.CREATED_AT,time(now)).set(t.REVISION,0L).set(t.SUBJECT_TYPE,lead.type()).set(t.SUBJECT_ID,lead.id()).set(t.SUBJECT_REVISION,lead.revision()).execute();
        return read(c,tenant,id);
    }
    public void complete(Connection c,UUID tenant,Task task,Subject fact,Instant now)throws SQLException{
        if(!task.type().completionType.equals(fact.type()))throw new IllegalArgumentException("Wrong completion fact");
        var t=TASK_OCCURRENCE;long revision=CommandHandler.nextRevision(task.selector().revision());
        int changed=db(c).update(t).set(t.STATE,"DONE").set(t.REVISION,revision).set(t.COMPLETED_AT,time(now)).set(t.COMPLETION_FACT_TYPE,fact.type())
            .set(t.COMPLETION_FACT_ID,fact.id()).set(t.COMPLETION_FACT_REVISION,fact.revision()).set(t.COMPLETION_FACT_HASH,fact.hash()==null?null:Base64.getUrlDecoder().decode(fact.hash()))
            .where(t.TENANT_ID.eq(tenant)).and(t.TASK_OCCURRENCE_ID.eq(task.selector().id())).and(t.REVISION.eq(task.selector().revision())).and(t.STATE.eq("OPEN")).execute();
        if(changed!=1)throw new CommandHandler.Rejected("STALE_TASK");
    }
    public Subject decision(Connection c,UUID tenant,Task task,UUID actor,String contract,String decision,String rationale,Map<String,Object> digestValues,Instant now)throws SQLException{
        validateDecision(tenant,task,contract,decision,rationale,digestValues);
        var d=DECISION_RECORD;UUID id=id(c);byte[] digest=CanonicalJson.digest(CanonicalJson.encode(digestValues));
        db(c).insertInto(d).set(d.TENANT_ID,tenant).set(d.DECISION_RECORD_ID,id).set(d.TASK_OCCURRENCE_ID,task.selector().id()).set(d.DECISION_VERSION,1)
            .set(d.DECIDED_BY_APPOINTMENT_ID,actor).set(d.AUTHORITY_SLOT_CODE,task.type().slot).set(d.DECISION_CONTRACT_CODE,contract).set(d.DECISION_CONTRACT_VERSION,1)
            .set(d.DECISION_CODE,decision).set(d.CONTENT_DIGEST,digest).set(d.RATIONALE_SUMMARY,rationale).set(d.DECIDED_AT,time(now))
            .set(d.DECISION_SUBJECT_TYPE,task.lead().type()).set(d.DECISION_SUBJECT_ID,task.lead().id()).set(d.DECISION_SUBJECT_REVISION,task.lead().revision()).execute();
        return new Subject("responsibility.decision_record",id,null,base64(digest));
    }
    /** The Owner accepts only the closed Task3 contracts, including their exact digest coverage. */
    private static void validateDecision(UUID tenant,Task task,String contract,String code,String rationale,Map<String,Object> values)throws SQLException {
        String expectedContract=switch(task.type()) {
            case RESOLVE_LEAD_DUPLICATE -> "LEAD_DUPLICATE_RESOLUTION";
            case RESOLVE_LEAD_ROUTING_GAP -> "LEAD_ROUTING_DISPOSITION";
            case ACK_SOURCE_INTAKE_STOP_REQUEST -> "SOURCE_INTAKE_STOP_REQUEST_ACKNOWLEDGED";
            default -> throw new IllegalArgumentException("Task has no Task3 Decision contract");
        };
        Set<String> codes=switch(task.type()) {
            case RESOLVE_LEAD_DUPLICATE -> Set.of("LINK_EXISTING_PARTY","KEEP_SEPARATE");
            case RESOLVE_LEAD_ROUTING_GAP -> Set.of("SCHEDULE_ROUTING_REVIEW","RETRY_ASSIGNMENT_NOW","REQUEST_SOURCE_INTAKE_STOP");
            default -> Set.of(expectedContract);
        };
        if(!expectedContract.equals(contract)||!codes.contains(code)||rationale==null||rationale.isBlank())throw new IllegalArgumentException("Unregistered Decision");
        var expected=new TreeMap<String,Object>();
        expected.put("tenantId",tenant.toString());expected.put("subject",Map.of("type",task.lead().type(),"id",task.lead().id().toString(),"revision",task.lead().revision()));
        expected.put("authoritySlot",task.type().slot);expected.put("decisionCode",code);expected.put("rationaleSummary",rationale);
        if(task.type()==Type.RESOLVE_LEAD_DUPLICATE) {
            for(String key:List.of("candidateLeadId","partyId")) {
                Object value=values.get(key);if(!(value instanceof String id)||!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException("Exact candidate required");expected.put(key,value);
            }
            for(String key:List.of("candidateLeadRevision","partyRevision")) {
                Object value=values.get(key);if(!(value instanceof Long revision)||revision<0||revision>9007199254740991L)throw new IllegalArgumentException("Exact candidate revision required");expected.put(key,value);
            }
            expected.put("newRevision",CommandHandler.nextRevision(task.lead().revision()));
            var changes=new TreeMap<String,Object>();changes.put("disposition_code",code);
            if(code.equals("LINK_EXISTING_PARTY")){changes.put("parsed_party_id",values.get("partyId"));changes.put("party_resolution_code","RESOLVED");}
            expected.put("newValues",changes);
        }
        if(task.type()==Type.ACK_SOURCE_INTAKE_STOP_REQUEST) {
            var causal=new Subject("responsibility.decision_record",UUID.fromString((String)values.get("causalDecisionId")),null,(String)values.get("causalDecisionHash"));
            expected.put("causalDecisionId",causal.id().toString());expected.put("causalDecisionHash",causal.hash());
        }
        if(!expected.equals(values))throw new IllegalArgumentException("Wrong Decision digest coverage");
    }
    public Subject waitUntil(Connection c,UUID tenant,Task task,UUID actor,Instant due,Instant now)throws SQLException{
        if(task.type()!=Type.RESOLVE_LEAD_ROUTING_GAP||!due.isAfter(now))throw new IllegalArgumentException("Routing wait required");
        var t=TASK_OCCURRENCE;long revision=CommandHandler.nextRevision(task.selector().revision());
        int changed=db(c).update(t).set(t.STATE,"WAITING").set(t.REVISION,revision).where(t.TENANT_ID.eq(tenant)).and(t.TASK_OCCURRENCE_ID.eq(task.selector().id()))
            .and(t.STATE.eq("OPEN")).and(t.REVISION.eq(task.selector().revision())).execute();
        if(changed!=1)throw new CommandHandler.Rejected("STALE_TASK");
        var w=WAIT_RECEIPT;
        int sequence=db(c).select(DSL.coalesce(DSL.max(w.WAIT_SEQUENCE),0)).from(w).where(w.TENANT_ID.eq(tenant)).and(w.TASK_OCCURRENCE_ID.eq(task.selector().id())).fetchOne(0,Integer.class)+1;
        db(c).insertInto(w).set(w.TENANT_ID,tenant).set(w.WAIT_RECEIPT_ID,id(c)).set(w.TASK_OCCURRENCE_ID,task.selector().id()).set(w.TASK_REVISION,revision).set(w.WAIT_SEQUENCE,sequence)
            .set(w.WAIT_REASON_CODE,"ROUTING_REVIEW_WINDOW").set(w.WAIT_CONTRACT_CODE,"R1_ROUTING_REVIEW_WAIT_V1").set(w.WAIT_CONTRACT_VERSION,1)
            .set(w.ENTERED_WAITING_AT,time(now)).set(w.RESUME_DUE_AT,time(due)).set(w.RECORDED_BY_APPOINTMENT_ID,actor).execute();
        return new JooqEventResponsibilityReader().latestWait(c,tenant,task.selector().id()).selector();
    }
    public Subject causalStop(Connection c,UUID tenant,Task task){
        if(task.type()!=Type.ACK_SOURCE_INTAKE_STOP_REQUEST)throw new IllegalArgumentException("ACK Task required");
        var d=DECISION_RECORD;var t=TASK_OCCURRENCE;
        var rows=db(c).select(d.DECISION_RECORD_ID,d.CONTENT_DIGEST).from(d).join(t).on(t.TENANT_ID.eq(d.TENANT_ID)).and(t.TASK_OCCURRENCE_ID.eq(d.TASK_OCCURRENCE_ID))
            .where(d.TENANT_ID.eq(tenant)).and(d.DECISION_CODE.eq("REQUEST_SOURCE_INTAKE_STOP")).and(d.DECISION_CONTRACT_CODE.eq("LEAD_ROUTING_DISPOSITION"))
            .and(d.DECISION_CONTRACT_VERSION.eq(1)).and(d.DECISION_VERSION.eq(1)).and(d.AUTHORITY_SLOT_CODE.eq(Type.RESOLVE_LEAD_ROUTING_GAP.slot))
            .and(d.DECISION_SUBJECT_TYPE.eq(task.lead().type())).and(d.DECISION_SUBJECT_ID.eq(task.lead().id())).and(d.DECISION_SUBJECT_REVISION.eq(task.lead().revision()))
            .and(t.BUSINESS_PURPOSE_CODE.eq(Type.RESOLVE_LEAD_ROUTING_GAP.name())).and(t.PRIMARY_COMMAND_CODE.eq(Type.RESOLVE_LEAD_ROUTING_GAP.command))
            .and(t.EXPECTED_COMPLETION_FACT_TYPE.eq("responsibility.decision_record")).and(t.COMPLETION_FACT_REVISION.isNull())
            .and(d.DECIDED_AT.le(time(task.createdAt()))).and(t.STATE.eq("DONE")).and(t.SUBJECT_TYPE.eq(task.lead().type())).and(t.SUBJECT_ID.eq(task.lead().id())).and(t.SUBJECT_REVISION.eq(task.lead().revision()))
            .and(t.COMPLETION_FACT_TYPE.eq("responsibility.decision_record")).and(t.COMPLETION_FACT_ID.eq(d.DECISION_RECORD_ID)).and(t.COMPLETION_FACT_HASH.eq(d.CONTENT_DIGEST))
            .orderBy(d.DECIDED_AT.desc(),d.DECISION_RECORD_ID.desc()).limit(1).fetchOne();
        return rows==null?null:new Subject("responsibility.decision_record",rows.value1(),null,base64(rows.value2()));
    }
}
