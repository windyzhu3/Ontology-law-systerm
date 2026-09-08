package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.testing.PostgresIntegrationTest;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.sql.*;
import org.junit.jupiter.api.*;

class RuntimeDatabaseIT extends PostgresIntegrationTest {
    final byte[] release=HexFormat.of().parseHex("11".repeat(32)),manifest=HexFormat.of().parseHex("22".repeat(32));
    RuntimeDatabase.Expected expected(){return new RuntimeDatabase.Expected("52-plus-2-v1.2",release,manifest);}
    void state(String mode,String schema,byte[] digest,byte[] hash)throws Exception {
        try(var c=database.migratorConnection()){sql(c,"update platform_meta.deployment_state set operating_mode=?,schema_contract_version=?,active_release_digest=?,active_manifest_hash=?,revision=revision+1,changed_at=clock_timestamp() where deployment_state_key='PRIMARY' and (operating_mode,schema_contract_version,active_release_digest,active_manifest_hash) is distinct from (?,?,?,?)",mode,schema,digest,hash,mode,schema,digest,hash);}
    }
    void active()throws Exception{state("ACTIVE","52-plus-2-v1.2",release,manifest);}
    @Test void deployment_jdbc_factory_opens_both_real_nonowner_login_roles_without_secret_diagnostics()throws Exception {
        active();for(var role:RuntimeDatabase.Role.values()) {
            String password=role==RuntimeDatabase.Role.API?database.apiPassword():database.workerPassword();
            var login=new RuntimeDatabase.JdbcLogin(database.jdbcUrl(),role==RuntimeDatabase.Role.API?"law_api_login":"law_worker_login",password.toCharArray());assertFalse(login.toString().contains(password));
            try(var c=RuntimeDatabase.databaseBacked(RuntimeDatabase.jdbc(login),role,expected()).open()){assertTrue(c.isValid(2));}
        }
    }
    @Test void assembly_validation_uses_only_query_read_committed_and_closes_acknowledged_connection()throws Exception {
        active();var seen=new java.util.concurrent.atomic.AtomicReference<Connection>();
        var gate=RuntimeDatabase.databaseBacked(database::apiConnection,RuntimeDatabase.Role.API,expected());
        String value=new R1AssemblyValidationRuntime().validate(gate,c->{seen.set(c);assertFalse(c.getAutoCommit());assertEquals(Connection.TRANSACTION_READ_COMMITTED,c.getTransactionIsolation());try(var statement=c.createStatement();var row=statement.executeQuery("select current_user")){row.next();return row.getString(1);}});
        assertEquals("law_app_query",value);assertTrue(seen.get().isClosed());
        var worker=RuntimeDatabase.databaseBacked(database::workerConnection,RuntimeDatabase.Role.WORKER,expected());assertThrows(SQLException.class,()->new R1AssemblyValidationRuntime().validate(worker,c->{fail("Worker must never execute QUERY validation");return null;}));
    }
    @Test void both_exact_login_capability_sets_open_fresh_connections_only_when_deployment_matches()throws Exception {
        active();for(var role:RuntimeDatabase.Role.values()) {
            var gate=RuntimeDatabase.databaseBacked(role==RuntimeDatabase.Role.API?database::apiConnection:database::workerConnection,role,expected());assertTrue(gate.healthy());
            try(var c=assertDoesNotThrow(gate::open)){assertTrue(c.getAutoCommit());try(var s=c.createStatement();var row=s.executeQuery("select session_user,current_user")){assertTrue(row.next());assertEquals(role==RuntimeDatabase.Role.API?"law_api_login":"law_worker_login",row.getString(1));assertEquals(row.getString(1),row.getString(2));}}
        }
    }
    @Test void running_gate_rechecks_mode_and_every_independent_expected_field_without_database_writes()throws Exception {
        active();var gate=RuntimeDatabase.databaseBacked(database::apiConnection,RuntimeDatabase.Role.API,expected());assertTrue(gate.healthy());
        for(var mode:List.of("BLOCKED","MAINTENANCE")){state(mode,"52-plus-2-v1.2",release,manifest);assertFalse(gate.healthy());assertThrows(SQLException.class,gate::open);active();assertTrue(gate.healthy());}
        state("ACTIVE","52-plus-2-v1.1",release,manifest);assertFalse(gate.healthy());
        state("ACTIVE","52-plus-2-v1.2",manifest,manifest);assertFalse(gate.healthy());
        state("ACTIVE","52-plus-2-v1.2",release,release);assertFalse(gate.healthy());
        try(var c=database.migratorConnection();var s=c.createStatement();var row=s.executeQuery("select revision from platform_meta.deployment_state")){row.next();long before=row.getLong(1);gate.healthy();gate.healthy();try(var again=s.executeQuery("select revision from platform_meta.deployment_state")){again.next();assertEquals(before,again.getLong(1));}}
    }
    @Test void wrong_runtime_login_and_owner_credentials_fail_closed()throws Exception {
        active();assertFalse(RuntimeDatabase.databaseBacked(database::workerConnection,RuntimeDatabase.Role.API,expected()).healthy());assertFalse(RuntimeDatabase.databaseBacked(database::apiConnection,RuntimeDatabase.Role.WORKER,expected()).healthy());
        assertFalse(RuntimeDatabase.databaseBacked(database::migratorConnection,RuntimeDatabase.Role.API,expected()).healthy());assertFalse(RuntimeDatabase.databaseBacked(database::adminConnection,RuntimeDatabase.Role.API,expected()).healthy());
    }
    @Test void login_inherit_extra_membership_and_direct_privilege_drift_are_rejected()throws Exception {
        active();var gate=RuntimeDatabase.databaseBacked(database::apiConnection,RuntimeDatabase.Role.API,expected());
        var changes=List.of(new String[]{"alter role law_api_login inherit","alter role law_api_login noinherit"},new String[]{"grant law_app_worker to law_api_login","revoke law_app_worker from law_api_login"},new String[]{"grant temporary on database law_contract_runtime to law_api_login","revoke temporary on database law_contract_runtime from law_api_login"},new String[]{"grant usage on schema identity to law_api_login","revoke usage on schema identity from law_api_login"},new String[]{"grant select on identity.principal to law_api_login","revoke select on identity.principal from law_api_login"});
        for(var change:changes){assertTrue(gate.healthy());try(var c=database.adminConnection()){sql(c,change[0]);}try{assertFalse(gate.healthy());assertThrows(SQLException.class,gate::open);}finally{try(var c=database.adminConnection()){sql(c,change[1]);}}}
        assertTrue(gate.healthy());
    }
    @Test void missing_malformed_zero_or_wrong_version_expectations_are_not_inferred_from_database() {
        assertThrows(RuntimeException.class,()->RuntimeDatabase.databaseBacked(database::apiConnection,RuntimeDatabase.Role.API,null));
        assertThrows(RuntimeException.class,()->new RuntimeDatabase.Expected(null,release,manifest));
        assertThrows(RuntimeException.class,()->new RuntimeDatabase.Expected("52-plus-2-v1.2",new byte[31],manifest));
        assertThrows(RuntimeException.class,()->new RuntimeDatabase.Expected("52-plus-2-v1.2",new byte[32],manifest));
        assertThrows(RuntimeException.class,()->new RuntimeDatabase.Expected("52-plus-2-v1.1",release,manifest));
    }
}
