package io.github.windyzhu3.ontologylaw.lead.internal.persistence;

import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import io.github.windyzhu3.ontologylaw.opportunity.EventOpportunityReader;
import io.github.windyzhu3.ontologylaw.responsibility.EventResponsibilityReader;
import java.sql.*;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import static io.github.windyzhu3.ontologylaw.lead.internal.persistence.jooq.Tables.*;

public final class JooqR1EventFacts implements R1EventFacts {
    public LeadAnchor leadAnchor(Connection c,UUID tenant,UUID id){
        var l=LEAD_;var r=DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false)).select(l.REVISION,l.SOURCE_ACCOUNT_CODE,l.CURRENT_ASSIGNMENT_ID).from(l).where(l.TENANT_ID.eq(tenant)).and(l.LEAD_ID.eq(id)).fetchOne();
        return r==null?null:new LeadAnchor(new Subject("lead.lead",id,r.value1(),null),r.value2(),r.value3());
    }
    public ContactAnchor contactAnchor(Connection c,UUID tenant,UUID id){
        var f=LEAD_CONTACT_RESULT;var r=DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false)).select(f.LEAD_ID,f.LEAD_ASSIGNMENT_ID,f.CONTACT_TASK_ID).from(f).where(f.TENANT_ID.eq(tenant)).and(f.LEAD_CONTACT_RESULT_ID.eq(id)).fetchOne();
        return r==null?null:new ContactAnchor(r.value1(),r.value2(),r.value3());
    }
    public Opportunity opportunity(Connection c,UUID tenant,UUID id)throws SQLException{
        var o=opportunities.byId(c,tenant,id);return o==null?null:new Opportunity(o.selector(),o.leadId(),o.assignmentId(),o.contactId(),o.owner());
    }
    public Set<UUID> retainedAssignmentOwners(Connection c,UUID tenant) {
        var a=LEAD_ASSIGNMENT;
        return new HashSet<>(DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false))
            .selectDistinct(a.OWNER_APPOINTMENT_ID).from(a).where(a.TENANT_ID.eq(tenant)).fetch(a.OWNER_APPOINTMENT_ID));
    }
    private final EventResponsibilityReader responsibility;
    private final EventOpportunityReader opportunities;
    public JooqR1EventFacts(EventResponsibilityReader responsibility,EventOpportunityReader opportunities){this.responsibility=responsibility;this.opportunities=opportunities;}
    public Subject lead(Connection c,UUID tenant,UUID id) {
        var l=LEAD_;var r=DSL.using(c,SQLDialect.POSTGRES).select(l.REVISION).from(l).where(l.TENANT_ID.eq(tenant)).and(l.LEAD_ID.eq(id)).fetchOne();
        return r==null?null:new Subject("lead.lead",id,r.value1(),null);
    }
    public Subject capturedLead(Connection c,UUID tenant,String account,String digest) {
        var l=LEAD_;var r=DSL.using(c,SQLDialect.POSTGRES).select(l.LEAD_ID,l.REVISION).from(l).where(l.TENANT_ID.eq(tenant))
                .and(l.SOURCE_ACCOUNT_CODE.eq(account)).and(l.SOURCE_RECORD_KEY_DIGEST.eq(Base64.getUrlDecoder().decode(digest))).fetchOne();
        return r==null?null:new Subject("lead.lead",r.value1(),r.value2(),null);
    }
    public Contact contact(Connection c,UUID tenant,UUID id) {
        var f=LEAD_CONTACT_RESULT;
        var r=DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false)).selectFrom(f).where(f.TENANT_ID.eq(tenant)).and(f.LEAD_CONTACT_RESULT_ID.eq(id)).fetchOne();
        if(r==null)return null;
        return new Contact(contactSelector(tenant,id,r),r.get(f.LEAD_ID),r.get(f.LEAD_ASSIGNMENT_ID),r.get(f.CONTACT_TASK_ID),r.get(f.CONTACT_NO),r.get(f.RESULT_CODE));
    }
    public Subject reviewTrigger(Connection c,UUID tenant,UUID reviewTaskId)throws SQLException{
        var task=io.github.windyzhu3.ontologylaw.responsibility.CurrentTaskReader.databaseBacked().read(c,tenant,reviewTaskId);
        if(task==null||task.type()!=io.github.windyzhu3.ontologylaw.responsibility.TaskFactory.Type.REVIEW_LEAD_VALIDITY)return null;
        var result=io.github.windyzhu3.ontologylaw.lead.ContactCausality.trigger(c,tenant,task.lead().id(),task.createdAt(),(connection,t,id)->{
            var f=LEAD_CONTACT_RESULT;var r=DSL.using(connection,SQLDialect.POSTGRES).selectFrom(f).where(f.TENANT_ID.eq(t)).and(f.LEAD_CONTACT_RESULT_ID.eq(id)).fetchOne();
            return r==null?null:new io.github.windyzhu3.ontologylaw.lead.CurrentLeadReader.ContactResult(contactSelector(t,id,r),r.get(f.LEAD_ID),r.get(f.LEAD_ASSIGNMENT_ID),r.get(f.CONTACT_TASK_ID),r.get(f.CONTACT_NO),r.get(f.CONTACT_CHANNEL_CODE),r.get(f.RESULT_CODE),r.get(f.RESULT_SUMMARY),r.get(f.EVIDENCE_SUBMISSION_ID),r.get(f.RESULTED_AT).toInstant());
        });return result==null?null:result.selector();
    }
    static Subject contactSelector(UUID tenant,UUID id,org.jooq.Record r) {
        var f=LEAD_CONTACT_RESULT;
        var fields=new TreeMap<String,Object>();
        fields.put("tenantId",tenant.toString());fields.put("lead_contact_result_id",id.toString());fields.put("lead_id",r.get(f.LEAD_ID).toString());
        fields.put("lead_assignment_id",r.get(f.LEAD_ASSIGNMENT_ID).toString());fields.put("contact_no",r.get(f.CONTACT_NO));fields.put("contact_task_id",r.get(f.CONTACT_TASK_ID).toString());
        fields.put("contact_channel_code",r.get(f.CONTACT_CHANNEL_CODE));fields.put("result_code",r.get(f.RESULT_CODE));fields.put("result_summary",r.get(f.RESULT_SUMMARY));
        fields.put("evidence_submission_id",r.get(f.EVIDENCE_SUBMISSION_ID)==null?null:r.get(f.EVIDENCE_SUBMISSION_ID).toString());
        var timestamp=new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
        fields.put("resulted_at",timestamp.format(r.get(f.RESULTED_AT).toInstant()));fields.put("created_at",timestamp.format(r.get(f.CREATED_AT).toInstant()));
        return new Subject("lead.lead_contact_result",id,null,Base64.getUrlEncoder().withoutPadding().encodeToString(CanonicalJson.digest(CanonicalJson.encode(fields))));
    }
    public boolean contactExistsForTask(Connection c,UUID tenant,UUID taskId) {
        var f=LEAD_CONTACT_RESULT;
        return DSL.using(c,SQLDialect.POSTGRES).fetchExists(DSL.selectOne().from(f)
                .where(f.TENANT_ID.eq(tenant)).and(f.CONTACT_TASK_ID.eq(taskId)));
    }
    public Assignment assignment(Connection c,UUID tenant,UUID id) {
        var a=LEAD_ASSIGNMENT;var r=DSL.using(c,SQLDialect.POSTGRES).select(a.REVISION,a.LEAD_ID,a.OWNER_APPOINTMENT_ID).from(a).where(a.TENANT_ID.eq(tenant)).and(a.LEAD_ASSIGNMENT_ID.eq(id)).fetchOne();
        return r==null?null:new Assignment(new Subject("lead.lead_assignment",id,r.value1(),null),r.value2(),r.value3());
    }
    public Task task(Connection c,UUID tenant,UUID id)throws SQLException{return responsibility.task(c,tenant,id);}
    public Decision decision(Connection c,UUID tenant,UUID id)throws SQLException{return responsibility.decision(c,tenant,id);}
    public Wait latestWait(Connection c,UUID tenant,UUID id)throws SQLException{return responsibility.latestWait(c,tenant,id);}
    public Opportunity opportunityForContact(Connection c,UUID tenant,UUID id)throws SQLException {
        var o=opportunities.forContact(c,tenant,id);
        return o==null?null:new Opportunity(o.selector(),o.leadId(),o.assignmentId(),o.contactId(),o.owner());
    }
}
