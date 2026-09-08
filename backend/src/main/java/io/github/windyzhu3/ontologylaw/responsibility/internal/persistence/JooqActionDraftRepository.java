package io.github.windyzhu3.ontologylaw.responsibility.internal.persistence;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.sql.*;import java.time.Instant;import java.util.*;
import java.time.ZoneOffset;
import io.github.windyzhu3.ontologylaw.execution.*;
import org.jooq.*;import org.jooq.impl.DSL;
import static io.github.windyzhu3.ontologylaw.responsibility.internal.persistence.jooq.Tables.*;
public final class JooqActionDraftRepository implements ActionDraftService {
    private static DSLContext db(Connection c){return DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false));}
    public Draft read(Connection c,UUID tenant,UUID taskId) {
        var d=ACTION_DRAFT;
        var row=db(c).selectFrom(d).where(d.TENANT_ID.eq(tenant)).and(d.TASK_OCCURRENCE_ID.eq(taskId)).fetchOne();
        if(row==null)return null;
        return new Draft(new io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject("responsibility.action_draft",row.get(d.ACTION_DRAFT_ID),row.get(d.REVISION),null),
                taskId,row.get(d.ACTION_CODE),row.get(d.PAYLOAD_SCHEMA_CODE),row.get(d.PAYLOAD_SCHEMA_VERSION),candidateValues(c,row.get(d.CANDIDATE_PAYLOAD)),
                Base64.getUrlEncoder().withoutPadding().encodeToString(row.get(d.CANDIDATE_PAYLOAD_DIGEST)),row.get(d.STATE),row.get(d.CREATED_AT).toInstant(),row.get(d.LAST_EDITED_AT).toInstant());
    }
    public Saved save(Connection c,UUID tenant,TaskFactory.Task task,Draft expected,Map<String,Object> values,UUID actor,Instant now)throws SQLException {
        var d=ACTION_DRAFT;
        String canonical=CanonicalJson.encode(values);
        byte[] digest=CanonicalJson.digest(canonical);
        var current=read(c,tenant,task.selector().id());
        if(!Objects.equals(current,expected))throw new CommandHandler.Rejected("STALE_DRAFT");
        if(current!=null) {
            if(!"DRAFT".equals(current.state())||!task.type().command.equals(current.actionCode())||!task.type().schema.equals(current.schemaCode())||current.schemaVersion()!=1)
                throw new CommandHandler.Rejected("DRAFT_DIGEST_MISMATCH");
            if(canonical.equals(CanonicalJson.encode(current.values()))&&Arrays.equals(digest,Base64.getUrlDecoder().decode(current.digest())))return new Saved(current,false);
            int changed=db(c).update(d).set(d.CANDIDATE_PAYLOAD,JSONB.valueOf(canonical)).set(d.CANDIDATE_PAYLOAD_DIGEST,digest)
                    .set(d.LAST_EDITED_AT,now.atOffset(ZoneOffset.UTC)).set(d.REVISION,CommandHandler.nextRevision(current.selector().revision()))
                    .where(d.TENANT_ID.eq(tenant)).and(d.TASK_OCCURRENCE_ID.eq(task.selector().id())).and(d.ACTION_DRAFT_ID.eq(current.selector().id()))
                    .and(d.STATE.eq("DRAFT")).and(d.REVISION.eq(current.selector().revision())).execute();
            if(changed!=1)throw new CommandHandler.Rejected("STALE_DRAFT");
        } else {
            UUID id=db(c).select(DSL.field("uuidv7()",UUID.class)).fetchOne(0,UUID.class);
            db(c).insertInto(d).set(d.TENANT_ID,tenant).set(d.ACTION_DRAFT_ID,id).set(d.TASK_OCCURRENCE_ID,task.selector().id())
                    .set(d.ACTION_CODE,task.type().command).set(d.PAYLOAD_SCHEMA_CODE,task.type().schema).set(d.PAYLOAD_SCHEMA_VERSION,1)
                    .set(d.CANDIDATE_PAYLOAD,JSONB.valueOf(canonical)).set(d.CANDIDATE_PAYLOAD_DIGEST,digest).set(d.STATE,"DRAFT")
                    .set(d.CREATED_BY_APPOINTMENT_ID,actor).set(d.CREATED_AT,now.atOffset(ZoneOffset.UTC)).set(d.LAST_EDITED_AT,now.atOffset(ZoneOffset.UTC)).set(d.REVISION,0L).execute();
        }
        return new Saved(read(c,tenant,task.selector().id()),true);
    }
    public boolean exists(Connection c,UUID tenant,UUID task,UUID draft){
        var d=ACTION_DRAFT;return db(c).fetchExists(db(c).selectOne().from(d).where(d.TENANT_ID.eq(tenant)).and(d.TASK_OCCURRENCE_ID.eq(task)).and(d.ACTION_DRAFT_ID.eq(draft)));
    }
    public void validate(Connection c,UUID tenant,TaskFactory.Task task,Confirmation selector,Map<String,Object> values)throws SQLException{
        var d=ACTION_DRAFT;var row=db(c).selectFrom(d).where(d.TENANT_ID.eq(tenant)).and(d.TASK_OCCURRENCE_ID.eq(task.selector().id())).and(d.ACTION_DRAFT_ID.eq(selector.draftId())).fetchOne();
        if(row==null)throw new CommandHandler.Rejected("NOT_FOUND");
        if(row.get(d.REVISION)!=selector.revision())throw new CommandHandler.Rejected("STALE_DRAFT");
        CommandHandler.nextRevision(selector.revision());
        byte[] digest;
        try {digest=Base64.getUrlDecoder().decode(selector.digest());}catch(IllegalArgumentException invalid){throw new CommandHandler.Rejected("DRAFT_DIGEST_MISMATCH");}
        // Frozen candidates are flat string/integer objects. Let PostgreSQL expose typed scalars;
        // domain persistence must not acquire an HTTP/JSON framework or coerce numeric values.
        String candidate=CanonicalJson.encode(candidateValues(c,row.get(d.CANDIDATE_PAYLOAD)));
        if(!"DRAFT".equals(row.get(d.STATE))||!task.type().command.equals(row.get(d.ACTION_CODE))||!task.type().schema.equals(row.get(d.PAYLOAD_SCHEMA_CODE))||row.get(d.PAYLOAD_SCHEMA_VERSION)!=1
                ||!Arrays.equals(digest,row.get(d.CANDIDATE_PAYLOAD_DIGEST))||!Arrays.equals(CanonicalJson.digest(candidate),digest)||!candidate.equals(CanonicalJson.encode(values)))
            throw new CommandHandler.Rejected("DRAFT_DIGEST_MISMATCH");
    }
    private static Map<String,Object> candidateValues(Connection c,JSONB payload) {
        var result=new TreeMap<String,Object>();
        var rows=db(c).fetch("select key, jsonb_typeof(value) as kind, value #>> '{}' as scalar from jsonb_each(?::jsonb)",payload.data());
        for(var row:rows){String kind=row.get("kind",String.class),value=row.get("scalar",String.class);Object parsed;
            if("string".equals(kind))parsed=value;
            else if("number".equals(kind)&&value.matches("-?(0|[1-9][0-9]*)")) {
                try{parsed=Long.valueOf(value);}catch(NumberFormatException invalid){throw new CommandHandler.Rejected("DRAFT_DIGEST_MISMATCH");}
            }else throw new CommandHandler.Rejected("DRAFT_DIGEST_MISMATCH");
            result.put(row.get("key",String.class),parsed);
        }
        return result;
    }
    public void confirm(Connection c,UUID tenant,TaskFactory.Task task,Confirmation selector,Map<String,Object> values,UUID actor,Instant now)throws SQLException{
        validate(c,tenant,task,selector,values);var d=ACTION_DRAFT;
        int changed=db(c).update(d).set(d.STATE,"CONFIRMED").set(d.REVISION,CommandHandler.nextRevision(selector.revision())).set(d.CONFIRMED_BY_APPOINTMENT_ID,actor)
            .set(d.CONFIRMED_AT,now.atOffset(ZoneOffset.UTC)).set(d.CONFIRMED_PAYLOAD_DIGEST,Base64.getUrlDecoder().decode(selector.digest()))
            .where(d.TENANT_ID.eq(tenant)).and(d.ACTION_DRAFT_ID.eq(selector.draftId())).and(d.TASK_OCCURRENCE_ID.eq(task.selector().id())).and(d.STATE.eq("DRAFT")).and(d.REVISION.eq(selector.revision())).execute();
        if(changed!=1)throw new CommandHandler.Rejected("STALE_DRAFT");
    }
}
