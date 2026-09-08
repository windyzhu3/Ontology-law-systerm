package io.github.windyzhu3.ontologylaw.execution;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT;
import io.github.windyzhu3.ontologylaw.testing.PostgresIntegrationTest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class R1ProjectionOutboxIT extends PostgresIntegrationTest {
    @Test void reaper_commit_failure_returns_no_result_and_rolls_back_exhaustion()throws Exception{
        var s=AuthorizationServiceIT.seed(database);var id=seed(s.tenant(),"R1_PROJECTION",Instant.now().minusSeconds(30));
        for(int attempt=1;attempt<8;attempt++){assertTrue(port().retry(port().claim(s.tenant(),"PRIOR_WORKER",1).getFirst(),"NETWORK_ERROR"));past(id,"available_at");}
        var claim=port().claim(s.tenant(),"LAST_WORKER",1).getFirst();assertEquals(8,claim.attempt());past(id,"lease_until");var before=row(id);
        var failing=R1ProjectionOutboxPort.databaseBacked(()->{var raw=database.workerConnection();return (Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{if(method.getName().equals("commit"))throw new SQLException("REAPER_COMMIT_ACK_FAILURE");try{return method.invoke(raw,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}});});
        assertThrows(SQLException.class,()->failing.reap(s.tenant(),4));assertEquals(before,row(id));assertEquals(new R1ProjectionOutboxPort.ReapResult(1,1),port().reap(s.tenant(),4));assertEquals(new R1ProjectionOutboxPort.ReapResult(0,0),port().reap(s.tenant(),4));
    }
    @Test void locked_first_row_is_skipped_and_counter_overflow_rolls_back_whole_batch()throws Exception{
        var s=AuthorizationServiceIT.seed(database);var first=seed(s.tenant(),"R1_PROJECTION",Instant.now().minusSeconds(90));var second=seed(s.tenant(),"R1_PROJECTION",Instant.now().minusSeconds(80));
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var holder=executor.submit(()->{try(var c=database.workerConnection()){return inTransaction(c,Capability.WORKER,x->{try(var p=x.prepareStatement("select domain_event_outbox_id from execution.domain_event_outbox where domain_event_outbox_id=? for update")){p.setObject(1,first);p.executeQuery().close();}locked.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new SQLException(interrupted);}return null;});}});
            assertTrue(locked.await(5,TimeUnit.SECONDS));var claim=port().claim(s.tenant(),"WORKER_B",4);assertEquals(List.of(second),claim.stream().map(R1ProjectionOutboxPort.Claim::outboxId).toList());release.countDown();holder.get(5,TimeUnit.SECONDS);assertEquals(first,port().claim(s.tenant(),"WORKER_A",1).getFirst().outboxId());
        }finally{release.countDown();}
        var tenant=AuthorizationServiceIT.seed(database).tenant();var early=seed(tenant,"R1_PROJECTION",Instant.now().minusSeconds(90));var overflow=seed(tenant,"R1_PROJECTION",Instant.now().minusSeconds(80));
        try(var c=database.adminConnection()){c.setAutoCommit(false);sql(c,"set local session_replication_role=replica");sql(c,"update execution.domain_event_outbox set revision=9007199254740991 where domain_event_outbox_id=?",overflow);c.commit();}
        var beforeEarly=row(early);var beforeOverflow=row(overflow);assertThrows(Exception.class,()->port().claim(tenant,"WORKER_A",4));assertEquals(beforeEarly,row(early));assertEquals(beforeOverflow,row(overflow));
    }
    @Test void every_stale_tuple_is_inert_and_eighth_expired_attempt_exhausts_without_redrive()throws Exception{
        var s=AuthorizationServiceIT.seed(database);var id=seed(s.tenant(),"R1_PROJECTION",Instant.now().minusSeconds(30));var claim=port().claim(s.tenant(),"WORKER_A",1).getFirst();
        for(var stale:List.of(new R1ProjectionOutboxPort.Claim(claim.tenantId(),id,claim.eventId(),claim.revision()+1,claim.leaseOwner(),claim.fencingToken(),1,claim.leaseUntil()),new R1ProjectionOutboxPort.Claim(claim.tenantId(),id,claim.eventId(),claim.revision(),claim.leaseOwner(),claim.fencingToken()+1,1,claim.leaseUntil()),new R1ProjectionOutboxPort.Claim(claim.tenantId(),id,UUID.randomUUID(),claim.revision(),claim.leaseOwner(),claim.fencingToken(),1,claim.leaseUntil()))){var before=row(id);assertFalse(port().ack(stale));assertFalse(port().retry(stale,"NETWORK_ERROR"));assertFalse(port().exhaust(stale,"PROJECTION_EVENT_INVALID"));assertEquals(before,row(id));}
        for(int attempt=1;attempt<=8;attempt++){assertEquals(attempt,claim.attempt());past(id,"lease_until");var reaped=port().reap(s.tenant(),4);assertEquals(new R1ProjectionOutboxPort.ReapResult(1,attempt==8?1:0),reaped);assertEquals(new R1ProjectionOutboxPort.ReapResult(0,0),port().reap(s.tenant(),4));assertFalse(port().ack(claim));assertEquals(attempt==8?"EXHAUSTED":"PENDING",row(id).get("status"));if(attempt<8){past(id,"available_at");claim=port().claim(s.tenant(),"WORKER_RESTART",1).getFirst();}}
        assertTrue(port().claim(s.tenant(),"WORKER_RESTART_2",4).isEmpty());assertEquals(8,row(id).get("attempt_count"));assertEquals(16L,row(id).get("revision"));
    }
    R1ProjectionOutboxPort port(){return R1ProjectionOutboxPort.databaseBacked(database::workerConnection);}
    UUID seed(UUID tenant,String queue,Instant available)throws Exception {
        UUID event=UUID.randomUUID(),outbox=UUID.randomUUID();
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            sql(x,"insert into execution.domain_event (tenant_id,domain_event_id,event_type,event_schema_version,event_payload,payload_digest,command_id,correlation_id,occurred_at,source_fact_type,source_fact_id,source_fact_revision) values (?,?,'LeadCapturedV1',1,'{}',decode('44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a','hex'),?,?,clock_timestamp(),'lead.lead',?,0)",tenant,event,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
            sql(x,"insert into execution.domain_event_outbox (tenant_id,domain_event_outbox_id,domain_event_id,queue_owner,status,available_at) values (?,?,?,?,'PENDING',?)",tenant,outbox,event,queue,available.atOffset(ZoneOffset.UTC));return null;
        });}return outbox;
    }
    Map<String,Object> row(UUID id)throws Exception {
        try(var c=database.workerConnection()){return inTransaction(c,Capability.WORKER,x->{
            try(var p=x.prepareStatement("select status,revision,fencing_token,attempt_count,lease_owner,lease_until,available_at,delivered_at,last_error_code from execution.domain_event_outbox where domain_event_outbox_id=?")){
                p.setObject(1,id);try(var r=p.executeQuery()){assertTrue(r.next());var m=new HashMap<String,Object>();for(int i=1;i<=9;i++)m.put(r.getMetaData().getColumnLabel(i),i>=6&&i<=8?r.getObject(i,OffsetDateTime.class):r.getObject(i));return m;}
            }
        });}
    }
    /** Only fixture time is moved; production time predicates and all transitions stay real PostgreSQL. */
    void past(UUID id,String column)throws Exception {
        assertTrue(Set.of("lease_until","available_at").contains(column));
        try(var c=database.adminConnection()) {c.setAutoCommit(false);sql(c,"set local session_replication_role=replica");
            sql(c,"update execution.domain_event_outbox set "+column+"=clock_timestamp()-interval '1 second' where domain_event_outbox_id=?",id);c.commit();}
    }
    @Test void claims_only_due_tenant_projection_rows_and_ack_is_exact_fenced_terminal_cas()throws Exception {
        var s=AuthorizationServiceIT.seed(database);var other=AuthorizationServiceIT.seed(database);
        var id=seed(s.tenant(),"R1_PROJECTION",Instant.now().minusSeconds(30));seed(s.tenant(),"OTHER",Instant.now().minusSeconds(40));
        seed(s.tenant(),"R1_PROJECTION",Instant.now().plusSeconds(120));seed(other.tenant(),"R1_PROJECTION",Instant.now().minusSeconds(50));
        var claims=port().claim(s.tenant(),"WORKER_A",4);assertEquals(1,claims.size());var claim=claims.getFirst();
        assertEquals(id,claim.outboxId());assertEquals(1,claim.revision());assertEquals(1,claim.fencingToken());assertEquals(1,claim.attempt());
        assertTrue(claim.leaseUntil().isAfter(Instant.now().plusSeconds(50)));
        assertFalse(port().ack(new R1ProjectionOutboxPort.Claim(claim.tenantId(),id,claim.eventId(),claim.revision(),"WORKER_B",claim.fencingToken(),1,claim.leaseUntil())));
        assertFalse(port().ack(new R1ProjectionOutboxPort.Claim(other.tenant(),id,claim.eventId(),claim.revision(),claim.leaseOwner(),claim.fencingToken(),1,claim.leaseUntil())));
        assertTrue(port().ack(claim));assertFalse(port().ack(claim));assertFalse(port().retry(claim,"NETWORK_ERROR"));
        var row=row(id);assertEquals("DELIVERED",row.get("status"));assertEquals(2L,row.get("revision"));assertNull(row.get("lease_owner"));assertNull(row.get("lease_until"));assertNull(row.get("last_error_code"));assertNotNull(row.get("delivered_at"));
    }
    @Test void skip_locked_workers_never_claim_the_same_row_and_respect_batch_bound()throws Exception {
        var s=AuthorizationServiceIT.seed(database);for(int i=0;i<9;i++)seed(s.tenant(),"R1_PROJECTION",Instant.now().minusSeconds(30));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);var a=pool.submit(()->{gate.await();return port().claim(s.tenant(),"WORKER_A",4);});
            var b=pool.submit(()->{gate.await();return port().claim(s.tenant(),"WORKER_B",4);});gate.countDown();
            var first=a.get(10,TimeUnit.SECONDS);var second=b.get(10,TimeUnit.SECONDS);assertEquals(4,first.size());assertEquals(4,second.size());
            var ids=new HashSet<UUID>();first.forEach(c->assertTrue(ids.add(c.outboxId())));second.forEach(c->assertTrue(ids.add(c.outboxId())));
        }
        assertThrows(IllegalArgumentException.class,()->port().claim(s.tenant(),"WORKER_A",5));
    }
    @Test void expired_claim_is_only_reaped_then_old_result_is_inert()throws Exception {
        var s=AuthorizationServiceIT.seed(database);var id=seed(s.tenant(),"R1_PROJECTION",Instant.now().minusSeconds(30));
        var first=port().claim(s.tenant(),"WORKER_A",1);assertEquals(1,first.size());var claim=first.getFirst();
        assertEquals(new R1ProjectionOutboxPort.ReapResult(0,0),port().reap(s.tenant(),4));past(id,"lease_until");
        assertFalse(port().ack(claim));assertFalse(port().retry(claim,"NETWORK_ERROR"));assertFalse(port().exhaust(claim,"PROJECTION_EVENT_INVALID"));
        assertEquals(new R1ProjectionOutboxPort.ReapResult(1,0),port().reap(s.tenant(),4));assertEquals("PENDING",row(id).get("status"));assertEquals(2L,row(id).get("revision"));
        assertFalse(port().ack(claim));past(id,"available_at");var next=port().claim(s.tenant(),"WORKER_B",1).getFirst();
        assertEquals(2,next.attempt());assertEquals(2,next.fencingToken());assertEquals(3,next.revision());assertTrue(port().ack(next));
    }
    @Test void retry_schedule_counts_eight_cumulative_claims_and_worker_cannot_redrive()throws Exception {
        var s=AuthorizationServiceIT.seed(database);var id=seed(s.tenant(),"R1_PROJECTION",Instant.now().minusSeconds(30));
        long[] seconds={1,5,30,120,600,1800,7200};
        for(int attempt=1;attempt<=8;attempt++) {
            var claims=port().claim(s.tenant(),"WORKER_A",1);assertEquals(1,claims.size());var claim=claims.getFirst();assertEquals(attempt,claim.attempt());
            var before=Instant.now();assertTrue(port().retry(claim,"NETWORK_ERROR"));var values=row(id);
            assertEquals(attempt==8?"EXHAUSTED":"PENDING",values.get("status"));assertEquals((long)attempt*2,values.get("revision"));assertNull(values.get("lease_until"));
            if(attempt<8){var available=((OffsetDateTime)values.get("available_at")).toInstant();assertTrue(available.isAfter(before.plusSeconds(seconds[attempt-1]-1)));assertTrue(available.isBefore(Instant.now().plusSeconds(seconds[attempt-1]+1)));past(id,"available_at");}
        }
        assertTrue(port().claim(s.tenant(),"WORKER_B",1).isEmpty());
        try(var c=database.workerConnection()){assertThrows(Exception.class,()->inTransaction(c,Capability.WORKER,x->{sql(x,"update execution.domain_event_outbox set status='PENDING',revision=revision+1 where domain_event_outbox_id=?",id);return null;}));}
        assertEquals("EXHAUSTED",row(id).get("status"));
    }
}
