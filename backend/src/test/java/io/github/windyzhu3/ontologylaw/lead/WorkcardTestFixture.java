package io.github.windyzhu3.ontologylaw.lead;

import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import io.github.windyzhu3.ontologylaw.testing.PostgresIntegrationTest;
import io.github.windyzhu3.ontologylaw.execution.CanonicalJson;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Only fixture construction/import lives here; all reads use production Owner ports and roles. */
public abstract class WorkcardTestFixture extends PostgresIntegrationTest {
    protected AuthorizationServiceIT.Seed seed;
    protected TaskFactory.Task current;
    protected LeadProtection protection=LeadProtectionTest.protection();
    protected UUID secondaryLead,secondaryParty,secondaryAppointment,secondaryPrincipal,secondaryOrganization;
    protected AuthorizationService.Subject secondaryFact;
    protected R1SourcePolicyRegistry policies=new R1SourcePolicyRegistry(Map.of("FIXTURE",new R1SourcePolicyRegistry.SourcePolicy(
        R1SourcePolicyRegistry.AssignmentMode.MANUAL,List.of("ROOT"),"ROOT","ROOT","Asia/Shanghai")));
    protected void setupCard(TaskFactory.Type type)throws Exception {
        setupCard(type,null);
    }
    protected void setupCard(TaskFactory.Type type,Instant createdAt)throws Exception {
        seed=AuthorizationServiceIT.seed(database,"HUMAN",type.authority);
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            var leads=LeadIngressService.databaseBacked(protection);var tasks=TaskFactory.databaseBacked();var now=createdAt==null?leads.now(x):createdAt;
            var lead=leads.capture(x,seed.tenant(),input(type!=TaskFactory.Type.COMPLETE_LEAD_INGRESS),CanonicalJson.digest(UUID.randomUUID().toString()),now);
            if(type==TaskFactory.Type.RESOLVE_LEAD_DUPLICATE) {
                var candidate=leads.capture(x,seed.tenant(),input(true),CanonicalJson.digest(UUID.randomUUID().toString()),now.minusSeconds(1));
                secondaryLead=candidate.selector().id();secondaryParty=UUID.randomUUID();
                sql(x,"insert into party.party (tenant_id,party_id,party_type,canonical_name,status) values (?,?,'PERSON','候选当事人','ACTIVE')",seed.tenant(),secondaryParty);
                sql(x,"update lead.lead set parsed_party_id=?,party_resolution_code='RESOLVED',revision=revision+1 where tenant_id=? and lead_id=?",secondaryParty,seed.tenant(),secondaryLead);
            }
            if(type==TaskFactory.Type.ASSIGN_LEAD)createSalesCandidate(x);
            if(type==TaskFactory.Type.CONTACT_LEAD||type==TaskFactory.Type.REVIEW_LEAD_VALIDITY) {
                if(type==TaskFactory.Type.REVIEW_LEAD_VALIDITY)grant(x,"SALES_CONTACT_OWNER");
                var assignment=leads.assign(x,seed.tenant(),lead,seed.appointment(),"MANUAL_SELECTION",now);
                lead=leads.update(x,seed.tenant(),lead,null,null,null,seed.appointment(),assignment.selector().id(),now);secondaryFact=assignment.selector();
            }
            if(type==TaskFactory.Type.ACK_SOURCE_INTAKE_STOP_REQUEST) {
                var source=tasks.create(x,seed.tenant(),TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP,seed.appointment(),lead.selector(),ZoneId.of("Asia/Shanghai"),now);
                var values=new TreeMap<String,Object>();values.put("tenantId",seed.tenant().toString());values.put("subject",Map.of("type","lead.lead","id",lead.selector().id().toString(),"revision",lead.selector().revision()));
                values.put("authoritySlot","ROUTING_SUPERVISOR");values.put("decisionCode","REQUEST_SOURCE_INTAKE_STOP");values.put("rationaleSummary","来源待确认");
                secondaryFact=tasks.decision(x,seed.tenant(),source,seed.appointment(),"LEAD_ROUTING_DISPOSITION","REQUEST_SOURCE_INTAKE_STOP","来源待确认",values,now);
                tasks.complete(x,seed.tenant(),source,secondaryFact,now);
            }
            if(type==TaskFactory.Type.REVIEW_LEAD_VALIDITY) {
                var source=tasks.create(x,seed.tenant(),TaskFactory.Type.CONTACT_LEAD,seed.appointment(),lead.selector(),ZoneId.of("Asia/Shanghai"),now);
                UUID result=UUID.randomUUID();sql(x,"insert into lead.lead_contact_result (tenant_id,lead_contact_result_id,lead_id,lead_assignment_id,contact_no,contact_task_id,contact_channel_code,result_code,result_summary,resulted_at,created_at) values (?,?,?,?,1,?,'PHONE','SUSPECT_INVALID','待核实',?,?)",seed.tenant(),result,lead.selector().id(),secondaryFact.id(),source.selector().id(),now.atOffset(ZoneOffset.UTC),now.atOffset(ZoneOffset.UTC));
                secondaryFact=CurrentLeadReader.databaseBacked(protection).contactResult(x,seed.tenant(),result).selector();tasks.complete(x,seed.tenant(),source,secondaryFact,now);
            }
            current=tasks.create(x,seed.tenant(),type,seed.appointment(),lead.selector(),ZoneId.of("Asia/Shanghai"),now.plusNanos(1000));return null;
        });}
    }
    protected Map<String,Object> input(boolean contacts) {
        var result=new TreeMap<String,Object>();result.put("sourceChannelCode","TEST");result.put("sourceAccountCode","FIXTURE");result.put("capturedAt","2026-09-04T00:00:00.000000Z");
        result.put("capturedName","张测试");result.put("serviceCategoryCode","CONSULTATION");result.put("jurisdictionCode","CN");result.put("urgencyCode","NORMAL");result.put("legalNeedSummary","测试咨询");
        if(contacts)result.put("phone","+12025550123");return result;
    }
    protected void grant(Connection x,String authority)throws SQLException {
        sql(x,"insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,?,clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),seed.appointment(),seed.appointment(),seed.org(),authority);
    }
    private void createSalesCandidate(Connection x)throws SQLException {
        secondaryAppointment=UUID.randomUUID();secondaryPrincipal=UUID.randomUUID();secondaryOrganization=UUID.randomUUID();
        sql(x,"insert into identity.organization_unit (tenant_id,organization_unit_id,parent_organization_unit_id,unit_code,display_name,state,created_at) values (?,?,?,'SALES','销售组','ACTIVE',clock_timestamp())",seed.tenant(),secondaryOrganization,seed.org());
        sql(x,"insert into identity.principal (tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) values (?,?,'HUMAN','FIXTURE',?,'李销售','ACTIVE',clock_timestamp())",seed.tenant(),secondaryPrincipal,CanonicalJson.digest(secondaryPrincipal.toString()));
        sql(x,"insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'OWNER',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),secondaryAppointment,secondaryPrincipal,secondaryOrganization);
        sql(x,"insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,'SALES_CONTACT_OWNER',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),secondaryAppointment,seed.appointment(),seed.org());
    }
    protected long auditCount()throws Exception {
        try(var c=database.apiConnection()){return inTransaction(c,Capability.QUERY,x->{try(var p=x.prepareStatement("select count(*) from audit.audit_entry_classified_v where tenant_id=?")){p.setObject(1,seed.tenant());try(var r=p.executeQuery()){r.next();return r.getLong(1);}}});}
    }
    protected void mutate(String query,Object... args)throws Exception {
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{sql(x,query,args);return null;});}
    }
    protected io.github.windyzhu3.ontologylaw.api.CurrentWorkCardDisclosureService.Response readCard(String tag)throws Exception {
        try(var c=database.apiConnection()){return new io.github.windyzhu3.ontologylaw.api.CurrentWorkCardDisclosureService(protection,policies,"WORKCARD_IT").read(c,seed.request().actor(),UUID.randomUUID(),tag);}
    }
    protected ActionDraftService.Draft saveDraft(Map<String,Object> values,boolean confirm)throws Exception {
        try(var c=database.apiConnection()){return inTransaction(c,Capability.COMMAND,x->{
            var service=ActionDraftService.databaseBacked();var now=LeadIngressService.databaseBacked(protection).now(x);
            var existing=service.read(x,seed.tenant(),current.selector().id());
            var saved=service.save(x,seed.tenant(),current,existing,CurrentLeadReader.validatedDraftValues(current.type().command,values),seed.appointment(),now).draft();
            if(confirm)service.confirm(x,seed.tenant(),current,new ActionDraftService.Confirmation(saved.selector().id(),saved.selector().revision(),saved.digest()),saved.values(),seed.appointment(),now);
            return service.read(x,seed.tenant(),current.selector().id());
        });}
    }
    protected void deny(AuthorizationService.Subject subject,String code)throws Exception {
        mutate("insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision,object_subject_hash) values (?,?,?,?,?,'DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),?,?,?,?)",seed.tenant(),UUID.randomUUID(),seed.principal(),seed.appointment(),code,subject.type(),subject.id(),subject.revision(),subject.hash()==null?null:Base64.getUrlDecoder().decode(subject.hash()));
    }
    protected void cancelCurrent()throws Exception {
        mutate("update responsibility.task_occurrence set state='CANCELLED',cancelled_at=clock_timestamp(),cancellation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and task_occurrence_id=?",seed.tenant(),current.selector().id());
    }
    protected TaskFactory.Task addTask(UUID id,Instant created,Instant due,String state)throws Exception {
        try(var c=database.apiConnection()){return inTransaction(c,Capability.COMMAND,x->{
            var lead=LeadIngressService.databaseBacked(protection).capture(x,seed.tenant(),input(false),CanonicalJson.digest(UUID.randomUUID().toString()),created);
            var type="WAITING".equals(state)?TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP:TaskFactory.Type.COMPLETE_LEAD_INGRESS;
            sql(x,"insert into responsibility.task_occurrence (tenant_id,task_occurrence_id,owner_appointment_id,business_purpose_code,primary_command_code,expected_completion_fact_type,original_sla_code,original_sla_seconds,original_sla_due_at,state,created_at,subject_type,subject_id,subject_revision) values (?,?,?,?,?,?,?,?,?,?,?,'lead.lead',?,0)",seed.tenant(),id,seed.appointment(),type.name(),type.command,type.completionType,type.slaCode(),type.slaSeconds(),due.atOffset(ZoneOffset.UTC),"OPEN",created.atOffset(ZoneOffset.UTC),lead.selector().id());
            if("WAITING".equals(state))TaskFactory.databaseBacked().waitUntil(x,seed.tenant(),TaskFactory.databaseBacked().read(x,seed.tenant(),id),seed.appointment(),due.plusSeconds(3600),created);
            return TaskFactory.databaseBacked().read(x,seed.tenant(),id);
        });}
    }
}
