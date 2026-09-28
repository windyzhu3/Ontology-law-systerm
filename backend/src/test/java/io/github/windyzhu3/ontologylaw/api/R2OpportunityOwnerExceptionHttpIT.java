package io.github.windyzhu3.ontologylaw.api;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.opportunity.OpportunityOwnerExceptionService.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
/** Real HTTP/QUERY reads: rights are independent, projections closed, cursors bounded and actor-bound. */
class R2OpportunityOwnerExceptionHttpIT extends R1HttpFixture {
    private Snapshot exception;
    private Subject opportunity;
    private Set<Reason> reasons=Set.of(Reason.OWNER_AUTHORITY_MISSING);
    @org.junit.jupiter.api.BeforeEach void resetObservationReasons(){reasons=Set.of(Reason.OWNER_AUTHORITY_MISSING);}
    private OpportunityOwnerExceptionService observer(){return OpportunityOwnerExceptionService.databaseBacked((c,t,o,r,n)->new Observation(reasons,null,null,null),(c,t,o,r,task,wait)->{throw new java.sql.SQLException("Unused receiver");},(c,t,h,o,r,task,wait,receiver,id,actor,zone)->{throw new java.sql.SQLException("Unused handoff");});}
    private void setupException(String... rights)throws Exception {
        setupContact();var completed=execute(prepare(contact("CONNECTED_VALID")));
        opportunityProtection=OpportunityProgressProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES"));
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{opportunity=EventOpportunityReader.databaseBacked().forContact(x,seed.tenant(),completed.resultFact().id()).selector();for(var right:rights)grant(x,right);exception=observer().observe(x,seed.tenant(),opportunity).orElseThrow();return null;});}
        try(var c=database.apiConnection()){var reads=new R2OpportunityOwnerExceptionReadService(new byte[32],protection,AuditAppender.databaseBacked("EXCEPTION_HTTP_PREFLIGHT"));reads.read(c,seed.request().actor(),List.of(rights).contains("OPPORTUNITY_OWNER_EXCEPTION_READ")?"list":"operations",null,null,20,null);}
    }
    @Test void read_only_supervisor_gets_audited_labels_but_no_candidates_or_commands()throws Exception {
        setupException("OPPORTUNITY_OWNER_EXCEPTION_READ");
        try(var http=new HttpHarness()){
            var response=http.request("GET","/api/v1/opportunity-owner-exceptions/"+exception.selector().id(),null,Map.of());assertEquals(200,response.statusCode(),response.body());
            var body=http.body(response);assertEquals(List.of(),body.get("allowedActions"));assertTrue(body.containsKey("opportunityLabel"));assertTrue(body.containsKey("currentOwnerLabel"));assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());assertTrue(response.headers().firstValue("ETag").orElseThrow().startsWith("\"oe."));
            var candidates=http.request("GET","/api/v1/opportunity-owner-exceptions/"+exception.selector().id()+"/candidates?expectedRevision=0",null,Map.of());assertEquals(403,candidates.statusCode(),candidates.body());
            var limited=http.request("GET","/api/v1/opportunity-owner-exceptions?limit=101",null,Map.of());assertEquals(400,limited.statusCode(),limited.body());
            var forged=http.request("GET","/api/v1/opportunity-owner-exceptions?tenantId="+seed.tenant(),null,Map.of());assertEquals(400,forged.statusCode(),forged.body());
        }
        assertNotEquals("0",scalar("select count(*) from audit.audit_entry_classified_v where tenant_id=? and summary_schema_code='R2_OWNER_EXCEPTION_DISCLOSURE_AUDIT_V1'",seed.tenant()));
    }
    @Test void operations_only_projection_never_contains_business_selectors_labels_or_actions()throws Exception {
        setupException("OPPORTUNITY_OWNER_EXCEPTION_OPERATIONS_READ");
        try(var http=new HttpHarness()){
            var response=http.request("GET","/api/v1/opportunity-owner-exceptions/operations",null,Map.of());assertEquals(200,response.statusCode(),response.body());
            var items=(List<?>)http.body(response).get("items");assertEquals(1,items.size());var row=(Map<?,?>)items.getFirst();assertEquals(Set.of("exceptionId","reasonCodes","state","firstObservedAt","lastObservedAt","organizationLabel","repairGuidance"),row.keySet());
            assertFalse(response.body().contains(opportunity.id().toString()));
            var full=http.request("GET","/api/v1/opportunity-owner-exceptions/"+exception.selector().id(),null,Map.of());assertEquals(403,full.statusCode(),full.body());
        }
    }
    @Test void independent_resolve_denial_keeps_read_visible_and_read_denial_removes_the_row()throws Exception {
        setupException("OPPORTUNITY_OWNER_EXCEPTION_READ","OPPORTUNITY_OWNER_EXCEPTION_RESOLVE");
        deny(exception.selector(),"OPPORTUNITY_OWNER_EXCEPTION_RESOLVE");
        try(var http=new HttpHarness()){
            var detail=http.request("GET","/api/v1/opportunity-owner-exceptions/"+exception.selector().id(),null,Map.of());assertEquals(200,detail.statusCode(),detail.body());assertEquals(List.of(),http.body(detail).get("allowedActions"));
            deny(opportunity,"OPPORTUNITY_OWNER_EXCEPTION_READ");
            var hidden=http.request("GET","/api/v1/opportunity-owner-exceptions",null,Map.of());assertEquals(200,hidden.statusCode(),hidden.body());assertEquals(List.of(),http.body(hidden).get("items"));
            var unavailable=http.request("GET","/api/v1/opportunity-owner-exceptions/"+exception.selector().id(),null,Map.of());assertEquals(403,unavailable.statusCode(),unavailable.body());
        }
    }
    @Test void source_inconsistent_exception_exposes_coordination_only_and_no_transfer_candidates()throws Exception {
        reasons=Set.of(Reason.SOURCE_INCONSISTENT);setupException("OPPORTUNITY_OWNER_EXCEPTION_READ","OPPORTUNITY_OWNER_EXCEPTION_RESOLVE");
        try(var http=new HttpHarness()){
            var response=http.request("GET","/api/v1/opportunity-owner-exceptions/"+exception.selector().id(),null,Map.of());assertEquals(200,response.statusCode(),response.body());assertEquals(List.of("COORDINATE"),http.body(response).get("allowedActions"));
            var candidates=http.request("GET","/api/v1/opportunity-owner-exceptions/"+exception.selector().id()+"/candidates?expectedRevision=0",null,Map.of());assertEquals(200,candidates.statusCode(),candidates.body());assertEquals(List.of(),http.body(candidates).get("items"));
        }
    }
    @Test void candidates_use_current_receiver_authority_and_exact_exception_revision()throws Exception {
        setupException("OPPORTUNITY_OWNER_EXCEPTION_READ","OPPORTUNITY_OWNER_EXCEPTION_RESOLVE");
        UUID principal=UUID.randomUUID(),appointment=UUID.randomUUID();
        mutate("insert into identity.principal (tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) values (?,?,'HUMAN','FIXTURE',?,'Eligible Receiver','ACTIVE',clock_timestamp())",seed.tenant(),principal,io.github.windyzhu3.ontologylaw.execution.CanonicalJson.digest(principal.toString()));
        mutate("insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'OWNER',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),appointment,principal,seed.org());
        mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,'SALES_OPPORTUNITY_OWNER',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),appointment,seed.appointment(),seed.org());
        try(var http=new HttpHarness()){
            String path="/api/v1/opportunity-owner-exceptions/"+exception.selector().id()+"/candidates?expectedRevision=0";
            var first=http.request("GET",path,null,Map.of());assertEquals(200,first.statusCode(),first.body());var candidates=(List<?>)http.body(first).get("items");assertEquals(1,candidates.size());assertEquals(appointment.toString(),((Map<?,?>)candidates.getFirst()).get("appointmentId"));assertEquals("Eligible Receiver",((Map<?,?>)candidates.getFirst()).get("displayName"));
            mutate("insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values (?,?,?,?,'SALES_OPPORTUNITY_OWNER','DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),?,?,?)",seed.tenant(),UUID.randomUUID(),principal,seed.appointment(),opportunity.type(),opportunity.id(),opportunity.revision());
            var denied=http.request("GET",path,null,Map.of());assertEquals(200,denied.statusCode(),denied.body());assertEquals(List.of(),http.body(denied).get("items"));
            try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->observer().observe(x,seed.tenant(),opportunity));}
            var stale=http.request("GET",path,null,Map.of());assertEquals(412,stale.statusCode(),stale.body());
        }
    }
    @Test void denied_rows_advance_cursor_and_tampered_cursors_fail()throws Exception {
        setupException("OPPORTUNITY_OWNER_EXCEPTION_READ","OPPORTUNITY_OWNER_EXCEPTION_RESOLVE");
        try(var http=new HttpHarness()){
            var first=http.request("GET","/api/v1/opportunity-owner-exceptions?limit=1",null,Map.of());assertEquals(200,first.statusCode(),first.body());String cursor=(String)http.body(first).get("nextCursor");assertNotNull(cursor);
            deny(opportunity,"OPPORTUNITY_OWNER_EXCEPTION_READ");var denied=http.request("GET","/api/v1/opportunity-owner-exceptions?limit=1",null,Map.of());assertEquals(200,denied.statusCode(),denied.body());assertEquals(List.of(),http.body(denied).get("items"));assertNotNull(http.body(denied).get("nextCursor"));
            var next=http.request("GET","/api/v1/opportunity-owner-exceptions?limit=1&cursor="+java.net.URLEncoder.encode(cursor,java.nio.charset.StandardCharsets.UTF_8),null,Map.of());assertEquals(200,next.statusCode(),next.body());assertEquals(List.of(),http.body(next).get("items"));
            var malformed=http.request("GET","/api/v1/opportunity-owner-exceptions?cursor="+cursor+"x",null,Map.of());assertEquals(400,malformed.statusCode(),malformed.body());
        }
    }
}
