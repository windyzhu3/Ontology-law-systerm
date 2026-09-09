package io.github.windyzhu3.ontologylaw.identity;

import io.github.windyzhu3.ontologylaw.testing.*;
import io.github.windyzhu3.ontologylaw.api.security.KeycloakDirectoryReader;
import io.github.windyzhu3.ontologylaw.execution.IdentityBootstrapRuntime;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real directory plus existing DB capabilities; target identities are created only by offline bootstrap. */
class IdentityBootstrapIT extends PostgresIntegrationTest {
    KeycloakFixture idp;
    @BeforeAll void identity()throws Exception{idp=new KeycloakFixture().start();}
    @AfterAll void stopIdentity(){if(idp!=null)idp.close();}
    @Test void dry_run_is_read_only_then_exact_original_manifest_replay_is_atomic_no_change()throws Exception {
        {
            UUID tenant=UUID.randomUUID();byte[] candidateKey=new byte[32],subjectKey=new byte[32];new java.security.SecureRandom().nextBytes(candidateKey);new java.security.SecureRandom().nextBytes(subjectKey);
            var directory=KeycloakDirectoryReader.isolatedLoopback(new KeycloakDirectoryReader.Trust(idp.issuer(),"task92-directory",idp.directorySecret));
            var candidates=new BootstrapCandidateProtection("bootstrap-test-v1",Map.of("bootstrap-test-v1",candidateKey));
            var binding=new BootstrapCandidateProtection.Binding("Approved synthetic test operator","BOOTSTRAP_TEST","TASK92",idp.issuer());
            Instant issued=Instant.now().minusSeconds(295);String selector=candidates.issue(binding,idp.username,directory,issued);
            var manifest=new IdentityBootstrapService.Manifest("R1_IDENTITY_BOOTSTRAP_V1",UUID.randomUUID(),binding.tenantCode(),"Synthetic tenant","ROOT","Synthetic root",binding.provider(),binding.issuer(),selector,"Synthetic founder",Instant.now().minusSeconds(60),binding.operatorAssertion());
            var runtime=new IdentityBootstrapRuntime(tenant,binding,candidates,new ExternalSubjectProtection(t->subjectKey),directory,AuditAppender.databaseBacked("TASK92_BOOTSTRAP_IT"));
            try(var c=database.apiConnection()){var dry=assertDoesNotThrow(()->runtime.run(c,manifest,true));assertEquals("DRY_RUN",dry.mode());assertEquals(4,dry.plannedDelta().get("authority_grant"));}
            assertEquals(0,count("identity.tenant",tenant));
            IdentityBootstrapRuntime.Outcome created;try(var c=database.apiConnection()){created=runtime.run(c,manifest,false);assertEquals("CREATED",created.mode());assertNotNull(created.receiptId());}
            assertCounts(tenant);
            try(var c=database.apiConnection()){var replay=runtime.run(c,manifest,false);assertEquals("VERIFIED_ORIGINAL",replay.mode());assertEquals(created.receiptId(),replay.receiptId());}assertCounts(tenant);
            awaitCandidateExpiry(issued.plusSeconds(300));
            var restarted=new IdentityBootstrapRuntime(tenant,binding,candidates,new ExternalSubjectProtection(t->subjectKey),directory,AuditAppender.databaseBacked("TASK92_RESTARTED_NODE"));
            idp.unavailable(()->{try(var c=database.apiConnection()){assertEquals(created.receiptId(),restarted.run(c,manifest,false).receiptId());}catch(Exception failure){throw new AssertionError(failure);}});assertCounts(tenant);
        }
    }
    record Scenario(UUID tenant,IdentityBootstrapService.Manifest manifest,IdentityBootstrapRuntime runtime,Instant candidateExpiresAt){}
    Scenario scenario(boolean expired)throws Exception {return scenario(expired?301:0);}
    Scenario scenario(int ageSeconds)throws Exception {
        UUID tenant=UUID.randomUUID();byte[] candidateKey=new byte[32],subjectKey=new byte[32];new java.security.SecureRandom().nextBytes(candidateKey);new java.security.SecureRandom().nextBytes(subjectKey);
        var directory=KeycloakDirectoryReader.isolatedLoopback(new KeycloakDirectoryReader.Trust(idp.issuer(),"task92-directory",idp.directorySecret));
        var candidates=new BootstrapCandidateProtection("offline-v1",Map.of("offline-v1",candidateKey));
        var binding=new BootstrapCandidateProtection.Binding("Approved synthetic test operator","T"+tenant.toString().replace("-",""),"TASK92",idp.issuer());
        Instant issuedAt=Instant.now().minusSeconds(ageSeconds);String selector=candidates.issue(binding,idp.username,directory,issuedAt);
        var manifest=new IdentityBootstrapService.Manifest("R1_IDENTITY_BOOTSTRAP_V1",UUID.randomUUID(),binding.tenantCode(),"Synthetic tenant","ROOT","Synthetic root",binding.provider(),binding.issuer(),selector,"Synthetic founder",Instant.now().minusSeconds(60),binding.operatorAssertion());
        return new Scenario(tenant,manifest,new IdentityBootstrapRuntime(tenant,binding,candidates,new ExternalSubjectProtection(t->subjectKey),directory,AuditAppender.databaseBacked("TASK92_BOOTSTRAP_IT")),issuedAt.plusSeconds(300));
    }
    @ParameterizedTest @ValueSource(strings={"SLOT_TIME","RECEIPT_TIME","BOTH_TIMES","SERVICE_ROLE","TRACE","CAUSATION"})
    void expired_offline_original_recovery_rejects_each_corrupted_closure_time_or_audit_source_without_repair(String defect)throws Exception {
        var s=scenario(295);try(var c=database.apiConnection()){assertEquals("CREATED",s.runtime().run(c,s.manifest(),false).mode());}
        String mutation=switch(defect){
            case "SLOT_TIME"->"update execution.command_execution_slot set occupied_at=occupied_at+interval '1 second' where tenant_id=?";
            case "RECEIPT_TIME"->"update execution.command_receipt set completed_at=completed_at+interval '1 second' where tenant_id=?";
            case "BOTH_TIMES"->"with changed as (update execution.command_execution_slot set occupied_at=occupied_at+interval '1 second' where tenant_id=? returning tenant_id) update execution.command_receipt set completed_at=completed_at+interval '1 second' where tenant_id in (select tenant_id from changed)";
            case "SERVICE_ROLE"->"update audit.audit_entry set service_role_code='WORKER' where tenant_id=?";
            case "TRACE"->"update audit.audit_entry set trace_id=gen_random_uuid() where tenant_id=?";
            case "CAUSATION"->"update audit.audit_entry set causation_id=gen_random_uuid() where tenant_id=?";
            default->throw new IllegalArgumentException("Unsupported isolated corruption case");};
        // Isolated corruption fixture only: no production grants or trigger definitions are changed.
        try(var c=database.adminConnection()){c.setAutoCommit(false);AuthorizationServiceIT.sql(c,"set local session_replication_role=replica");AuthorizationServiceIT.sql(c,mutation,s.tenant());c.commit();}
        awaitCandidateExpiry(s.candidateExpiresAt());
        var before=snapshot(s.tenant());idp.unavailable(()->{try(var c=database.apiConnection()){
            assertThrows(SQLException.class,()->s.runtime().run(c,s.manifest(),false));assertThrows(SQLException.class,()->s.runtime().verifyOriginal(c,s.manifest()));
        }catch(SQLException failure){throw new AssertionError("Isolated original-state verification connection unavailable",failure);}});assertEquals(before,snapshot(s.tenant()));
    }
    @ParameterizedTest @ValueSource(strings={"EXPIRED","PARTIAL","CHANGED_MANIFEST","NEW_COMMAND","CHANGED_PRINCIPAL","REVOKED_GRANT"})
    void invalid_or_incomplete_original_attempt_never_initializes_repairs_or_adds_permissions(String defect)throws Exception {
        var s=scenario(defect.equals("EXPIRED"));var m=s.manifest();boolean committed=Set.of("CHANGED_MANIFEST","NEW_COMMAND","CHANGED_PRINCIPAL","REVOKED_GRANT").contains(defect);
        if(committed)try(var c=database.apiConnection()){s.runtime().run(c,m,false);}
        if(defect.equals("PARTIAL"))try(var c=database.apiConnection()){io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.inTransaction(c,io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.Capability.COMMAND,x->{AuthorizationServiceIT.sql(x,"insert into identity.tenant (tenant_id,tenant_code,display_name,state,created_at) values (?,?,'partial','ACTIVE',clock_timestamp())",s.tenant(),s.manifest().tenantCode());return null;});}
        if(defect.equals("CHANGED_PRINCIPAL")||defect.equals("REVOKED_GRANT"))try(var c=database.apiConnection()){io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.inTransaction(c,io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.Capability.COMMAND,x->{AuthorizationServiceIT.sql(x,defect.equals("CHANGED_PRINCIPAL")?"update identity.principal set display_name='changed',revision=revision+1 where tenant_id=?":"update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=?",s.tenant());return null;});}
        if(defect.equals("CHANGED_MANIFEST")||defect.equals("NEW_COMMAND"))m=new IdentityBootstrapService.Manifest(m.profile(),defect.equals("NEW_COMMAND")?UUID.randomUUID():m.commandId(),m.tenantCode(),defect.equals("CHANGED_MANIFEST")?"Altered tenant":m.tenantDisplayName(),m.rootCode(),m.rootDisplayName(),m.identityProviderCode(),m.issuer(),m.providerUserSelector(),m.principalDisplayName(),m.effectiveFrom(),m.operatorAssertion());
        var submitted=m;var before=snapshot(s.tenant());try(var c=database.apiConnection()){assertThrows(Exception.class,()->s.runtime().run(c,submitted,false));}assertEquals(before,snapshot(s.tenant()));
        if(!committed)assertEquals(0,count("identity.authority_grant",s.tenant()));
    }
    @ParameterizedTest @ValueSource(strings={"FACT_INSERT","RECEIPT_INSERT","AUDIT_INSERT","COMMIT_ACK"})
    void partial_write_failures_roll_back_entire_initial_set_and_unknown_commit_reconciles_original_key(String failure)throws Exception {
        var s=scenario(false);
        try(var actual=database.apiConnection()) {
            var faulty=(Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                if(method.getName().equals("prepareStatement")&&args[0] instanceof String sql&&sql.toLowerCase(Locale.ROOT).startsWith("insert")) {
                    String table=switch(failure){case "FACT_INSERT"->"authority_grant";case "RECEIPT_INSERT"->"command_receipt";case "AUDIT_INSERT"->"audit_entry";default->"no_match";};if(sql.contains(table))throw new SQLException("Synthetic bootstrap write failure","08006");
                }
                try{var result=method.invoke(actual,args);if(failure.equals("COMMIT_ACK")&&method.getName().equals("commit"))throw new SQLException("Synthetic unknown commit","08006");return result;}catch(java.lang.reflect.InvocationTargetException thrown){throw thrown.getCause();}
            });
            assertThrows(Exception.class,()->s.runtime().run(faulty,s.manifest(),false));
        }
        if(failure.equals("COMMIT_ACK")){assertCounts(s.tenant());try(var c=database.apiConnection()){assertEquals("VERIFIED_ORIGINAL",s.runtime().run(c,s.manifest(),false).mode());}assertCounts(s.tenant());}
        else for(long count:snapshot(s.tenant()))assertEquals(0,count);
    }
    List<Long> snapshot(UUID tenant)throws Exception {var counts=new ArrayList<Long>();for(String table:List.of("identity.tenant","identity.organization_unit","identity.principal","identity.appointment","identity.authority_grant","execution.command_execution_slot","execution.command_receipt","audit.audit_entry"))counts.add(count(table,tenant));return counts;}
    void awaitCandidateExpiry(Instant expiresAt)throws Exception {long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);while(Instant.now().isBefore(expiresAt)&&System.nanoTime()<deadline)Thread.sleep(25);assertFalse(Instant.now().isBefore(expiresAt),"Candidate did not reach its exact expiry");}
    long count(String table,UUID tenant)throws Exception{try(var c=database.migratorConnection();var p=c.prepareStatement("select count(*) from "+table+" where tenant_id=?")){p.setObject(1,tenant);try(var r=p.executeQuery()){r.next();return r.getLong(1);}}}
    void assertCounts(UUID tenant)throws Exception {
        for(String table:List.of("identity.tenant","identity.organization_unit","identity.principal","identity.appointment","execution.command_execution_slot","execution.command_receipt","audit.audit_entry"))assertEquals(1,count(table,tenant),table);
        assertEquals(4,count("identity.authority_grant",tenant));
        for(String table:List.of("responsibility.task_occurrence","responsibility.action_draft","execution.domain_event","execution.domain_event_outbox"))assertEquals(0,count(table,tenant),table);
    }
}
