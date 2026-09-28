package io.github.windyzhu3.ontologylaw.api;

import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Observes real JDBC execution; never substitutes authorization, Owner reads, or Audit storage. */
final class ReadConnectionProbe implements InvocationHandler {
    final Connection delegate;
    final List<String> roles=new CopyOnWriteArrayList<>();
    final AtomicInteger inserts=new AtomicInteger();
    final AtomicInteger auditRows=new AtomicInteger();
    final AtomicInteger isolationReads=new AtomicInteger();
    final List<String> statements=new CopyOnWriteArrayList<>();
    final java.util.concurrent.atomic.LongAdder sqlNanos=new java.util.concurrent.atomic.LongAdder();
    final CountDownLatch auditReached=new CountDownLatch(1),auditContinue=new CountDownLatch(1),commitReached=new CountDownLatch(1),commitContinue=new CountDownLatch(1);
    int pauseAuditAt=0;boolean pauseCommit=false,loseCommitAck=false;
    String commitAckSqlState="08006";
    ReadConnectionProbe(Connection delegate){this.delegate=delegate;}
    Connection connection(){return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},this);}
    public Object invoke(Object proxy,Method method,Object[] args)throws Throwable {
        if(method.getName().equals("getTransactionIsolation"))isolationReads.incrementAndGet();
        if(method.getName().equals("commit")) {
            commitReached.countDown();if(pauseCommit)await(commitContinue);
            Object result=call(delegate,method,args);if(loseCommitAck)throw new SQLException("Synthetic commit acknowledgement unavailable",commitAckSqlState);return result;
        }
        Object result=call(delegate,method,args);
        if(result instanceof Statement statement) {
            String prepared=args!=null&&args.length>0&&args[0] instanceof String s?s:null;
            Class<?> type=result instanceof PreparedStatement?PreparedStatement.class:Statement.class;
            var bindings=new AtomicInteger();
            return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->{
                String sql=prepared!=null?prepared:a!=null&&a.length>0&&a[0] instanceof String s?s:"";
                if(m.getName().equals("addBatch"))bindings.incrementAndGet();
                if(m.getName().equals("clearBatch"))bindings.set(0);
                if(m.getName().startsWith("execute")) {
                    statements.add(sql);
                    if(sql.startsWith("SET LOCAL ROLE "))roles.add(sql.substring(15));
                    if(sql.toLowerCase(Locale.ROOT).startsWith("insert into \"audit\".\"audit_entry\"")) {
                        auditRows.addAndGet(m.getName().contains("Batch")?bindings.getAndSet(0):1);
                        int n=inserts.incrementAndGet();if(n==pauseAuditAt){auditReached.countDown();await(auditContinue);}
                    }
                }
                long start=System.nanoTime();
                try{return call(statement,m,a);}finally{if(m.getName().startsWith("execute"))sqlNanos.add(System.nanoTime()-start);}
            });
        }
        return result;
    }
    static Object call(Object target,Method method,Object[] args)throws Throwable {
        try{return method.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}
    }
    private static void await(CountDownLatch latch)throws SQLException {
        try{if(!latch.await(15,TimeUnit.SECONDS))throw new SQLException("Synthetic latch timed out","57014");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new SQLException("Synthetic latch interrupted","57014");}
    }
    void release(){auditContinue.countDown();commitContinue.countDown();}
}
