package io.github.windyzhu3.ontologylaw.api;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.util.*;
import java.net.http.*;
import org.junit.jupiter.api.Test;

class R2SalesChainRepairHttpIT extends R2QuoteHttpIT {
    private HttpResponse<String> internal(HttpHarness http,String path,UUID key,Object body)throws Exception {
        return http.client.send(HttpRequest.newBuilder(http.origin.resolve(path)).header("Content-Type","application/json").header("Accept","application/json").header("Idempotency-Key",key.toString()).POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.encode(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void repair_http_source_requires_mtls_and_replays_original_command()throws Exception {
        setupFlow(TaskFactory.Type.RESOLVE_LEAD_ROUTING_GAP);
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"SOURCE_INTAKE_REQUEST_ACK");return null;});}
        var requested=execute(prepare(Map.of("decisionCode","REQUEST_SOURCE_INTAKE_STOP","rationaleSummary","Synthetic old request")));selectTask(TaskFactory.Type.ACK_SOURCE_INTAKE_STOP_REQUEST);
        var ack=current;var tasks=TaskFactory.databaseBacked();
        try(var c=database.apiConnection()){current=inTransaction(c,Capability.COMMAND,x->{
            var now=tasks.now(x);var values=new TreeMap<String,Object>();values.put("tenantId",seed.tenant().toString());values.put("subject",CommandScope.selector(ack.subject()));values.put("authoritySlot",ack.type().slot);values.put("decisionCode","SOURCE_INTAKE_STOP_REQUEST_ACKNOWLEDGED");values.put("rationaleSummary","Synthetic legacy ACK");values.put("causalDecisionId",requested.resultFact().id().toString());values.put("causalDecisionHash",requested.resultFact().hash());
            var fact=tasks.decision(x,seed.tenant(),ack,seed.appointment(),"SOURCE_INTAKE_STOP_REQUEST_ACKNOWLEDGED","SOURCE_INTAKE_STOP_REQUEST_ACKNOWLEDGED","Synthetic legacy ACK",values,now);tasks.complete(x,seed.tenant(),ack,fact,now);return tasks.read(x,seed.tenant(),ack.selector().id());
        });}
        var body=new CommandAuthorizationBinding.SourceRepair(current.selector(),current.subject(),current.owner(),current.completion(),seed.appointment()).payload();
        var worker=service("ROUTING_REVIEW_TASK_RECOVER");var tls=new io.github.windyzhu3.ontologylaw.testing.TlsFixture(java.nio.file.Files.createTempDirectory("repair-http-tls"));
        String path="/internal/v1/sales-chain-repairs/restore-source-request-task";
        try(var http=new HttpHarness(worker,tls)){
            var key=UUID.randomUUID();var response=internal(http,path,key,body);assertEquals(200,response.statusCode(),response.body());assertEquals("SUCCEEDED",http.body(response).get("outcome"));
            var replay=internal(http,path,key,body);assertEquals(200,replay.statusCode(),replay.body());assertSameReceipt(http.body(response),http.body(replay));assertFalse(response.headers().firstValue("Location").isPresent());
            assertEquals("1",scalar("select count(*) from responsibility.task_occurrence where tenant_id=? and business_purpose_code='RESOLVE_SOURCE_REQUEST'",seed.tenant()));
        }
    }
    @Test void repair_http_ordinary_uses_existing_contract_and_preserves_contract_responsibility()throws Exception {
        setupQuote();contractProtection=io.github.windyzhu3.ontologylaw.contract.ContractProtection.aesGcm(t->new javax.crypto.spec.SecretKeySpec(new byte[32],"AES"));
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{for(String code:List.of("CONTRACT_READ","CONTRACT_PREPARE","CONTRACT_PREPARATION_DECIDE"))grant(x,code);return null;});}
        var worker=service("OPPORTUNITY_TASK_RECOVER");var tls=new io.github.windyzhu3.ontologylaw.testing.TlsFixture(java.nio.file.Files.createTempDirectory("repair-opportunity-http-tls"));
        try(var http=new HttpHarness(worker,tls)){
            String base="/api/v1/opportunities/"+opportunity.id()+"/contracts";
            var read=http.request("GET",base,null,Map.of());assertEquals(200,read.statusCode(),read.body());var context=http.body(read);
            var body=new TreeMap<String,Object>();body.put("expectedOpportunityRevision",opportunity.revision());body.put("responsibilityBasis",context.get("responsibilityBasis"));body.put("customerConfirmation",context.get("customerConfirmation"));
            for(String field:List.of("expectedContract","expectedDraft","expectedVersion","expectedWorkflow"))body.put(field,null);
            var commercial=new TreeMap<String,Object>();commercial.put("currency","CNY");commercial.put("scope","Synthetic service scope");commercial.put("lines",List.of(Map.of("description","Service","amountMinor",10000,"discount",false)));commercial.put("conditionalFee",null);commercial.put("paymentTerms","Agreed payment");
            body.put("values",Map.of("commercial",commercial,"reason","Synthetic authorized preparation request"));
            var prepared=http.request("POST",base+"/preparation-requests",body,Map.of("Idempotency-Key",UUID.randomUUID().toString()));assertEquals(200,prepared.statusCode(),prepared.body());
            UUID task=UUID.randomUUID();mutate("insert into responsibility.task_occurrence(tenant_id,task_occurrence_id,owner_appointment_id,business_purpose_code,primary_command_code,expected_completion_fact_type,original_sla_code,original_sla_seconds,original_sla_due_at,state,created_at,subject_type,subject_id,subject_revision) values(?,?,?,'PROGRESS_OPPORTUNITY','RECORD_OPPORTUNITY_PROGRESS','opportunity.opportunity_progress','R2_BUSINESS_4H_V1',14400,clock_timestamp()+interval '4 hours','OPEN',clock_timestamp(),'opportunity.opportunity',?,?)",seed.tenant(),task,seed.appointment(),opportunity.id(),opportunity.revision());
            Map<String,Object> repair;
            try(var c=database.apiConnection()){repair=inTransaction(c,Capability.QUERY,x->new CommandAuthorizationBinding.OpportunityRepair(opportunity,new Subject("responsibility.task_occurrence",task,0L,null),seed.appointment(),opportunity,null,io.github.windyzhu3.ontologylaw.contract.OpportunityContractReader.databaseBacked().takeoverFacts(x,seed.tenant(),opportunity.id())).payload());}
            var preserved=scalar("select string_agg(task_occurrence_id::text||':'||revision||':'||state,',' order by task_occurrence_id) from responsibility.task_occurrence where tenant_id=? and subject_id=? and business_purpose_code<>'PROGRESS_OPPORTUNITY'",seed.tenant(),opportunity.id());
            var key=UUID.randomUUID();String path="/internal/v1/sales-chain-repairs/repair-superseded-opportunity-task";
            var duplicate=new TreeMap<String,Object>(repair);var facts=new ArrayList<Object>((List<?>)repair.get("takeoverFacts"));facts.add(facts.getFirst());duplicate.put("takeoverFacts",facts);
            assertEquals(400,internal(http,path,UUID.randomUUID(),duplicate).statusCode());
            var absent=new TreeMap<String,Object>(repair);absent.remove("draft");assertEquals(400,internal(http,path,UUID.randomUUID(),absent).statusCode());
            var fixed=internal(http,path,key,repair);assertEquals(200,fixed.statusCode(),fixed.body());var replay=internal(http,path,key,repair);assertEquals(200,replay.statusCode(),replay.body());assertSameReceipt(http.body(fixed),http.body(replay));
            assertEquals("CANCELLED",scalar("select state from responsibility.task_occurrence where tenant_id=? and task_occurrence_id=?",seed.tenant(),task));
            assertEquals(preserved,scalar("select string_agg(task_occurrence_id::text||':'||revision||':'||state,',' order by task_occurrence_id) from responsibility.task_occurrence where tenant_id=? and subject_id=? and business_purpose_code<>'PROGRESS_OPPORTUNITY'",seed.tenant(),opportunity.id()));
        }
    }
}
