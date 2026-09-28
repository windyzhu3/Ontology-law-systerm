package io.github.windyzhu3.ontologylaw.execution.internal.persistence;

import io.github.windyzhu3.ontologylaw.execution.RuntimeDatabase;
import com.zaxxer.hikari.*;
import java.sql.*;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded technical pool; every borrow still passes RuntimeDatabase capability/deployment checks. */
public final class JdbcRuntimeConnections implements RuntimeDatabase.Connections {
    private final HikariDataSource pool;
    public JdbcRuntimeConnections(RuntimeDatabase.JdbcLogin login){this(login,8);}
    public JdbcRuntimeConnections(RuntimeDatabase.JdbcLogin login,int maximum){
        if(maximum<1||maximum>32)throw new IllegalArgumentException("Invalid runtime pool bound");
        var config=new HikariConfig();char[] secret=login.password();
        try {
            config.setJdbcUrl(login.url());config.setUsername(login.username());config.setPassword(new String(secret));
            config.setMaximumPoolSize(maximum);config.setMinimumIdle(0);config.setConnectionTimeout(3000);
            config.setValidationTimeout(1000);config.setIdleTimeout(60000);config.setMaxLifetime(600000);
            config.setInitializationFailTimeout(-1);config.setRegisterMbeans(false);
            config.addDataSourceProperty("logServerErrorDetail","false");config.addDataSourceProperty("connectTimeout","3");
            config.addDataSourceProperty("socketTimeout","30");config.addDataSourceProperty("tcpKeepAlive","true");
            // Preserve one audit row per fact and the surrounding transaction, while
            // letting PostgreSQL receive each JDBC batch as bounded multi-row inserts.
            config.addDataSourceProperty("reWriteBatchedInserts","true");
            pool=new HikariDataSource(config);
        } finally {java.util.Arrays.fill(secret,'\0');}
    }
    public Connection open()throws SQLException {
        final Connection c;
        try{c=pool.getConnection();}catch(SQLException failure){throw unavailable();}
        var closed=new AtomicBoolean();
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            if(method.getName().equals("close")) {
                if(closed.compareAndSet(false,true)) {
                    try {
                        // ROLLBACK first: never commit abandoned work while restoring auto-commit.
                        if(!c.getAutoCommit()){c.rollback();c.setAutoCommit(true);}
                        try(var s=c.createStatement()){s.execute("RESET ROLE");s.execute("RESET ALL");s.execute("UNLISTEN *");s.execute("SELECT pg_advisory_unlock_all()");}
                    } catch(SQLException failure){pool.evictConnection(c);throw unavailable();}
                    finally{c.close();}
                }
                return null;
            }
            if(method.getName().equals("isClosed"))return closed.get()||c.isClosed();
            if(closed.get())throw new SQLException("Connection closed","08003");
            try{return method.invoke(c,args);}catch(InvocationTargetException failure){throw failure.getCause();}
        });
    }
    private static SQLException unavailable(){return new SQLException("Runtime database unavailable","08001");}
    public void close(){pool.close();}
    public String toString(){return "RuntimeConnections[restricted]";}
}
