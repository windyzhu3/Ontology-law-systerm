package io.github.windyzhu3.ontologylaw.lead.internal.persistence;

import io.github.windyzhu3.ontologylaw.lead.*;
import io.github.windyzhu3.ontologylaw.execution.CommandHandler;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.jooq.*;
import org.jooq.impl.DSL;
import static io.github.windyzhu3.ontologylaw.lead.internal.persistence.jooq.Tables.LEAD_CONTACT_RESULT;

public final class JooqContactResultRepository implements ContactResultService {
    private static DSLContext db(Connection c){return DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false));}
    public long nextNumber(Connection c,UUID tenant,UUID lead)throws SQLException {
        var f=LEAD_CONTACT_RESULT;
        long current=db(c).select(DSL.coalesce(DSL.max(f.CONTACT_NO),0L)).from(f).where(f.TENANT_ID.eq(tenant)).and(f.LEAD_ID.eq(lead)).fetchOne(0,Long.class);
        return CommandHandler.nextRevision(current);
    }
    public CurrentLeadReader.ContactResult latest(Connection c,UUID tenant,UUID lead){
        var f=LEAD_CONTACT_RESULT;var r=db(c).selectFrom(f).where(f.TENANT_ID.eq(tenant)).and(f.LEAD_ID.eq(lead)).orderBy(f.CONTACT_NO.desc()).limit(1).fetchOne();
        return r==null?null:new CurrentLeadReader.ContactResult(JooqR1EventFacts.contactSelector(tenant,r.get(f.LEAD_CONTACT_RESULT_ID),r),lead,r.get(f.LEAD_ASSIGNMENT_ID),r.get(f.CONTACT_TASK_ID),r.get(f.CONTACT_NO),r.get(f.CONTACT_CHANNEL_CODE),r.get(f.RESULT_CODE),r.get(f.RESULT_SUMMARY),r.get(f.EVIDENCE_SUBMISSION_ID),r.get(f.RESULTED_AT).toInstant());
    }
    public Subject append(Connection c,UUID tenant,UUID lead,UUID assignment,UUID task,long number,String channel,String code,String summary,UUID evidence,Instant now)throws SQLException {
        if(number!=nextNumber(c,tenant,lead))throw new CommandHandler.Rejected("STALE_SUBJECT");
        var f=LEAD_CONTACT_RESULT;UUID id=db(c).select(DSL.field("uuidv7()",UUID.class)).fetchOne(0,UUID.class);
        var r=db(c).insertInto(f).set(f.TENANT_ID,tenant).set(f.LEAD_CONTACT_RESULT_ID,id).set(f.LEAD_ID,lead).set(f.LEAD_ASSIGNMENT_ID,assignment)
            .set(f.CONTACT_TASK_ID,task).set(f.CONTACT_NO,number).set(f.CONTACT_CHANNEL_CODE,channel).set(f.RESULT_CODE,code).set(f.RESULT_SUMMARY,summary)
            .set(f.EVIDENCE_SUBMISSION_ID,evidence).set(f.RESULTED_AT,now.atOffset(ZoneOffset.UTC)).set(f.CREATED_AT,now.atOffset(ZoneOffset.UTC)).returning().fetchOne();
        return JooqR1EventFacts.contactSelector(tenant,id,r);
    }
}
