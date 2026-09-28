package io.github.windyzhu3.ontologylaw.api;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.lead.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.time.Instant;
import java.util.*;

/** F09 bridge: inherited quote/direct-contract checks now start at the real capture endpoint.
 * Only identity grants are seeded. No lead, assignment, contact task or opportunity is precreated.
 * CSV/XLSX parsing, role separation and signature/archive remain separate acceptance obligations.
 */
class R2ImportedLeadSalesHttpIT extends R2ContractHttpIT {
    @Override protected void setupContact() throws Exception {
        realHumanResolver=null;realHumanToken=null;
        businessAt=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        seed=AuthorizationServiceIT.seed(database,"HUMAN","LEAD_CAPTURE",this::credentialHmac);
        try(var c=database.apiConnection()) {inTransaction(c,Capability.COMMAND,x->{
            for(String code:List.of("LEAD_ASSIGN","SALES_CONTACT_OWNER","LEAD_INGRESS_COMPLETE","LEAD_INGRESS_RESOLVE","LEAD_ROUTING_DECIDE","SOURCE_INTAKE_REQUEST_ACK","LEAD_VALIDITY_REVIEW"))grant(x,code);
            return null;
        });}
        runtime=new CommandRuntime(new LeadCommands(policies,protection).handlers(),AuthorizationService.databaseBacked(),
            io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked("F09_CAPTURE"),R1AuthorizationReaders.databaseBacked(policies),R1EventReaders.databaseBacked());
        assertEquals("0",scalar("select count(*) from lead.lead where tenant_id=?",seed.tenant()));
        var payload=new TreeMap<String,Object>(input(true));payload.put("sourceRecordKey","F09-"+UUID.randomUUID());
        String key=UUID.randomUUID().toString();
        try(var http=new HttpHarness()) {
            var captured=http.request("POST","/api/v1/leads",payload,Map.of("Idempotency-Key",key));
            assertEquals(201,captured.statusCode(),captured.body());
            // Recovery uses the original receipt; it does not resubmit a new capture.
            assertSameReceipt(http.body(captured),http.body(http.request("GET","/api/v1/commands/"+key+"/receipt",null,Map.of())));
            assertSameReceipt(http.body(captured),http.body(http.request("POST","/api/v1/leads",payload,Map.of("Idempotency-Key",key))));
            UUID taskId=UUID.fromString(scalar("select task_occurrence_id from responsibility.task_occurrence where tenant_id=? and business_purpose_code='ASSIGN_LEAD' and state='OPEN'",seed.tenant()));
            try(var c=database.apiConnection()){current=inTransaction(c,Capability.QUERY,x->TaskFactory.databaseBacked().read(x,seed.tenant(),taskId));}
            var original=current;
            var assign=prepare(Map.of("ownerAppointmentId",seed.appointment().toString()));
            var response=http.request("POST","/api/v1/tasks/"+taskId+"/commands/assign-lead",assign.payload(),Map.of("Idempotency-Key",assign.commandId().toString(),"If-Match",assign.taskPrecondition().ifMatch()));
            assertEquals(200,response.statusCode(),response.body());
            assertSameReceipt(http.body(response),http.body(http.request("GET","/api/v1/commands/"+assign.commandId()+"/receipt",null,Map.of())));
            try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
                var finished=TaskFactory.databaseBacked().read(x,seed.tenant(),taskId);
                assertEquals("DONE",finished.state());secondaryFact=finished.completion();
                var next=TaskFactory.databaseBacked().activeForLead(x,seed.tenant(),LeadIngressService.databaseBacked(protection).header(x,seed.tenant(),original.lead().id()).selector());
                assertEquals(1,next.size());current=next.getFirst();assertEquals(TaskFactory.Type.CONTACT_LEAD,current.type());assertEquals(seed.appointment(),current.owner());
                return null;
            });}
            assertEquals("1",scalar("select count(*) from lead.lead where tenant_id=?",seed.tenant()));
            assertEquals("1",scalar("select count(*) from lead.lead_assignment where tenant_id=?",seed.tenant()));
        }
        var handlers=new ArrayList<CommandHandler>(new LeadCommands(policies,protection).handlers());
        handlers.removeIf(h->h.type()==CommandEnvelope.Type.RECORD_CONTACT_RESULT||h.type()==CommandEnvelope.Type.REVIEW_LEAD_VALIDITY);
        handlers.addAll(new ContactCommands(policies,protection).handlers());
        runtime=new CommandRuntime(handlers,AuthorizationService.databaseBacked(),io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked("F09_CONTACT"),R1AuthorizationReaders.databaseBacked(policies),R1EventReaders.databaseBacked());
    }

    Map<String,Object> contractBody(Map<String,Object> context,Map<String,Object> values) {
        var body=new LinkedHashMap<String,Object>();
        body.put("expectedOpportunityRevision",((Map<?,?>)context.get("opportunity")).get("revision"));
        body.put("responsibilityBasis",context.get("responsibilityBasis"));body.put("customerConfirmation",context.get("customerConfirmation"));
        var contract=(Map<?,?>)context.get("contract");var draft=(Map<?,?>)context.get("draft");var workflow=(Map<?,?>)context.get("workflow");
        body.put("expectedContract",contract==null?null:contract.get("selector"));body.put("expectedVersion",contract==null?null:contract.get("currentRevision"));
        body.put("expectedDraft",draft==null?null:draft.get("selector"));body.put("expectedWorkflow",workflow==null?null:workflow.get("selector"));body.put("values",values);return body;
    }
    @org.junit.jupiter.api.Test void imported_direct_request_hands_to_distinct_authorizer_then_back_to_sales()throws Exception {
        setupQuote();contractProtection=io.github.windyzhu3.ontologylaw.contract.ContractProtection.aesGcm(t->new javax.crypto.spec.SecretKeySpec(new byte[32],"AES"));
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"CONTRACT_READ");grant(x,"CONTRACT_PREPARE");return null;});}
        var reviewer=credentialActor(AuthorizationService.PrincipalKind.HUMAN,"F09 direct authorizer","CONTRACT_PREPARATION_DECIDE");
        mutate("insert into identity.authority_grant(tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values(?,?,?,?,?,'CONTRACT_READ',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),reviewer.appointmentId(),seed.appointment(),seed.org());
        String base="/api/v1/opportunities/"+opportunity.id()+"/contracts";
        registerActors(reviewer);
        try(var sales=new HttpHarness()) {var authorization=new RoleHttp(sales,"F09 direct authorizer");
            var commercial=new LinkedHashMap<String,Object>();commercial.put("currency","CNY");commercial.put("scope","F09 synthetic direct contract");commercial.put("lines",List.of(Map.of("description","Service","amountMinor",10000,"discount",false)));commercial.put("conditionalFee",null);commercial.put("paymentTerms","As agreed");
            var initial=sales.body(sales.request("GET",base,null,Map.of()));var requestBody=contractBody(initial,Map.of("commercial",commercial,"reason","F09 exact direct preparation"));String key=UUID.randomUUID().toString();
            var requested=sales.request("POST",base+"/preparation-requests",requestBody,Map.of("Idempotency-Key",key));assertEquals(200,requested.statusCode(),requested.body());
            var read=authorization.request("GET",base,null,Map.of());assertEquals(200,read.statusCode(),read.body());var reviewContext=authorization.body(read);
            assertTrue(((List<?>)reviewContext.get("allowedActions")).contains("RECORD_CONTRACT_PREPARATION_DECISION"));
            UUID reviewTask=UUID.fromString(scalar("select task_occurrence_id from responsibility.task_occurrence where tenant_id=? and subject_id=? and business_purpose_code='DECIDE_CONTRACT_PREPARATION' and state='OPEN'",seed.tenant(),opportunity.id()));
            assertEquals(reviewer.appointmentId().toString(),scalar("select owner_appointment_id from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),reviewTask));
            var decision=contractBody(reviewContext,Map.of("decision","APPROVED","reason","Approved precise scope"));
            assertEquals(403,sales.request("POST",base+"/preparation-decisions",decision,Map.of("Idempotency-Key",UUID.randomUUID().toString())).statusCode());
            String decisionKey=UUID.randomUUID().toString();var approved=authorization.request("POST",base+"/preparation-decisions",decision,Map.of("Idempotency-Key",decisionKey));assertEquals(200,approved.statusCode(),approved.body());
            assertSameReceipt(authorization.body(approved),authorization.body(authorization.request("GET","/api/v1/commands/"+decisionKey+"/receipt",null,Map.of())));
            assertEquals("DONE",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),reviewTask));
            assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and business_purpose_code='PREPARE_CONTRACT' and state='OPEN' and owner_appointment_id=?",seed.tenant(),opportunity.id(),seed.appointment()));
            var next=sales.body(sales.request("GET",base,null,Map.of()));assertTrue(((List<?>)next.get("allowedActions")).contains("START_CONTRACT_PREPARATION"),next.toString());
            assertSameReceipt(sales.body(requested),sales.body(sales.request("GET","/api/v1/commands/"+key+"/receipt",null,Map.of())));
            continueContractPreparation(sales);
            assertEquals("0",scalar("select count(*) from opportunity.quote_response where tenant_id=?",seed.tenant()));
            assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and business_purpose_code='PROGRESS_OPPORTUNITY' and state in ('OPEN','WAITING')",seed.tenant(),opportunity.id()));
        }
    }

    @org.junit.jupiter.api.Test void imported_quote_hands_to_configured_approver_and_back_to_delivery()throws Exception {
        setupQuote();contractProtection=io.github.windyzhu3.ontologylaw.contract.ContractProtection.aesGcm(t->new javax.crypto.spec.SecretKeySpec(new byte[32],"AES"));var approver=credentialActor(AuthorizationService.PrincipalKind.HUMAN,"F09 quote approver","QUOTE_APPROVE");
        mutate("insert into identity.authority_grant(tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values(?,?,?,?,?,'QUOTE_READ',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),approver.appointmentId(),seed.appointment(),seed.org());
        UUID policy=UUID.randomUUID();
        try(var configuration=database.adminConnection()){configuration.setAutoCommit(false);R2QuoteWorkflowIT.execSql(configuration,"insert into opportunity.quote_approval_policy(tenant_id,quote_approval_policy_id,revision,organization_unit_id,policy_code,policy_version,mode,created_at) values(?,?,0,?,'R2_QUOTE_APPROVAL_V1',1,'REQUIRE_APPROVAL',clock_timestamp())",seed.tenant(),policy,seed.org());
        R2QuoteWorkflowIT.execSql(configuration,"insert into opportunity.quote_approval_policy_signer(tenant_id,quote_approval_policy_signer_id,revision,policy_id,appointment_id) values(?,?,0,?,?)",seed.tenant(),UUID.randomUUID(),policy,approver.appointmentId());configuration.commit();}
        String base="/api/v1/opportunities/"+opportunity.id()+"/quotes";
        registerActors(approver);
        try(var sales=new HttpHarness()) {var review=new RoleHttp(sales,"F09 quote approver");
            for(var entry:List.of(Map.entry("preparation-intents",Map.<String,Object>of()),Map.entry("form",Map.<String,Object>of("currency","CNY","scope","F09 service","lines",List.of(Map.of("description","Service","amountMinor",10000,"discount",false)),"paymentTerms","As agreed","validUntil",Instant.now().plusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString())),Map.entry("approval-requests",Map.<String,Object>of()))) {
                var ctx=sales.body(sales.request("GET",base,null,Map.of()));var response=sales.request("POST",base+"/"+entry.getKey(),commandBody(ctx,entry.getValue()),Map.of("Idempotency-Key",UUID.randomUUID().toString()));assertEquals(200,response.statusCode(),response.body());
            }
            var ctx=review.body(review.request("GET",base,null,Map.of()));assertEquals("AWAIT_APPROVAL",((Map<?,?>)ctx.get("workflow")).get("stage"));
            assertEquals(approver.appointmentId().toString(),scalar("select owner_appointment_id from responsibility.task_occurrence where tenant_id=? and subject_id=? and business_purpose_code='APPROVE_QUOTE' and state='OPEN'",seed.tenant(),opportunity.id()));
            var decision=commandBody(ctx,Map.of("decision","APPROVED","reason","Exact version approved"));String key=UUID.randomUUID().toString();
            assertFalse(((List<?>)sales.body(sales.request("GET",base,null,Map.of())).get("allowedActions")).contains("RECORD_QUOTE_DECISION"));
            var response=review.request("POST",base+"/decisions",decision,Map.of("Idempotency-Key",key));assertEquals(200,response.statusCode(),response.body());
            assertSameReceipt(review.body(response),review.body(review.request("GET","/api/v1/commands/"+key+"/receipt",null,Map.of())));
            var next=sales.body(sales.request("GET",base,null,Map.of()));assertEquals("DELIVER",((Map<?,?>)next.get("workflow")).get("stage"));assertTrue(((List<?>)next.get("allowedActions")).contains("RECORD_QUOTE_DELIVERY"));
            assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and business_purpose_code='DELIVER_QUOTE' and state='OPEN' and owner_appointment_id=?",seed.tenant(),opportunity.id(),seed.appointment()));
            try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"CONTRACT_PREPARE");grant(x,"CONTRACT_READ");return null;});}
            var evidence=acceptedMaterial(next);
            var recipient=(Map<?,?>)((List<?>)next.get("recipients")).getFirst();
            var delivery=Map.<String,Object>of("recipient","Synthetic contact","recipientParticipation",recipient.get("selector"),"channel","EMAIL","occurredAt",Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString(),"evidence",R2CustomerRequirementsServices.selector(evidence));
            var delivered=sales.request("POST",base+"/deliveries",commandBody(next,delivery),Map.of("Idempotency-Key",UUID.randomUUID().toString()));assertEquals(200,delivered.statusCode(),delivered.body());
            next=sales.body(sales.request("GET",base,null,Map.of()));
            var reply=Map.<String,Object>of("kind","ACCEPTED","statement","Client accepts exact quotation","occurredAt",Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString(),"evidence",R2CustomerRequirementsServices.selector(evidence));
            String replyKey=UUID.randomUUID().toString();var accepted=sales.request("POST",base+"/responses",commandBody(next,reply),Map.of("Idempotency-Key",replyKey));assertEquals(200,accepted.statusCode(),accepted.body());
            assertSameReceipt(sales.body(accepted),sales.body(sales.request("GET","/api/v1/commands/"+replyKey+"/receipt",null,Map.of())));
            assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and business_purpose_code='PREPARE_CONTRACT' and state='OPEN' and owner_appointment_id=?",seed.tenant(),opportunity.id(),seed.appointment()));
            assertEquals("1",scalar("select count(*) from opportunity.contract_preparation_source where tenant_id=? and opportunity_id=?",seed.tenant(),opportunity.id()));
            continueContractPreparation(sales);

            assertEquals("0",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and subject_id=? and business_purpose_code in ('PROGRESS_OPPORTUNITY','APPROVE_QUOTE') and state in ('OPEN','WAITING')",seed.tenant(),opportunity.id()));
        }
    }

    void registerActors(AuthorizationService.Actor other) {
        realHumanResolver=new io.github.windyzhu3.ontologylaw.api.security.ActorContextResolver(database::apiConnection,new ExternalSubjectProtection(credentialKeys::get),
            List.of(new io.github.windyzhu3.ontologylaw.api.security.ActorContextResolver.Trust(ISSUER,AUDIENCE,(java.security.interfaces.RSAPublicKey)signing.getPublic())),
            List.of(new io.github.windyzhu3.ontologylaw.api.security.ActorContextResolver.Registration(ISSUER,AUDIENCE,"FIXTURE",seed.request().actor()),new io.github.windyzhu3.ontologylaw.api.security.ActorContextResolver.Registration(ISSUER,AUDIENCE,"FIXTURE",other)));
    }
    final class RoleHttp {
        final HttpHarness http;final String token;
        RoleHttp(HttpHarness http,String subject)throws Exception{this.http=http;this.token=bearer(subject);}
        java.net.http.HttpResponse<String> request(String method,String path,Object body,Map<String,String> headers)throws Exception {
            var builder=java.net.http.HttpRequest.newBuilder(http.origin.resolve(path)).header("Authorization","Bearer "+token).header("Accept","application/json");headers.forEach(builder::header);
            if(body==null)builder.method(method,java.net.http.HttpRequest.BodyPublishers.noBody());else builder.header("Content-Type","application/json").method(method,java.net.http.HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            return http.client.send(builder.build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        }
        Map<String,Object> body(java.net.http.HttpResponse<String> response){return http.body(response);}
    }

    @org.junit.jupiter.api.io.TempDir java.nio.file.Path materialDirectory;
    // Real bytes and Owner acceptance; scanner is a deterministic test port, not a live malware service.
    AuthorizationService.Subject acceptedMaterial(Map<String,Object> context)throws Exception {
        var image=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"PNG",bytes);
        return acceptedMaterial(context,bytes.toByteArray(),"F09-synthetic.png");
    }
    AuthorizationService.Subject acceptedMaterial(Map<String,Object> context,byte[] bytes,String filename)throws Exception {
        var confirmation=(Map<?,?>)context.get("customerConfirmation");var selector=new AuthorizationService.Subject(io.github.windyzhu3.ontologylaw.opportunity.OpportunityCustomerRequirementsService.CONFIRMATION,UUID.fromString((String)confirmation.get("id")),0L,null);
        var currentRoot=(Map<?,?>)context.get("opportunity");var currentOpportunity=new AuthorizationService.Subject("opportunity.opportunity",opportunity.id(),((Number)currentRoot.get("revision")).longValue(),null);
        var evidence=R2MaterialsServices.evidence(opportunityProtection);UUID basis;
        try(var c=database.apiConnection()){basis=inTransaction(c,Capability.COMMAND,x->evidence.open(x,seed.tenant(),currentOpportunity,currentOpportunity,seed.appointment(),selector,null,"CONTRACT_BUSINESS",filename,"Synthetic delivery and acceptance evidence").selector().id());}

        var store=io.github.windyzhu3.ontologylaw.evidence.MaterialObjectStore.localPrivate(materialDirectory,input->new io.github.windyzhu3.ontologylaw.evidence.MalwareScanner.ScanResult(io.github.windyzhu3.ontologylaw.evidence.MalwareScanner.Verdict.CLEAN,"F09-SYNTHETIC"));var stored=store.store(new java.io.ByteArrayInputStream(bytes));
        try(var c=database.apiConnection()){return inTransaction(c,Capability.COMMAND,x->{var b=evidence.basis(x,seed.tenant(),basis);var check=evidence.beginCheck(x,seed.tenant(),b);evidence.completeCheck(x,seed.tenant(),b,check,stored,"PASSED","CLEAN");b=evidence.basis(x,seed.tenant(),basis);var submission=evidence.accept(x,seed.tenant(),b);var body=evidence.protectedBody(x,seed.tenant(),basis);return io.github.windyzhu3.ontologylaw.opportunity.OpportunityMaterials.databaseBacked().accept(x,seed.tenant(),opportunity.id(),null,basis,submission.source(),submission.submission(),submission.binding(),"CONTRACT_BUSINESS",seed.appointment(),submission.at(),body.ciphertext(),body.digest()).selector();});}
    }

    void continueContractPreparation(HttpHarness sales)throws Exception {
        String base="/api/v1/opportunities/"+opportunity.id()+"/contracts";
        String responsibility=scalar("select task_occurrence_id::text||':'||original_sla_due_at::text from responsibility.task_occurrence where tenant_id=? and subject_id=? and business_purpose_code='PREPARE_CONTRACT' and state='OPEN'",seed.tenant(),opportunity.id());
        var read=sales.request("GET",base,null,Map.of());assertEquals(200,read.statusCode(),read.body());var context=sales.body(read);
        String key=UUID.randomUUID().toString();var started=sales.request("POST",base+"/start",contractBody(context,Map.of()),Map.of("Idempotency-Key",key));assertEquals(200,started.statusCode(),started.body());
        assertEquals(responsibility,scalar("select task_occurrence_id::text||':'||original_sla_due_at::text from responsibility.task_occurrence where tenant_id=? and subject_id=? and business_purpose_code='PREPARE_CONTRACT' and state='OPEN'",seed.tenant(),opportunity.id()));
        var next=sales.body(sales.request("GET",base,null,Map.of()));assertTrue(((List<?>)next.get("allowedActions")).contains("FORM_CONTRACT"),next.toString());
        assertSameReceipt(sales.body(started),sales.body(sales.request("GET","/api/v1/commands/"+key+"/receipt",null,Map.of())));
    }
}


