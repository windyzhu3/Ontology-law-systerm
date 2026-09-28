package io.github.windyzhu3.ontologylaw.worker;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class OwnerExceptionCheckpointTest {
    @Test void restricted_diagnostics_advance_authorized_work_but_never_report_ready()throws Exception {
        var binding=new R1WorkerTenantBindings.Binding(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"WORKER","a".repeat(64));
        var registry=new R1WorkerTenantBindings("MVP-2026-09-08.3",List.of(binding));var store=new R2OpportunityCheckpointTest.Store();
        var item=new InternalApiClient.OwnerExceptionCandidate(UUID.fromString("00000000-0000-5000-8000-000000000003"),UUID.randomUUID(),0);
        var attempts=new ArrayList<UUID>();var gateway=new R2OpportunityTaskScheduler.Gateway(){
            public InternalApiClient.OpportunityPage discover(R1WorkerTenantBindings.Binding b,InternalApiClient.OpportunityKind k,String c){return new InternalApiClient.OpportunityPage(200,List.of(item),null,1);}
            public InternalApiClient.Result execute(R1WorkerTenantBindings.Binding b,InternalApiClient.OpportunityCandidate c){attempts.add(c.idempotencyKey());return new InternalApiClient.Result(200,"");}
        };
        try(var scheduler=new R2OpportunityTaskScheduler(registry,gateway,Clock.fixed(Instant.EPOCH,ZoneOffset.UTC),store)){
            assertEquals(1,scheduler.poll(binding,InternalApiClient.OpportunityKind.OWNER_EXCEPTION));
            assertEquals(R2OpportunityTaskScheduler.Status.DEGRADED,scheduler.snapshot(binding,InternalApiClient.OpportunityKind.OWNER_EXCEPTION).status());assertEquals(List.of(item.idempotencyKey()),attempts);
        }
        var restored=new R2OpportunityTaskScheduler.State();R2OpportunityCheckpointCodec.restore(restored,store.body,InternalApiClient.OpportunityKind.OWNER_EXCEPTION);
        assertTrue(restored.blocked);assertEquals(R2OpportunityTaskScheduler.Status.DEGRADED,restored.snapshot.status());
        restored.active=true;restored.page=new InternalApiClient.OpportunityPage(200,List.of(item),null,2);
        var roundTrip=new R2OpportunityTaskScheduler.State();R2OpportunityCheckpointCodec.restore(roundTrip,R2OpportunityCheckpointCodec.encode(restored,InternalApiClient.OpportunityKind.OWNER_EXCEPTION),InternalApiClient.OpportunityKind.OWNER_EXCEPTION);
        assertEquals(2,roundTrip.page.diagnostics());
    }
    @Test void restartRecoversOriginalObservationBeforeAnotherScan() {
        var binding=new R1WorkerTenantBindings.Binding(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"WORKER","a".repeat(64));
        var registry=new R1WorkerTenantBindings("MVP-2026-09-08.3",List.of(binding));
        var item=new InternalApiClient.OwnerExceptionCandidate(UUID.fromString("00000000-0000-5000-8000-000000000001"),UUID.randomUUID(),7);
        var store=new R2OpportunityCheckpointTest.Store();var first=new R2OpportunityCheckpointTest.Gateway();first.rows=List.of(item);first.response=408;
        first.before=()->assertNotNull(store.body,"Observation intent must be durable before HTTP");
        try(var scheduler=new R2OpportunityTaskScheduler(registry,first,Clock.fixed(Instant.EPOCH,ZoneOffset.UTC),store)) {
            assertEquals(0,scheduler.poll(binding,InternalApiClient.OpportunityKind.OWNER_EXCEPTION));
        }
        var next=new R2OpportunityCheckpointTest.Gateway();
        try(var scheduler=new R2OpportunityTaskScheduler(registry,next,Clock.fixed(Instant.EPOCH.plusSeconds(60),ZoneOffset.UTC),store)) {
            assertEquals(1,scheduler.poll(binding,InternalApiClient.OpportunityKind.OWNER_EXCEPTION));
            assertEquals(List.of(item),next.attempts);assertEquals(0,next.discoveries);
        }
    }
    @Test void versionOneInitialCheckpointStillReadsButCannotBeReinterpretedAsObservation()throws Exception {
        var initial=new InternalApiClient.InitialOpportunityCandidate(UUID.fromString("00000000-0000-5000-8000-000000000002"),UUID.randomUUID(),3);
        var state=new R2OpportunityTaskScheduler.State();state.active=true;state.page=new InternalApiClient.OpportunityPage(200,List.of(initial),null);
        var encoded=R2OpportunityCheckpointCodec.encode(state,InternalApiClient.OpportunityKind.INITIAL);
        java.nio.ByteBuffer.wrap(encoded).putInt(0x52324331);
        var payload=Arrays.copyOf(encoded,encoded.length-32);
        var hash=java.security.MessageDigest.getInstance("SHA-256").digest(payload);System.arraycopy(hash,0,encoded,payload.length,hash.length);
        var restored=new R2OpportunityTaskScheduler.State();R2OpportunityCheckpointCodec.restore(restored,encoded,InternalApiClient.OpportunityKind.INITIAL);
        assertEquals(List.of(initial),restored.page.candidates());
        assertThrows(java.io.IOException.class,()->R2OpportunityCheckpointCodec.restore(new R2OpportunityTaskScheduler.State(),encoded,InternalApiClient.OpportunityKind.OWNER_EXCEPTION));
    }
}
