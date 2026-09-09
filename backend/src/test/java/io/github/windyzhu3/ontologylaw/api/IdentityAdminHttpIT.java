package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.OntologyLawApplication;
import io.github.windyzhu3.ontologylaw.testing.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.api.security.*;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import io.github.windyzhu3.ontologylaw.execution.IdentityBootstrapRuntime;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import java.net.*;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real OIDC, offline founder, PostgreSQL capability roles and real HTTP. */
class IdentityAdminHttpIT extends PostgresIntegrationTest {
    KeycloakFixture idp;
    ConfigurableApplicationContext app;
    KeycloakFixture.Login login;
    UUID tenant=UUID.randomUUID();
    UUID root;
    UUID ownAppointment;
    final byte[] subjectKey=key(), candidateKey=key(), tagKey=key(), cursorKey=key();
    final AuditAppender audit=AuditAppender.databaseBacked("TASK93_HTTP_IT");
    KeycloakDirectoryReader directory;
    @BeforeAll void startIdentity() throws Exception {
        var users=new ArrayList<Map<String,Object>>();
        users.add(Map.of("username","aaa-collision","enabled",true,"firstName","Shared name"));
        users.add(Map.of("username","bbb-collision","enabled",true,"firstName","aaa-collision"));
        users.add(Map.of("username","ccc-shared","enabled",true,"firstName","Shared name"));
        users.add(Map.of("username","email-one","enabled",true,"email","shared@example.invalid","firstName","Email twins"));
        users.add(Map.of("username","email-two","enabled",true,"email","shared@example.invalid","firstName","Email twins"));
        users.add(Map.of("username","zz-overflow","enabled",true));
        for(int i=0;i<51;i++)users.add(Map.of("username",String.format("proof-%02d",i),"enabled",true,"firstName","zz-overflow"));
        idp=new KeycloakFixture().withUsers(users).withDuplicateEmails().start();
        login=idp.login();
        directory=KeycloakDirectoryReader.isolatedLoopback(new KeycloakDirectoryReader.Trust(idp.issuer(),"task92-directory",idp.directorySecret));
        var subjects=new ExternalSubjectProtection(t->subjectKey);
        var offline=new BootstrapCandidateProtection("offline-test",Map.of("offline-test",key()));
        var binding=new BootstrapCandidateProtection.Binding("Synthetic approved operator","TASK93","TASK92",idp.issuer());
        String selector=offline.issue(binding,idp.username,directory,Instant.now());
        var manifest=new IdentityBootstrapService.Manifest("R1_IDENTITY_BOOTSTRAP_V1",UUID.randomUUID(),"TASK93","Synthetic administration","ROOT","Root","TASK92",idp.issuer(),selector,"Founder",Instant.now().minusSeconds(60),binding.operatorAssertion());
        try(var c=database.apiConnection()){new IdentityBootstrapRuntime(tenant,binding,offline,subjects,directory,audit).run(c,manifest,false);}
        try(var c=database.migratorConnection();var p=c.prepareStatement("select organization_unit_id from identity.organization_unit where tenant_id=?")){p.setObject(1,tenant);try(var r=p.executeQuery()){r.next();root=r.getObject(1,UUID.class);}}
        try(var c=database.migratorConnection();var p=c.prepareStatement("select appointment_id from identity.appointment where tenant_id=?")){p.setObject(1,tenant);try(var r=p.executeQuery()){r.next();ownAppointment=r.getObject(1,UUID.class);}}
        var verifier=HumanCredentialVerifier.isolatedLoopback(List.of(new HumanCredentialVerifier.Trust(idp.issuer(),KeycloakFixture.AUDIENCE,"TASK92",tenant,KeycloakFixture.AUDIENCE,idp.introspectionSecret)));
        var resolver=new ActorContextResolver(database::apiConnection,subjects,verifier);
        app=new SpringApplicationBuilder(OntologyLawApplication.class).initializers(c->{var registry=(GenericApplicationContext)c;registry.registerBean(ActorContextResolver.class,()->resolver);registerAdmin(registry);})
                .properties("ols.runtime-role=api","server.port=0","spring.main.banner-mode=off","logging.level.root=OFF").run();
    }
    void registerAdmin(GenericApplicationContext registry) {registry.registerBean(IdentityAdminController.Services.class,()->new IdentityAdminController.Services(database::apiConnection,audit,new io.github.windyzhu3.ontologylaw.execution.IdentityResourceProtection(tagKey,cursorKey),new IdentityCandidateProtection("online-test",Map.of("online-test",candidateKey)),new ExternalSubjectProtection(t->subjectKey),"TASK92",directory));}
    @AfterAll void stopIdentity(){if(app!=null)app.close();if(idp!=null)idp.close();}
    @Test void create_child_organization_returns_exact_fact_and_atomic_metadata_without_business_events() throws Exception {
        // Break caught: missing/unauthorized controller or a write that bypasses Slot/Receipt/Audit.
        long before=count("audit.audit_entry"),slots=count("execution.command_execution_slot"),receipts=count("execution.command_receipt");
        var response=request("POST","organizations",Map.of("parentOrganizationId",root.toString(),"code","HTTP_CHILD","displayName","Child"),Map.of("Idempotency-Key",UUID.randomUUID().toString()));
        assertEquals(201,response.statusCode(),response.body());
        var body=json(response.body());assertEquals("SUCCEEDED",body.path("outcome").asString());assertEquals("ORGANIZATION_UNIT",body.path("resultFact").path("factType").asString());
        assertEquals(0,body.path("resultFact").path("revision").asLong());
        assertTrue(response.headers().firstValue("ETag").orElseThrow().matches("\"identity\\.[A-Za-z0-9_-]{43}\""));
        assertEquals(before+1,count("audit.audit_entry"));assertEquals(slots+1,count("execution.command_execution_slot"));assertEquals(receipts+1,count("execution.command_receipt"));
        for(String table:List.of("responsibility.task_occurrence","responsibility.action_draft","execution.domain_event","execution.domain_event_outbox"))assertEquals(0,count(table));
    }
    @Test void success_and_replay_keep_original_receipt_location()throws Exception {
        // Break caught: a successful Identity write redirects Location to its result Fact instead of the original CommandReceipt.
        String key=UUID.randomUUID().toString();var headers=Map.of("Idempotency-Key",key);var body=Map.of("parentOrganizationId",root.toString(),"code","RECEIPT_LOCATION","displayName","Receipt location");
        long facts=count("identity.organization_unit"),slots=count("execution.command_execution_slot"),receipts=count("execution.command_receipt"),audits=count("audit.audit_entry");
        var first=request("POST","organizations",body,headers);assertEquals(201,first.statusCode(),first.body());assertReceiptLocation(first,key);String result=factId(first);
        assertEquals(List.of(facts+1,slots+1,receipts+1,audits+1),List.of(count("identity.organization_unit"),count("execution.command_execution_slot"),count("execution.command_receipt"),count("audit.audit_entry")));
        var replay=request("POST","organizations",body,headers);assertEquals(201,replay.statusCode(),replay.body());assertReceiptLocation(replay,key);
        assertEquals(first.headers().firstValue("Location"),replay.headers().firstValue("Location"));assertEquals(json(first.body()),json(replay.body()));assertEquals(result,factId(replay));
        assertEquals(List.of(facts+1,slots+1,receipts+1,audits+1),List.of(count("identity.organization_unit"),count("execution.command_execution_slot"),count("execution.command_receipt"),count("audit.audit_entry")));
    }
    @Test void orphan_permanent_slot_with_same_key_and_different_scope_fails_closed_over_http()throws Exception {
        UUID command=UUID.randomUUID();
        try(var c=database.adminConnection()) {
            io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql(c,"insert into execution.command_execution_slot (tenant_id,command_execution_slot_id,command_id,envelope_type,command_type,command_scope_digest,payload_digest,occupied_at) values (?,?,?,'INTERNAL_ADMIN','CREATE_ORGANIZATION_UNIT',?,?,clock_timestamp())",tenant,UUID.randomUUID(),command,key(),key());
        }
        var before=List.of(count("identity.organization_unit"),count("execution.command_execution_slot"),count("execution.command_receipt"),count("audit.audit_entry"));
        var response=request("POST","organizations",Map.of("parentOrganizationId",root.toString(),"code","ORPHAN_SLOT","displayName","Must not create"),Map.of("Idempotency-Key",command.toString()));
        assertEquals(503,response.statusCode(),response.body());
        assertEquals("SERVICE_UNAVAILABLE",json(response.body()).path("code").asString());
        assertEquals(before,List.of(count("identity.organization_unit"),count("execution.command_execution_slot"),count("execution.command_receipt"),count("audit.audit_entry")));
    }
    @Test void known_denied_grantee_is_forbidden_with_closed_http_problem_and_no_command_delta()throws Exception {
        UUID grantee=validBusinessDelegation();
        try(var c=database.adminConnection()) {
            io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql(c,"insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) select ?,?,principal_id,?,'IDENTITY_AUTHORITY_MANAGE','DENY',clock_timestamp()-interval '1 hour','ACTIVE',clock_timestamp(),'identity.appointment',?,0 from identity.appointment where tenant_id=? and appointment_id=?",tenant,UUID.randomUUID(),ownAppointment,grantee,tenant,ownAppointment);
        }
        var body=new LinkedHashMap<String,Object>();body.put("appointmentId",grantee.toString());body.put("scopeOrganizationId",root.toString());body.put("authorityCode","SALES_CONTACT_OWNER");body.put("validFrom",Instant.now().toString());body.put("validUntil",null);
        var before=List.of(count("identity.authority_grant"),count("execution.command_execution_slot"),count("execution.command_receipt"),count("audit.audit_entry"));
        var response=request("POST","authority-grants",body,Map.of("Idempotency-Key",UUID.randomUUID().toString()));
        assertEquals(403,response.statusCode(),response.body());assertEquals("NOT_AUTHORIZED",json(response.body()).path("code").asString());
        assertTrue(response.headers().firstValue("ETag").isEmpty());assertFalse(response.body().contains("receiptRef"));
        assertEquals(before,List.of(count("identity.authority_grant"),count("execution.command_execution_slot"),count("execution.command_receipt"),count("audit.audit_entry")));
    }
    @Test void different_usernames_with_shared_email_remain_distinct_exact_candidates()throws Exception {
        var protection=new IdentityCandidateProtection("online-test",Map.of("online-test",candidateKey));
        UUID principal;try(var c=database.migratorConnection();var p=c.prepareStatement("select principal_id from identity.appointment where tenant_id=? and appointment_id=?")){p.setObject(1,tenant);p.setObject(2,ownAppointment);try(var r=p.executeQuery()){r.next();principal=r.getObject(1,UUID.class);}}
        var actor=new AuthorizationService.Actor(tenant,principal,ownAppointment,null,null);
        var subjects=new HashSet<String>();
        for(String username:List.of("email-one","email-two")) {
            var response=request("GET","provider-users?search="+username,null,Map.of());assertEquals(200,response.statusCode(),response.body());
            var page=json(response.body());assertEquals(1,page.path("items").size());assertTrue(page.path("nextCursor").isNull());
            String subject=protection.verify(page.path("items").get(0).path("selector").asString(),actor,"TASK92",idp.issuer()).subject();
            assertEquals(directory.candidate(username).subject(),subject);assertTrue(subjects.add(subject));
        }
        for(String search:List.of("shared%40example.invalid","Email%20twins")) {
            var response=request("GET","provider-users?search="+search,null,Map.of());assertEquals(200,response.statusCode(),response.body());assertEquals(0,json(response.body()).path("items").size());
        }
    }
    @Test void exact_provider_search_is_audited_and_never_issues_another_page() throws Exception {
        var response=request("GET","provider-users?search="+idp.username,null,Map.of());
        assertEquals(200,response.statusCode(),response.body());var body=json(response.body());
        assertEquals(1,body.path("items").size());assertTrue(body.path("nextCursor").isNull());
        assertFalse(response.body().contains(login.subject()));assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
    }
    @Test void missing_and_stale_identity_tags_have_exact_authorized_headers_and_preserve_original_receipt_on_replay()throws Exception {
        var created=create("organizations",Map.of("parentOrganizationId",root.toString(),"code","HTTP_CAS","displayName","CAS"));String id=factId(created),path="organizations/"+id+"/display-name",tag=etag(created);var body=Map.of("displayName","Changed");
        long slots=count("execution.command_execution_slot");var missing=request("PATCH",path,body,Map.of("Idempotency-Key",UUID.randomUUID().toString()));assertEquals(428,missing.statusCode(),missing.body());assertEquals(tag,json(missing.body()).path("currentETag").asString());assertEquals(slots,count("execution.command_execution_slot"));
        var changed=write("PATCH",path,body,tag,200);String key=UUID.randomUUID().toString();var headers=Map.of("Idempotency-Key",key,"If-Match",tag);var stale=request("PATCH",path,body,headers);assertEquals(412,stale.statusCode(),stale.body());assertEquals(etag(changed),json(stale.body()).path("currentETag").asString());
        long receipts=count("execution.command_receipt"),audits=count("audit.audit_entry");var replay=request("PATCH",path,body,headers);assertEquals(412,replay.statusCode(),replay.body());assertEquals(json(stale.body()).path("currentETag"),json(replay.body()).path("currentETag"));assertEquals(receipts,count("execution.command_receipt"));assertEquals(audits,count("audit.audit_entry"));
        var invalid=request("PATCH",path,Map.of("displayName","No","parentOrganizationId",root.toString()),Map.of("Idempotency-Key",UUID.randomUUID().toString(),"If-Match",etag(changed)));assertEquals(400,invalid.statusCode(),invalid.body());assertEquals(receipts,count("execution.command_receipt"));
    }
    @Test void exact_provider_proof_handles_name_collision_and_fails_closed_when_bounded_proof_is_indeterminate()throws Exception {
        var collision=request("GET","provider-users?search=aaa-collision",null,Map.of());assertEquals(200,collision.statusCode(),collision.body());assertEquals(1,json(collision.body()).path("items").size());
        for(String search:List.of("no-such-account","synthetic-disabled","service-account-task92-directory","Shared%20name","%2A","synthetic%40example.invalid")){var empty=request("GET","provider-users?search="+search,null,Map.of());assertEquals(200,empty.statusCode(),empty.body());assertEquals(0,json(empty.body()).path("items").size());}
        long before=count("audit.audit_entry");assertEquals(503,request("GET","provider-users?search=zz-overflow",null,Map.of()).statusCode());assertEquals(before,count("audit.audit_entry"));
        assertEquals(400,request("GET","provider-users?search=aaa-collision&cursor=unused",null,Map.of()).statusCode());
    }
    @Test void all_fourteen_mutations_execute_over_http_with_current_tags_and_all_six_reads_project_only_safe_fields()throws Exception {
        var lookup=request("GET","provider-users?search=aaa-collision",null,Map.of());String selector=json(lookup.body()).path("items").get(0).path("selector").asString();
        var principal=create("principals",Map.of("providerUserSelector",selector,"displayName","Managed user"));String principalId=factId(principal);String principalTag=etag(principal);
        principalTag=etag(write("PATCH","principals/"+principalId+"/display-name",Map.of("displayName","Renamed user"),principalTag,200));
        principalTag=etag(write("POST","principals/"+principalId+"/suspend",reason(),principalTag,200));
        principalTag=etag(write("POST","principals/"+principalId+"/resume",reason(),principalTag,200));
        var organization=create("organizations",Map.of("parentOrganizationId",root.toString(),"code","WORKFLOW","displayName","Workflow"));String org=factId(organization),orgTag=etag(organization);
        orgTag=etag(write("PATCH","organizations/"+org+"/display-name",Map.of("displayName","Renamed org"),orgTag,200));
        var appBody=new LinkedHashMap<String,Object>();appBody.put("principalId",principalId);appBody.put("organizationId",org);appBody.put("roleCode","CONTACT_OPERATOR");appBody.put("effectiveFrom",Instant.now().minusSeconds(60).toString());appBody.put("effectiveUntil",null);
        var appointment=create("appointments",appBody);String appointmentId=factId(appointment),appTag=etag(appointment);
        appTag=etag(write("POST","appointments/"+appointmentId+"/suspend",reason(),appTag,200));appTag=etag(write("POST","appointments/"+appointmentId+"/resume",reason(),appTag,200));
        var grantBody=new LinkedHashMap<String,Object>();grantBody.put("appointmentId",appointmentId);grantBody.put("scopeOrganizationId",org);grantBody.put("authorityCode","SALES_CONTACT_OWNER");grantBody.put("validFrom",Instant.now().minusSeconds(30).toString());grantBody.put("validUntil",null);
        var grant=create("authority-grants",grantBody);write("POST","authority-grants/"+factId(grant)+"/revoke",reason(),etag(grant),200);
        for(String path:List.of("principals","organizations","appointments","authority-grants","options?page=APPOINTMENTS&optionKind=PRINCIPAL")){var read=request("GET",path,null,Map.of());assertEquals(200,read.statusCode(),path+read.body());assertEquals("no-store",read.headers().firstValue("Cache-Control").orElseThrow());assertFalse(read.body().contains("externalSubject"));}
        // Refresh tags after other authorization-dependent facts changed.
        appTag=findTag("appointments",appointmentId);write("POST","appointments/"+appointmentId+"/end",reason(),appTag,200);
        principalTag=findTag("principals",principalId);write("POST","principals/"+principalId+"/disable",reason(),principalTag,200);
        orgTag=findTag("organizations",org);write("POST","organizations/"+org+"/close",reason(),orgTag,200);
    }
    @Test void every_management_operation_rejects_supplied_on_behalf_without_any_command_or_disclosure_delta()throws Exception {
        UUID represented=validBusinessDelegation();
        var paths=List.of("GET principals","GET organizations","GET appointments","GET authority-grants","GET provider-users?search=synthetic-user","GET options?page=APPOINTMENTS&optionKind=PRINCIPAL","POST principals","PATCH principals/ID/display-name","POST principals/ID/suspend","POST principals/ID/resume","POST principals/ID/disable","POST organizations","PATCH organizations/ID/display-name","POST organizations/ID/close","POST appointments","POST appointments/ID/suspend","POST appointments/ID/resume","POST appointments/ID/end","POST authority-grants","POST authority-grants/ID/revoke");
        var before=List.of(count("execution.command_execution_slot"),count("execution.command_receipt"),count("audit.audit_entry"));
        for(String operation:paths){var fields=operation.split(" ",2);var response=request(fields[0],fields[1].replace("ID",root.toString()),fields[0].equals("GET")?null:Map.of(),Map.of("X-Appointment-Id",ownAppointment.toString(),"X-On-Behalf-Appointment-Id",represented.toString(),"Idempotency-Key",UUID.randomUUID().toString()));assertEquals(403,response.statusCode(),operation+response.body());}
        assertEquals(before,List.of(count("execution.command_execution_slot"),count("execution.command_receipt"),count("audit.audit_entry")));
    }
    @Test void real_authenticated_user_without_management_grants_is_refused_by_reads_and_valid_write()throws Exception {
        try(var c=database.adminConnection()){io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql(c,"update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and grantee_appointment_id=? and state='ACTIVE'",tenant,ownAppointment);}
        try {
            long before=count("audit.audit_entry"),slots=count("execution.command_execution_slot");for(String path:List.of("principals","organizations","appointments","authority-grants","provider-users?search=synthetic-user","options?page=APPOINTMENTS&optionKind=PRINCIPAL"))assertEquals(403,request("GET",path,null,Map.of()).statusCode(),path);
            assertEquals(403,request("POST","organizations",Map.of("parentOrganizationId",root.toString(),"code","DENIED","displayName","Denied"),Map.of("Idempotency-Key",UUID.randomUUID().toString())).statusCode());assertEquals(before,count("audit.audit_entry"));assertEquals(slots,count("execution.command_execution_slot"));
        } finally {try(var c=database.adminConnection()){for(String code:IdentityCommands.MANAGEMENT)io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql(c,"insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,?,clock_timestamp()-interval '1 hour','ACTIVE',clock_timestamp())",tenant,UUID.randomUUID(),ownAppointment,ownAppointment,root,code);}}
    }
    UUID validBusinessDelegation()throws Exception {
        UUID principal=UUID.randomUUID(),appointment=UUID.randomUUID(),grant=UUID.randomUUID();
        try(var c=database.adminConnection()) {
            io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql(c,"insert into identity.principal (tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) values (?,?,'HUMAN','TASK92',?,'Represented fixture','ACTIVE',clock_timestamp())",tenant,principal,key());
            io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql(c,"insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'CONTACT_OPERATOR',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",tenant,appointment,principal,root);
            io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql(c,"insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,'SALES_CONTACT_OWNER',clock_timestamp()-interval '1 hour','ACTIVE',clock_timestamp())",tenant,grant,appointment,ownAppointment,root);
            io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql(c,"insert into identity.delegation_grant (tenant_id,delegation_grant_id,source_authority_grant_id,delegator_appointment_id,delegate_appointment_id,scope_organization_unit_id,valid_from,state,created_at) values (?,?,?,?,?,?,clock_timestamp()-interval '30 minutes','ACTIVE',clock_timestamp())",tenant,UUID.randomUUID(),grant,appointment,ownAppointment,root);
        }
        try(var c=database.apiConnection()) {
            c.setAutoCommit(false);io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.setLocalRole(c,io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.Capability.QUERY);
            var reader=HumanIdentityReader.databaseBacked();var identity=reader.unique(c,tenant,"TASK92",new ExternalSubjectProtection(t->subjectKey).digest(tenant,login.subject()));assertTrue(reader.delegated(c,identity,ownAppointment).stream().anyMatch(choice->choice.choice().appointment().id().equals(appointment)));c.rollback();
        }
        return appointment;
    }
    static Map<String,Object> reason(){return Map.of("reasonCode","ADMINISTRATIVE_ACTION");}
    HttpResponse<String> create(String path,Object body)throws Exception{return write("POST",path,body,null,201);}
    HttpResponse<String> write(String method,String path,Object body,String tag,int status)throws Exception{var headers=new HashMap<String,String>();headers.put("Idempotency-Key",UUID.randomUUID().toString());if(tag!=null)headers.put("If-Match",tag);var response=request(method,path,body,headers);assertEquals(status,response.statusCode(),path+response.body());assertReceiptLocation(response,headers.get("Idempotency-Key"));return response;}
    void assertReceiptLocation(HttpResponse<String> response,String commandId){assertEquals("/api/v1/commands/"+commandId+"/receipt",response.headers().firstValue("Location").orElseThrow());assertEquals(commandId,json(response.body()).path("commandId").asString());}
    String factId(HttpResponse<String> response)throws Exception {
        var body=json(response.body());UUID command=UUID.fromString(body.path("commandId").asString());
        try(var c=database.migratorConnection();var p=c.prepareStatement("select r.command_receipt_id,r.result_fact_type,r.result_fact_id,r.result_fact_revision from execution.command_execution_slot s join execution.command_receipt r on r.tenant_id=s.tenant_id and r.command_execution_slot_id=s.command_execution_slot_id where s.tenant_id=? and s.command_id=?")) {
            p.setObject(1,tenant);p.setObject(2,command);try(var rows=p.executeQuery()){assertTrue(rows.next(),"Missing original receipt");UUID receipt=rows.getObject(1,UUID.class),fact=rows.getObject(3,UUID.class);String type=rows.getString(2);Long revision=rows.getObject(4,Long.class);assertFalse(rows.next(),"Ambiguous original receipt");
                assertEquals(receipt.toString(),body.path("receiptId").asString());var projection=body.path("resultFact");assertEquals(switch(type){case "identity.principal"->"IDENTITY_PRINCIPAL";case "identity.organization_unit"->"ORGANIZATION_UNIT";case "identity.appointment"->"APPOINTMENT";case "identity.authority_grant"->"AUTHORITY_GRANT";default->throw new AssertionError("Unexpected Identity result Fact type: "+type);},projection.path("factType").asString());assertEquals(revision.longValue(),projection.path("revision").asLong());assertFalse(projection.path("factRef").asString().isBlank());return fact.toString();}
        }
    }
    static String etag(HttpResponse<String> r){return r.headers().firstValue("ETag").orElseThrow();}
    String findTag(String collection,String id)throws Exception{var response=request("GET",collection+"?limit=50",null,Map.of());assertEquals(200,response.statusCode(),response.body());for(var item:json(response.body()).path("items"))if(item.path("id").asString().equals(id))return item.path("etag").asString();throw new AssertionError("Missing visible identity");}
    HttpResponse<String> request(String method,String path,Object body,Map<String,String> headers)throws Exception {
        try(var client=HttpClient.newHttpClient()) {
            var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+app.getEnvironment().getRequiredProperty("local.server.port")+"/api/v1/admin/identity/"+path)).header("Authorization","Bearer "+login.accessToken());
            headers.forEach(builder::header);if(body!=null)builder.header("Content-Type","application/json");
            builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(body)));
            return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
        }
    }
    long count(String table)throws Exception {try(var c=database.migratorConnection();var p=c.prepareStatement("select count(*) from "+table+" where tenant_id=?")){p.setObject(1,tenant);try(var r=p.executeQuery()){r.next();return r.getLong(1);}}}
    static tools.jackson.databind.JsonNode json(String body){return tools.jackson.databind.json.JsonMapper.builder().build().readTree(body);}
    static byte[] key(){byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);return key;}
}
