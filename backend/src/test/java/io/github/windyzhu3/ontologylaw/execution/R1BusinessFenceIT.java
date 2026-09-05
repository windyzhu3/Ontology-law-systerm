package io.github.windyzhu3.ontologylaw.execution;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class R1BusinessFenceIT extends CommandRuntimeIT {
    @Test void shared_reads_progress_and_other_tenant_writes_progress_but_same_tenant_write_waits_for_rollback()throws Exception {
        var fence=R1BusinessFence.databaseBacked();UUID tenant=UUID.randomUUID();
        try(var first=database.apiConnection();var second=database.apiConnection();var other=database.apiConnection();var writer=database.apiConnection();var observer=database.apiConnection();var pool=Executors.newFixedThreadPool(3)) {
            first.setAutoCommit(false);second.setAutoCommit(false);other.setAutoCommit(false);writer.setAutoCommit(false);
            fence.shared(first,tenant);
            pool.submit(()->{fence.shared(second,tenant);return true;}).get(10,TimeUnit.SECONDS);
            pool.submit(()->{fence.exclusive(other,UUID.randomUUID());return true;}).get(10,TimeUnit.SECONDS);
            int pid=Integer.parseInt(scalar(writer,"select pg_backend_pid()"));var entered=new CountDownLatch(1);
            var work=pool.submit(()->{entered.countDown();fence.exclusive(writer,tenant);return true;});
            try {assertTrue(entered.await(10,TimeUnit.SECONDS));awaitBlocked(observer,pid);first.rollback();awaitBlocked(observer,pid);}
            finally {first.rollback();second.rollback();other.rollback();}
            assertTrue(work.get(10,TimeUnit.SECONDS));writer.rollback();
            // Rollback releases the exclusive lock; a real read can then enter.
            pool.submit(()->{fence.shared(second,tenant);return true;}).get(10,TimeUnit.SECONDS);second.rollback();
        }
    }
    @Test void exclusive_writer_blocks_same_tenant_read_until_commit()throws Exception {
        var fence=R1BusinessFence.databaseBacked();var tenant=UUID.randomUUID();
        try(var writer=database.apiConnection();var reader=database.apiConnection();var observer=database.apiConnection();var pool=Executors.newSingleThreadExecutor()) {
            writer.setAutoCommit(false);reader.setAutoCommit(false);fence.exclusive(writer,tenant);
            int pid=Integer.parseInt(scalar(reader,"select pg_backend_pid()"));var started=new CountDownLatch(1);
            var work=pool.submit(()->{started.countDown();fence.shared(reader,tenant);return true;});
            try {assertTrue(started.await(10,TimeUnit.SECONDS));awaitBlocked(observer,pid);}finally{writer.commit();}
            assertTrue(work.get(10,TimeUnit.SECONDS));reader.rollback();
        }
    }
    @Test void fence_rejects_autocommit_and_non_read_committed_transactions()throws Exception {
        var fence=R1BusinessFence.databaseBacked();try(var c=database.apiConnection()) {
            assertThrows(SQLException.class,()->fence.shared(c,UUID.randomUUID()));
            c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);c.setAutoCommit(false);
            assertThrows(SQLException.class,()->fence.exclusive(c,UUID.randomUUID()));c.rollback();
        }
    }
    static long lockKey(UUID tenant)throws Exception {
        return java.nio.ByteBuffer.wrap(java.security.MessageDigest.getInstance("SHA-256").digest(
                ("R1_BUSINESS_TENANT_LOCK_V1:"+tenant).getBytes(java.nio.charset.StandardCharsets.UTF_8))).getLong();
    }
    static void readFence(Connection c,UUID tenant)throws Exception {
        try(var p=c.prepareStatement("select pg_advisory_xact_lock_shared(?)")){p.setLong(1,lockKey(tenant));p.execute();}
    }
    static void awaitBlocked(Connection observer,int pid)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<deadline) {
            try(var p=observer.prepareStatement("select exists(select 1 from pg_locks where pid=? and not granted and locktype='advisory')")) {
                p.setInt(1,pid);try(var r=p.executeQuery()){r.next();if(r.getBoolean(1))return;}
            }
            Thread.onSpinWait();
        }
        fail("Command did not wait on the tenant business fence");
    }
    @Test void runtime_cannot_enter_handler_roots_while_current_card_read_holds_tenant_fence()throws Exception {
        var roots=new CountDownLatch(1);var resolved=new CountDownLatch(1);
        Handler h=new Handler(){
            @Override public Context resolve(Connection c,CommandEnvelope e)throws SQLException {var value=super.resolve(c,e);resolved.countDown();return value;}
            @Override public void lockRoots(Connection c,CommandEnvelope e,Context ctx)throws SQLException {roots.countDown();super.lockRoots(c,e,ctx);}
        };
        try(var reader=database.apiConnection();var writer=database.apiConnection();var observer=database.apiConnection();var pool=Executors.newSingleThreadExecutor()) {
            reader.setAutoCommit(false);readFence(reader,h.seed.tenant());
            int pid=Integer.parseInt(scalar(writer,"select pg_backend_pid()"));
            var work=pool.submit(()->runtime(h).execute(writer,h.envelope(UUID.randomUUID(),Map.of())));
            try {
                assertTrue(resolved.await(10,TimeUnit.SECONDS));
                awaitBlocked(observer,pid);assertEquals(1L,roots.getCount());
            } finally {reader.rollback();}
            assertInstanceOf(CommandOutcome.class,work.get(15,TimeUnit.SECONDS));assertEquals(0L,roots.getCount());
        }
    }
}
