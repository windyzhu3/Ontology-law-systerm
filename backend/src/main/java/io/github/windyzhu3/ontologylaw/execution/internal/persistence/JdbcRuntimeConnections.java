package io.github.windyzhu3.ontologylaw.execution.internal.persistence;

import io.github.windyzhu3.ontologylaw.execution.RuntimeDatabase;
import java.sql.*;
import java.util.Properties;

/** JDBC technical factory only; no pools, migration or credential discovery. */
public final class JdbcRuntimeConnections implements RuntimeDatabase.Connections {
    private final RuntimeDatabase.JdbcLogin login;
    public JdbcRuntimeConnections(RuntimeDatabase.JdbcLogin login){this.login=login;}
    public Connection open()throws SQLException {
        char[] secret=login.password();var properties=new Properties();
        try {
            properties.setProperty("user",login.username());properties.setProperty("password",new String(secret));
            properties.setProperty("logServerErrorDetail","false");properties.setProperty("connectTimeout","10");properties.setProperty("socketTimeout","30");
            return DriverManager.getConnection(login.url(),properties);
        } catch(SQLException failure){throw new SQLException("Runtime database unavailable","08001");}
        finally{java.util.Arrays.fill(secret,'\0');properties.clear();}
    }
    public String toString(){return "RuntimeConnections[restricted]";}
}
