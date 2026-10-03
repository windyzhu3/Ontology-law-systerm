package io.github.windyzhu3.ontologylaw.execution;

import java.sql.*;
import java.util.*;
/** Read-only deployment/capability boundary shared by the two exclusive runtime assemblies. */
public interface RuntimeDatabase extends AutoCloseable {
    default void close() {}
    enum Role { API,WORKER }
    @FunctionalInterface interface Connections extends AutoCloseable {Connection open()throws SQLException;default void close(){}}
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
    static Connections jdbc(JdbcLogin login,int maximum){return new io.github.windyzhu3.ontologylaw.execution.internal.persistence.JdbcRuntimeConnections(Objects.requireNonNull(login),maximum);}
    record Expected(String schemaVersion,byte[] releaseDigest,byte[] manifestHash) {
        public Expected {
            if(!("52-plus-2-v1.2".equals(schemaVersion)||"52-plus-2-r2-v1".equals(schemaVersion)||"52-plus-2-r2-v2".equals(schemaVersion)||"52-plus-2-r2-v3".equals(schemaVersion)||"52-plus-2-r2-v4".equals(schemaVersion)||"52-plus-2-r2-v5".equals(schemaVersion)||"52-plus-2-r2-v6".equals(schemaVersion)||"52-plus-2-r2-v7".equals(schemaVersion)||"52-plus-2-r2-v8".equals(schemaVersion)||"52-plus-2-r2-v9".equals(schemaVersion)||"52-plus-2-r2-v10".equals(schemaVersion)||"52-plus-2-r2-v11".equals(schemaVersion)||"52-plus-2-r2-v12".equals(schemaVersion)||"52-plus-2-r2-v13".equals(schemaVersion)||"52-plus-2-r2-v14".equals(schemaVersion)||"52-plus-2-r2-v15".equals(schemaVersion)||"52-plus-2-r2-v16".equals(schemaVersion)||"52-plus-2-r2-v17".equals(schemaVersion)||"52-plus-2-r2-v18".equals(schemaVersion)||"52-plus-2-r2-v19".equals(schemaVersion)||"52-plus-2-r2-v20".equals(schemaVersion)||"52-plus-2-r2-v21".equals(schemaVersion)||"52-plus-2-r2-v22".equals(schemaVersion))||!digest(releaseDigest)||!digest(manifestHash))throw new IllegalArgumentException("Invalid runtime expectations");
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
