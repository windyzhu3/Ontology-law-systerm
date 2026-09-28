package io.github.windyzhu3.ontologylaw.api;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.worker.*;
import io.github.windyzhu3.ontologylaw.execution.R2OpportunityCheckpointPort;
import io.github.windyzhu3.ontologylaw.testing.TlsFixture;
import java.time.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class OwnerExceptionInternalHttpIT extends R1HttpFixture {
    private static final String ROOT="/internal/v1/opportunity-owner-exceptions";
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp;
    private Subject opportunity;
    private Actor opening()throws Exception {
        setupContact();var contact=execute(prepare(contact("CONNECTED_VALID")));
        opportunityProtection=OpportunityProgressProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES"));
        try(var c=database.apiConnection()){opportunity=inTransaction(c,Capability.COMMAND,x->{
            grant(x,"OPPORTUNITY_OWNER_EXCEPTION_READ");grant(x,"OPPORTUNITY_OWNER_EXCEPTION_RESOLVE");
            return EventOpportunityReader.databaseBacked().forContact(x,seed.tenant(),contact.resultFact().id()).selector();
        });}
        return credentialActor(PrincipalKind.SERVICE,"owner-exception-worker","OPPORTUNITY_OWNER_EXCEPTION_DISCOVER");
    }
    @Test void committed_observation_with_lost_response_restarts_from_durable_original_key()throws Exception {
        var service=opening();
        try(var http=new HttpHarness(service,new TlsFixture(temp));var client=http.workerClient()) {
            var bindings=new R1WorkerTenantBindings("MVP-2026-09-08.3",List.of(http.workerBinding));
            var port=R2OpportunityCheckpointPort.databaseBacked(database::workerConnection);var kind=InternalApiClient.OpportunityKind.OWNER_EXCEPTION;
            var raw=http.request("GET",ROOT+"/candidates?limit=50",null,Map.of());assertEquals(200,raw.statusCode(),raw.body());
            var discovered=client.opportunityCandidates(http.workerBinding,kind,null);assertEquals(200,discovered.status(),raw.body());assertEquals(1,discovered.candidates().size(),raw.body());
            var seen=new ArrayList<UUID>();
            var dropping=new R2OpportunityTaskScheduler.Gateway(){
                public InternalApiClient.OpportunityPage discover(R1WorkerTenantBindings.Binding b,InternalApiClient.OpportunityKind k,String cursor){return client.opportunityCandidates(b,k,cursor);}
                public InternalApiClient.Result execute(R1WorkerTenantBindings.Binding b,InternalApiClient.OpportunityCandidate candidate){
                    seen.add(candidate.idempotencyKey());var response=client.maintainOpportunity(b,candidate);assertEquals(200,response.status(),response.body());
                    return new InternalApiClient.Result(408,"");
                }
            };
            try(var first=new R2OpportunityTaskScheduler(bindings,dropping,Clock.systemUTC(),port)){assertEquals(0,first.poll(http.workerBinding,kind));assertEquals(1,first.snapshot(http.workerBinding,kind).pending());}
            assertEquals("1",scalar("select count(*) from opportunity.owner_exception where tenant_id=? and is_current",seed.tenant()));
            var before=counts();
            try(var next=new R2OpportunityTaskScheduler(bindings,client,Clock.offset(Clock.systemUTC(),Duration.ofMinutes(1)),port,Set.of(kind))){
                assertEquals(1,next.poll(http.workerBinding,kind));assertEquals(0,next.snapshot(http.workerBinding,kind).pending());
            }
            assertEquals(before,counts());assertEquals(1,seen.size());
            assertEquals("1",scalar("select count(*) from opportunity.owner_exception where tenant_id=?",seed.tenant()));
        }
    }
    @Test void internal_observation_enforces_mtls_and_closed_request_fields()throws Exception {
        var service=opening();var body=Map.of("opportunityId",opportunity.id().toString(),"expectedOpportunityRevision",opportunity.revision());
        try(var http=new HttpHarness()){assertEquals(401,http.request("GET",ROOT+"/candidates",null,Map.of()).statusCode());}
        try(var http=new HttpHarness(service,new TlsFixture(temp))){
            assertEquals(400,http.request("POST",ROOT+"/commands/observe",body,Map.of()).statusCode());
            var extra=new HashMap<String,Object>(body);extra.put("receiverAppointmentId",seed.appointment().toString());
            assertEquals(400,http.request("POST",ROOT+"/commands/observe",extra,Map.of("Idempotency-Key",UUID.randomUUID().toString())).statusCode());
            assertEquals(400,http.request("GET",ROOT+"/candidates?tenantId="+seed.tenant(),null,Map.of()).statusCode());
            assertEquals("0",scalar("select count(*) from opportunity.owner_exception where tenant_id=?",seed.tenant()));
        }
    }
}
