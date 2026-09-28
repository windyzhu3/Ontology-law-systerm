package io.github.windyzhu3.ontologylaw.api;

import com.sun.net.httpserver.*;
import io.github.windyzhu3.ontologylaw.execution.CanonicalJson;
import io.github.windyzhu3.ontologylaw.testing.TlsFixture;
import io.github.windyzhu3.ontologylaw.worker.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class R2WorkerClientHttpIT {
    @TempDir Path directory;
    static final UUID KEY=UUID.fromString("00000000-0000-5000-8000-000000000001"), OPPORTUNITY=UUID.fromString("00000000-0000-4000-8000-000000000002");
    static final String HASH="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    static Map<String,Object> candidate(boolean due) {
        var values=new LinkedHashMap<String,Object>();
        values.put("kind",due?"DUE":"INITIAL");values.put("idempotencyKey",KEY.toString());values.put("opportunityId",OPPORTUNITY.toString());values.put("expectedOpportunityRevision",3);
        if(due){values.put("taskId",UUID.randomUUID().toString());values.put("expectedTaskRevision",4);values.put("waitReceiptId",UUID.randomUUID().toString());values.put("waitReceiptHash",HASH);values.put("progressId",UUID.randomUUID().toString());values.put("progressHash",HASH);values.put("dueCutoff","2026-09-14T10:00:00.123456Z");}
        return values;
    }
    static String page(Map<String,Object> row){return new JsonMapper().writeValueAsString(Map.of("candidates",List.of(row)));}
    static String receipt(String outcome){return CanonicalJson.encode(Map.of("commandId",KEY.toString(),"receiptId",UUID.randomUUID().toString(),"outcome",outcome,"completedAt","2026-09-14T10:00:00.123456Z","resultFact",Map.of("factType","TASK_OCCURRENCE","factRef","opaque-task-reference-0001","revision",5)));}
    final class Harness implements AutoCloseable {
        final HttpsServer server;final InternalApiClient client;final R1WorkerTenantBindings.Binding binding;
        final AtomicBoolean gate=new AtomicBoolean(true);int received;String path,method,key,requestBody;String body="{\"candidates\":[]}";int status=200;boolean etag,location;byte[] rawBody;
        Harness()throws Exception {
            var tls=new TlsFixture(directory);var serverKey=tls.key("SERVER");var clientKey=tls.key("CLIENT");
            binding=new R1WorkerTenantBindings.Binding(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"CLIENT",tls.sha256(clientKey));
            server=HttpsServer.create(new InetSocketAddress("localhost",0),0);var context=tls.client(serverKey,tls.trust("serverTrust",clientKey));
            server.setHttpsConfigurator(new HttpsConfigurator(context){public void configure(HttpsParameters parameters){var p=context.getDefaultSSLParameters();p.setNeedClientAuth(true);parameters.setSSLParameters(p);}});
            server.createContext("/",exchange->{try{received++;path=exchange.getRequestURI().toString();method=exchange.getRequestMethod();key=exchange.getRequestHeaders().getFirst("Idempotency-Key");requestBody=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
                if(etag)exchange.getResponseHeaders().set("ETag","\"unexpected\"");if(location)exchange.getResponseHeaders().set("Location","/api/v1/commands/receipt");
                var bytes=rawBody==null?body.getBytes(StandardCharsets.UTF_8):rawBody;exchange.sendResponseHeaders(status,bytes.length==0?-1:bytes.length);if(bytes.length>0)exchange.getResponseBody().write(bytes);
            }finally{exchange.close();}});
            server.start();client=new InternalApiClient(URI.create("https://localhost:"+server.getAddress().getPort()),new R1WorkerTenantBindings("MVP-2026-09-08.3",List.of(binding)),Map.of("CLIENT",new InternalApiClient.Credentials(clientKey.store(),tls.password,tls.trust("clientTrust",serverKey).store())),gate::get);
        }
        public void close(){client.close();server.stop(0);}
    }
    @Test void contract_preparation_discovery_retains_utf8_body_and_exact_reconcile_receipt()throws Exception {
        try(var h=new Harness()){
            for(String mode:List.of("ACCEPTED_QUOTE","AUTHORITY_RETURN","SIGNATURE_READINESS","SIGNATURE_AUTHORITY_RETURN")){
                var source=UUID.randomUUID().toString();var row=candidate(false);
                row.put("kind","CONTRACT_PREPARATION");row.put("sourceKind",mode);
                row.put("responsibilityBasis",Map.of("id",UUID.randomUUID().toString(),"revision",2));
                row.put("source",Map.of("id",source,"revision",0));
                row.put("expectedWorkflow",mode.endsWith("AUTHORITY_RETURN")?Map.of("id",source,"revision",0):null);
                var response=new LinkedHashMap<String,Object>();response.put("candidates",List.of(row));response.put("nextCursor",null);
                h.body=CanonicalJson.encode(response);
                var page=h.client.opportunityCandidates(h.binding,InternalApiClient.OpportunityKind.CONTRACT_PREPARATION,null);
                assertEquals(200,page.status());assertEquals(1,page.candidates().size());assertNull(page.nextCursor());
                assertEquals("/internal/v1/contract-preparation/candidates?limit=5",h.path);
                response.put("nextCursor","next+page/&=");h.body=CanonicalJson.encode(response);
                var continued=h.client.opportunityCandidates(h.binding,InternalApiClient.OpportunityKind.CONTRACT_PREPARATION,"prior+page/&=");
                assertEquals(200,continued.status());assertEquals("next+page/&=",continued.nextCursor());
                assertEquals(page.candidates(),continued.candidates());
                assertEquals("/internal/v1/contract-preparation/candidates?limit=5&cursor=prior%2Bpage%2F%26%3D",h.path);
                var wire=receipt("SUCCEEDED").replace("TASK_OCCURRENCE",mode.startsWith("SIGNATURE_")?"CONTRACT_SIGNATURE_WORKFLOW":"CONTRACT_PREPARATION_WORKFLOW").replace("\"revision\":5","\"revision\":0");
                // Exercise the same closed generated response binding as the real delegate.
                var model=R1WireModels.model(new JsonMapper().readValue(wire,Map.class),io.github.windyzhu3.ontologylaw.api.adapter.generated.model.ContractPreparationReconcileReceiptV1.class);
                h.body=new JsonMapper().writeValueAsString(model);
                assertEquals(200,h.client.maintainOpportunity(h.binding,page.candidates().getFirst()).status());
                assertEquals("/internal/v1/opportunity-tasks/commands/reconcile-contract-preparation",h.path);assertEquals(KEY.toString(),h.key);
                row.remove("kind");row.remove("idempotencyKey");assertEquals(row,new JsonMapper().readValue(h.requestBody,Map.class));
            }
            // A malformed byte within otherwise valid JSON must not be silently replaced.
            var prefix="{\"candidates\":[],\"nextCursor\":\"".getBytes(StandardCharsets.UTF_8);
            var suffix="\"}".getBytes(StandardCharsets.UTF_8);
            h.rawBody=java.util.Arrays.copyOf(prefix,prefix.length+2+suffix.length);h.rawBody[prefix.length]=(byte)0xc3;h.rawBody[prefix.length+1]=(byte)0x28;System.arraycopy(suffix,0,h.rawBody,prefix.length+2,suffix.length);
            assertEquals(503,h.client.opportunityCandidates(h.binding,InternalApiClient.OpportunityKind.CONTRACT_PREPARATION,null).status());
        }
    }
    @Test void discovers_both_closed_candidate_kinds_and_reuses_the_exact_key_and_command_fields()throws Exception {
        try(var h=new Harness()){
            for(boolean due:List.of(false,true)){
                var original=candidate(due);h.body=CanonicalJson.encode(Map.of("candidates",List.of(original),"nextCursor","next+page/&="));
                var kind=due?InternalApiClient.OpportunityKind.DUE:InternalApiClient.OpportunityKind.INITIAL;
                var page=h.client.opportunityCandidates(h.binding,kind,"prior+page/&=");assertEquals(200,page.status());assertEquals("next+page/&=",page.nextCursor());assertEquals(1,page.candidates().size());
                assertEquals("GET",h.method);assertEquals("/internal/v1/opportunity-tasks/candidates?kind="+kind+"&limit=50&cursor=prior%2Bpage%2F%26%3D",h.path);assertNull(h.key);
                h.body=receipt(due?"NO_CHANGE":"SUCCEEDED");assertEquals(200,h.client.maintainOpportunity(h.binding,page.candidates().getFirst()).status());
                assertEquals("POST",h.method);assertEquals("/internal/v1/opportunity-tasks/commands/"+(due?"reopen-due":"activate-initial"),h.path);assertEquals(KEY.toString(),h.key);
                original.remove("kind");original.remove("idempotencyKey");assertEquals(original,new JsonMapper().readValue(h.requestBody,Map.class));
            }
        }
    }
    @Test void rejects_unknown_duplicate_trailing_mixed_and_malformed_candidate_payloads_without_partial_pages()throws Exception {
        try(var h=new Harness()){
            var malformed=new ArrayList<String>();var base=candidate(false);
            malformed.add(page(candidate(true)));malformed.add(CanonicalJson.encode(Map.of("candidates",List.of(base,candidate(true)))));malformed.add(CanonicalJson.encode(Map.of("candidates",List.of(base,base))));malformed.add(page(base).replace("\"kind\":\"INITIAL\"","\"kind\":\"INITIAL\",\"kind\":\"INITIAL\""));malformed.add(page(base)+" {}");
            for(var change:List.of(Map.entry("idempotencyKey",(Object)UUID.randomUUID().toString()),Map.entry("opportunityId",(Object)"1-1-1-1-1"),Map.entry("expectedOpportunityRevision",(Object)(-1)),Map.entry("expectedOpportunityRevision",(Object)1.5),Map.entry("expectedOpportunityRevision",(Object)9007199254740992L),Map.entry("unknown",(Object)true))){var row=new LinkedHashMap<>(base);row.put(change.getKey(),change.getValue());malformed.add(page(row));}
            malformed.add("{\"candidates\":[],\"nextCursor\":null}");malformed.add("{\"candidates\":[],\"nextCursor\":\"\"}");malformed.add(CanonicalJson.encode(Map.of("candidates",Collections.nCopies(51,base))));
            for(String value:malformed){h.body=value;var response=h.client.opportunityCandidates(h.binding,InternalApiClient.OpportunityKind.INITIAL,null);assertEquals(503,response.status(),value);assertTrue(response.candidates().isEmpty());}
            for(var change:List.of(Map.entry("waitReceiptHash","A".repeat(42)+"B"),Map.entry("progressHash","invalid"),Map.entry("dueCutoff","2026-09-14T10:00:00.1234567Z"),Map.entry("dueCutoff","2026-09-14T10:00:00"))){var row=candidate(true);row.put(change.getKey(),change.getValue());h.body=page(row);assertEquals(503,h.client.opportunityCandidates(h.binding,InternalApiClient.OpportunityKind.DUE,null).status());}
        }
    }
    @Test void owner_exception_transport_binds_original_request_and_exact_exception_or_validation_receipt()throws Exception {
        try(var h=new Harness()){
            var original=candidate(false);original.put("kind","OWNER_EXCEPTION");h.body=page(original);
            var page=h.client.opportunityCandidates(h.binding,InternalApiClient.OpportunityKind.OWNER_EXCEPTION,null);
            assertEquals(200,page.status());assertEquals("/internal/v1/opportunity-owner-exceptions/candidates?limit=50",h.path);
            var candidate=page.candidates().getFirst();
            var result=new LinkedHashMap<String,Object>();result.put("commandId",KEY.toString());result.put("receiptId",UUID.randomUUID().toString());result.put("outcome","SUCCEEDED");result.put("completedAt","2026-09-14T10:00:00.123456Z");
            result.put("resultFact",Map.of("factType","OPPORTUNITY_OWNER_EXCEPTION","factRef","opaque-exception-reference-0001","revision",0));
            h.body=CanonicalJson.encode(result);assertEquals(200,h.client.maintainOpportunity(h.binding,candidate).status());
            assertEquals("/internal/v1/opportunity-owner-exceptions/commands/observe",h.path);assertEquals(KEY.toString(),h.key);
            assertEquals(Map.of("opportunityId",OPPORTUNITY.toString(),"expectedOpportunityRevision",3),new JsonMapper().readValue(h.requestBody,Map.class));
            result.put("outcome","NO_CHANGE");result.put("resultFact",Map.of("factType","OPPORTUNITY_OWNER_VALIDATION","factRef","opaque-validation-reference-0001","digest",HASH));
            h.body=CanonicalJson.encode(result);assertEquals(200,h.client.maintainOpportunity(h.binding,candidate).status());
            result.put("outcome","SUCCEEDED");h.body=CanonicalJson.encode(result);assertEquals(503,h.client.maintainOpportunity(h.binding,candidate).status());
            h.body=receipt("NO_CHANGE");assertEquals(503,h.client.maintainOpportunity(h.binding,candidate).status());
        }
    }
    @Test void validates_success_receipts_instead_of_accepting_status_only_and_preserves_business_statuses()throws Exception {
        try(var h=new Harness()){
            h.body=page(candidate(false));var candidate=h.client.opportunityCandidates(h.binding,InternalApiClient.OpportunityKind.INITIAL,null).candidates().getFirst();
            for(String invalid:List.of("","{}",receipt("REJECTED"),receipt("SUCCEEDED").replace(KEY.toString(),UUID.randomUUID().toString()),receipt("SUCCEEDED").replace("TASK_OCCURRENCE","LEAD"),receipt("SUCCEEDED")+" {}")){h.body=invalid;assertEquals(503,h.client.maintainOpportunity(h.binding,candidate).status(),invalid);}
            h.body=receipt("SUCCEEDED");h.etag=true;assertEquals(503,h.client.maintainOpportunity(h.binding,candidate).status());h.etag=false;h.location=true;assertEquals(503,h.client.maintainOpportunity(h.binding,candidate).status());h.location=false;
            for(int status:List.of(400,401,403,404,409,422,429,500,503)){h.status=status;h.body="{}";assertEquals(status,h.client.maintainOpportunity(h.binding,candidate).status());}
        }
    }
    @Test void retains_gate_binding_response_limit_and_closed_input_guards()throws Exception {
        try(var h=new Harness()){
            h.body=page(candidate(false));var candidate=h.client.opportunityCandidates(h.binding,InternalApiClient.OpportunityKind.INITIAL,null).candidates().getFirst();int before=h.received;
            h.gate.set(false);assertEquals(503,h.client.opportunityCandidates(h.binding,InternalApiClient.OpportunityKind.INITIAL,null).status());assertEquals(503,h.client.maintainOpportunity(h.binding,candidate).status());assertEquals(before,h.received);h.gate.set(true);
            var other=new R1WorkerTenantBindings.Binding(UUID.randomUUID(),h.binding.principalId(),h.binding.appointmentId(),h.binding.credentialAlias(),h.binding.certificateSha256());assertEquals(503,h.client.opportunityCandidates(other,InternalApiClient.OpportunityKind.INITIAL,null).status());assertEquals(before,h.received);
            assertEquals(400,h.client.opportunityCandidates(h.binding,null,null).status());assertEquals(400,h.client.opportunityCandidates(h.binding,InternalApiClient.OpportunityKind.INITIAL,"").status());assertEquals(400,h.client.opportunityCandidates(h.binding,InternalApiClient.OpportunityKind.INITIAL,"x".repeat(2049)).status());assertEquals(400,h.client.maintainOpportunity(h.binding,null).status());assertEquals(before,h.received);
            h.body="x".repeat(65537);assertEquals(503,h.client.opportunityCandidates(h.binding,InternalApiClient.OpportunityKind.INITIAL,null).status());
        }
    }
    @Test void public_candidate_records_cannot_be_used_to_bypass_discovery_field_validation(){
        assertThrows(IllegalArgumentException.class,()->new InternalApiClient.InitialOpportunityCandidate(UUID.randomUUID(),OPPORTUNITY,0));
        assertThrows(IllegalArgumentException.class,()->new InternalApiClient.InitialOpportunityCandidate(UUID.fromString("00000000-0000-5000-0000-000000000001"),OPPORTUNITY,0));
        assertThrows(IllegalArgumentException.class,()->new InternalApiClient.InitialOpportunityCandidate(KEY,OPPORTUNITY,-1));
        assertThrows(IllegalArgumentException.class,()->new InternalApiClient.DueOpportunityCandidate(KEY,OPPORTUNITY,0,UUID.randomUUID(),0,UUID.randomUUID(),HASH,UUID.randomUUID(),"bad","2026-09-14T10:00:00Z"));
        assertThrows(IllegalArgumentException.class,()->new InternalApiClient.DueOpportunityCandidate(KEY,OPPORTUNITY,0,UUID.randomUUID(),0,UUID.randomUUID(),HASH,UUID.randomUUID(),HASH,"2026-09-14T10:00:00.1234567Z"));
    }
}
