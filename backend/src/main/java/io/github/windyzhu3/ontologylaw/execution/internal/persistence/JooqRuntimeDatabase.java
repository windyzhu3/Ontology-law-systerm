package io.github.windyzhu3.ontologylaw.execution.internal.persistence;

import io.github.windyzhu3.ontologylaw.execution.RuntimeDatabase;
import java.sql.*;
import java.util.*;
import java.security.MessageDigest;
import org.jooq.*;
import org.jooq.impl.DSL;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;

/** No migration/GRANT/DeploymentRuntime writer exists in the application boundary. */
public final class JooqRuntimeDatabase implements RuntimeDatabase {
    private final Connections connections;private final Role role;private final Expected expected;
    public JooqRuntimeDatabase(Connections connections,Role role,Expected expected){this.connections=connections;this.role=role;this.expected=expected;}
    private static DSLContext db(Connection c){return DSL.using(c,SQLDialect.POSTGRES,new org.jooq.conf.Settings().withExecuteLogging(false));}
    public Connection open()throws SQLException {
        Connection c=null;
        try {
            c=connections.open();if(!c.getAutoCommit())throw unavailable();capabilities(db(c));
            inTransaction(c,role==Role.API?Capability.QUERY:Capability.WORKER,x->{deployment(db(x));return null;});return c;
        }catch(SQLException|RuntimeException failure){if(c!=null)try{c.close();}catch(SQLException ignored){}throw unavailable();}
    }
    public boolean healthy(){try(var c=open()){return true;}catch(SQLException|RuntimeException unavailable){return false;}}
    private void deployment(DSLContext db)throws SQLException {
        var row=db.fetchOne("select operating_mode,schema_contract_version,active_release_digest,active_manifest_hash from platform_meta.deployment_state where deployment_state_key='PRIMARY'");
        if(row==null||!"ACTIVE".equals(row.get(0,String.class))||!expected.schemaVersion().equals(row.get(1,String.class))
                ||!MessageDigest.isEqual(expected.releaseDigest(),row.get(2,byte[].class))||!MessageDigest.isEqual(expected.manifestHash(),row.get(3,byte[].class)))throw unavailable();
    }
    private void capabilities(DSLContext db)throws SQLException {
        if(!truth(db,"select session_user=current_user"))throw unavailable();
        if(!truth(db,"select rolcanlogin and not (rolinherit or rolsuper or rolcreatedb or rolcreaterole or rolreplication or rolbypassrls) from pg_catalog.pg_roles where oid=session_user::regrole"))throw unavailable();
        var rows=db.fetch("select r.rolname,m.admin_option,m.inherit_option,m.set_option,r.rolcanlogin,r.rolsuper,r.rolcreatedb,r.rolcreaterole,r.rolreplication,r.rolbypassrls from pg_catalog.pg_auth_members m join pg_catalog.pg_roles r on r.oid=m.roleid where m.member=session_user::regrole");
        Set<String> required=role==Role.API?Set.of("law_app_command","law_app_query","law_audit_append"):Set.of("law_app_worker");
        if(rows.size()!=required.size()||!new HashSet<>(rows.getValues(0,String.class)).equals(required))throw unavailable();
        for(var row:rows){if(row.get(1,Boolean.class)||row.get(2,Boolean.class)||!row.get(3,Boolean.class))throw unavailable();for(int i=4;i<10;i++)if(row.get(i,Boolean.class))throw unavailable();}
        if(truth(db,"select exists(select 1 from pg_catalog.pg_auth_members parent join pg_catalog.pg_auth_members ours on parent.member=ours.roleid where ours.member=session_user::regrole)"))throw unavailable();
        if(!truth(db,"select has_database_privilege(session_user,current_database(),'CONNECT') and not has_database_privilege(session_user,current_database(),'CREATE,TEMPORARY')"))throw unavailable();
        var direct=db.fetch("select acl.privilege_type,acl.is_grantable from pg_catalog.pg_database d cross join lateral aclexplode(d.datacl) acl where d.datname=current_database() and acl.grantee=session_user::regrole");
        if(direct.size()!=1||!"CONNECT".equals(direct.getFirst().get(0,String.class))||direct.getFirst().get(1,Boolean.class))throw unavailable();
        if(truth(db,"select exists(select 1 from pg_catalog.pg_database where datname=current_database() and datdba=session_user::regrole)"))throw unavailable();
        if(truth(db,"select exists(select 1 from pg_catalog.pg_namespace n where n.nspname !~ '^pg_' and n.nspname<>'information_schema' and (n.nspowner=session_user::regrole or has_schema_privilege(session_user,n.oid,'USAGE,CREATE')))"))throw unavailable();
        // Direct/PUBLIC object grants are forbidden even without schema USAGE; ownership is also authority.
        if(truth(db,"select exists(select 1 from pg_catalog.pg_class c join pg_catalog.pg_namespace n on n.oid=c.relnamespace where n.nspname !~ '^pg_' and n.nspname<>'information_schema' and (c.relowner=session_user::regrole or exists(select 1 from aclexplode(c.relacl) a where a.grantee in (0,session_user::regrole::oid))))"))throw unavailable();
        if(truth(db,"select exists(select 1 from pg_catalog.pg_attribute a join pg_catalog.pg_class c on c.oid=a.attrelid join pg_catalog.pg_namespace n on n.oid=c.relnamespace cross join lateral aclexplode(a.attacl) acl where n.nspname !~ '^pg_' and n.nspname<>'information_schema' and acl.grantee in (0,session_user::regrole::oid))"))throw unavailable();
        if(truth(db,"select exists(select 1 from pg_catalog.pg_proc p join pg_catalog.pg_namespace n on n.oid=p.pronamespace where n.nspname !~ '^pg_' and n.nspname<>'information_schema' and (p.proowner=session_user::regrole or exists(select 1 from aclexplode(p.proacl) a where a.grantee in (0,session_user::regrole::oid))))"))throw unavailable();
    }
    private static boolean truth(DSLContext db,String sql){var row=db.fetchOne(sql);return row!=null&&Boolean.TRUE.equals(row.get(0,Boolean.class));}
    private static SQLException unavailable(){return new SQLException("Runtime unavailable","08006");}
}
