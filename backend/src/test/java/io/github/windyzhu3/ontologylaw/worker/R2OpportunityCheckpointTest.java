package io.github.windyzhu3.ontologylaw.worker;

import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.execution.R2OpportunityCheckpointPort;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class R2OpportunityCheckpointTest {
    final R1WorkerTenantBindings.Binding binding=new R1WorkerTenantBindings.Binding(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"WORKER","a".repeat(64));
    final R1WorkerTenantBindings registry=new R1WorkerTenantBindings("MVP-2026-09-08.3",List.of(binding));
    final InternalApiClient.OpportunityKind kind=InternalApiClient.OpportunityKind.INITIAL;
    final InternalApiClient.OpportunityCandidate item=new InternalApiClient.InitialOpportunityCandidate(UUID.fromString("00000000-0000-5000-8000-000000000001"),UUID.randomUUID(),0);
    static final class Store implements R2OpportunityCheckpointPort {
        byte[] body; boolean busy, fail; int writes;
        public Session tryOpen(Key key)throws SQLException {
            if(fail)throw new SQLException("unavailable");if(busy)return null;busy=true;
            return new Session(){public byte[] load(){return body==null?null:body.clone();}public void save(byte[] value)throws SQLException{if(fail)throw new SQLException("unavailable");body=value.clone();writes++;}public void close(){busy=false;}};
        }
    }
    static final class Gateway implements R2OpportunityTaskScheduler.Gateway {
        int discoveries,commands; final List<InternalApiClient.OpportunityCandidate> attempts=new ArrayList<>();
        List<InternalApiClient.OpportunityCandidate> rows=List.of();int response=200;Runnable before=()->{};
        public InternalApiClient.OpportunityPage discover(R1WorkerTenantBindings.Binding b,InternalApiClient.OpportunityKind k,String c){discoveries++;return new InternalApiClient.OpportunityPage(200,rows,null);}
        public InternalApiClient.Result execute(R1WorkerTenantBindings.Binding b,InternalApiClient.OpportunityCandidate c){before.run();commands++;attempts.add(c);return new InternalApiClient.Result(response,"");}
    }
    @Test void restart_replays_uncertain_original_input_before_discovery(){
        var store=new Store();var first=new Gateway();first.rows=List.of(item);first.response=408;
        try(var scheduler=new R2OpportunityTaskScheduler(registry,first,Clock.fixed(Instant.parse("2026-09-14T00:00:00Z"),ZoneOffset.UTC),store)){scheduler.poll(binding,kind);}
        var second=new Gateway();try(var scheduler=new R2OpportunityTaskScheduler(registry,second,Clock.fixed(Instant.parse("2026-09-14T00:01:00Z"),ZoneOffset.UTC),store)){
            assertEquals(1,scheduler.poll(binding,kind));assertEquals(0,second.discoveries);assertEquals(List.of(item),second.attempts);
        }
    }
    @Test void intent_is_durable_before_network_and_lost_ack_write_replays_same_key(){
        var store=new Store();var api=new Gateway();api.rows=List.of(item);api.before=()->{assertNotNull(store.body);assertTrue(store.writes>0);store.fail=true;};
        try(var scheduler=new R2OpportunityTaskScheduler(registry,api,Clock.systemUTC(),store)){
            assertEquals(0,scheduler.poll(binding,kind));assertEquals(R2OpportunityTaskScheduler.Status.STORAGE_UNAVAILABLE,scheduler.snapshot(binding,kind).status());
        }
        store.fail=false;var recovered=new Gateway();try(var scheduler=new R2OpportunityTaskScheduler(registry,recovered,Clock.systemUTC(),store)){
            assertEquals(1,scheduler.poll(binding,kind));assertEquals(List.of(item),recovered.attempts);assertEquals(0,recovered.discoveries);
        }
    }
    @Test void corrupt_unavailable_or_locked_checkpoint_cannot_dispatch(){
        for(int scenario=0;scenario<3;scenario++){
            var store=new Store();if(scenario==0)store.body=new byte[]{1,2,3};if(scenario==1)store.fail=true;if(scenario==2)store.busy=true;
            var api=new Gateway();api.rows=List.of(item);
            try(var scheduler=new R2OpportunityTaskScheduler(registry,api,Clock.systemUTC(),store)){assertEquals(0,scheduler.poll(binding,kind));assertEquals(0,api.commands);assertEquals(0,api.discoveries);}
        }
    }
    @Test void restart_preserves_page_position_and_does_not_repeat_acknowledged_prefix(){
        var store=new Store();var api=new Gateway();var rows=new ArrayList<InternalApiClient.OpportunityCandidate>();
        for(int i=1;i<=11;i++)rows.add(new InternalApiClient.InitialOpportunityCandidate(UUID.fromString(String.format("00000000-0000-5000-8000-%012d",i)),UUID.randomUUID(),0));
        api.rows=rows;
        try(var scheduler=new R2OpportunityTaskScheduler(registry,api,Clock.systemUTC(),store)){assertEquals(10,scheduler.poll(binding,kind));}
        var resumed=new Gateway();try(var scheduler=new R2OpportunityTaskScheduler(registry,resumed,Clock.systemUTC(),store)){
            assertEquals(1,scheduler.poll(binding,kind));assertEquals(List.of(rows.getLast()),resumed.attempts);assertEquals(0,resumed.discoveries);
        }
    }
    @Test void due_input_roundtrips_exactly_and_wrong_kind_or_trailing_bytes_fail_closed()throws Exception {
        var due=new InternalApiClient.DueOpportunityCandidate(item.idempotencyKey(),item.opportunityId(),2,UUID.randomUUID(),3,UUID.randomUUID(),"A".repeat(43),UUID.randomUUID(),"A".repeat(43),"2026-09-14T00:00:00Z");
        var s=new R2OpportunityTaskScheduler.State();s.active=true;s.uncertain=true;s.page=new InternalApiClient.OpportunityPage(200,List.of(due),"next");s.failures=2;s.retryAt=Instant.parse("2026-09-14T00:01:00Z");
        var bytes=R2OpportunityCheckpointCodec.encode(s,InternalApiClient.OpportunityKind.DUE);var restored=new R2OpportunityTaskScheduler.State();
        R2OpportunityCheckpointCodec.restore(restored,bytes,InternalApiClient.OpportunityKind.DUE);
        assertEquals(List.of(due),restored.page.candidates());assertEquals(s.retryAt,restored.retryAt);assertTrue(restored.uncertain);
        assertThrows(java.io.IOException.class,()->R2OpportunityCheckpointCodec.restore(restored,bytes,kind));
        assertThrows(java.io.IOException.class,()->R2OpportunityCheckpointCodec.restore(restored,Arrays.copyOf(bytes,bytes.length+1),InternalApiClient.OpportunityKind.DUE));
        var damaged=bytes.clone();damaged[damaged.length/2]^=1;
        var store=new Store();store.body=damaged;var api=new Gateway();
        try(var scheduler=new R2OpportunityTaskScheduler(registry,api,Clock.systemUTC(),store)){
            assertEquals(0,scheduler.poll(binding,InternalApiClient.OpportunityKind.DUE));assertEquals(0,api.commands);assertEquals(0,api.discoveries);
        }
    }
}
