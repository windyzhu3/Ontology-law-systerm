package io.github.windyzhu3.ontologylaw.execution;

import java.sql.*;
import java.util.*;
/** Read-only deployment/capability boundary shared by the two exclusive runtime assemblies. */
public interface RuntimeDatabase {
    enum Role { API,WORKER }
    @FunctionalInterface interface Connections {Connection open()throws SQLException;}
    record JdbcLogin(String url,String username,char[] password) {
        public JdbcLogin {
            if(url==null||!url.matches("jdbc:postgresql://[^\\s@]+")||url.matches("(?i).*[?&](user|password)=.*")||username==null||!username.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")||password==null||password.length==0)
                throw new IllegalArgumentException("Invalid runtime JDBC configuration");
            password=password.clone();
        }
        public char[] password(){return password==null?null:password.clone();}
        public String toString(){return "RuntimeJdbcLogin[restricted]";}
    }
    static Connections jdbc(JdbcLogin login){return new io.github.windyzhu3.ontologylaw.execution.internal.persistence.JdbcRuntimeConnections(Objects.requireNonNull(login));}
    record Expected(String schemaVersion,byte[] releaseDigest,byte[] manifestHash) {
        public Expected {
            if(!"52-plus-2-v1.2".equals(schemaVersion)||!digest(releaseDigest)||!digest(manifestHash))throw new IllegalArgumentException("Invalid runtime expectations");
            releaseDigest=releaseDigest.clone();manifestHash=manifestHash.clone();
        }
        private static boolean digest(byte[] bytes){if(bytes==null||bytes.length!=32)return false;for(byte b:bytes)if(b!=0)return true;return false;}
        public byte[] releaseDigest(){return releaseDigest.clone();}public byte[] manifestHash(){return manifestHash.clone();}
        public String toString(){return "RuntimeExpectations[restricted]";}
    }
    Connection open()throws SQLException;
    boolean healthy();
    static RuntimeDatabase databaseBacked(Connections connections,Role role,Expected expected) {
        return new io.github.windyzhu3.ontologylaw.execution.internal.persistence.JooqRuntimeDatabase(Objects.requireNonNull(connections),Objects.requireNonNull(role),Objects.requireNonNull(expected));
    }
}
