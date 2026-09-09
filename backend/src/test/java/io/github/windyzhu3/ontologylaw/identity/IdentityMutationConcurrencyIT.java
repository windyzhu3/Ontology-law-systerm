package io.github.windyzhu3.ontologylaw.identity;

import io.github.windyzhu3.ontologylaw.execution.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import static org.junit.jupiter.api.Assertions.*;

class IdentityMutationConcurrencyIT extends IdentityAdminFixture {
    @Test void another_tenant_bootstrap_and_writer_progress_while_first_tenant_is_exclusively_fenced()throws Exception {
        UUID firstTenant=seed.tenant();try(var blocker=database.apiConnection()){blocker.setAutoCommit(false);setLocalRole(blocker,Capability.QUERY);R1BusinessFence.databaseBacked().exclusive(blocker,firstTenant);var pool=Executors.newSingleThreadExecutor();
            try{var second=pool.submit(()->{administration();return organization();});assertNotNull(second.get(15,TimeUnit.SECONDS));assertNotEquals(firstTenant,seed.tenant());}finally{blocker.rollback();pool.shutdownNow();}
        }
    }
    @Test void same_command_uuid_concurrently_creates_only_one_fact_and_original_metadata()throws Exception {
        UUID key=UUID.randomUUID();var body=Map.<String,Object>of("parentOrganizationId",seed.org().toString(),"code","ONE_COMMAND","displayName","One");var before=counts();var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){var first=pool.submit(()->{start.await();return execute(key,"CREATE_ORGANIZATION_UNIT",null,null,body);});var second=pool.submit(()->{start.await();return execute(key,"CREATE_ORGANIZATION_UNIT",null,null,body);});start.countDown();var a=first.get(15,TimeUnit.SECONDS);var b=second.get(15,TimeUnit.SECONDS);assertEquals(success(a),success(b));assertNotEquals(a.replay(),b.replay());}delta(before,1);
    }
    @Test void close_and_new_appointment_serialize_the_dependency_decision()throws Exception {
        UUID org=organization(),principal=principal();String closing=tag("CLOSE_ORGANIZATION_UNIT",org);var start=new CountDownLatch(1);var app=body("principalId",principal.toString(),"organizationId",org.toString(),"roleCode","CONTACT_OPERATOR","effectiveFrom","2026-01-01T00:00:00Z","effectiveUntil",null);
        try(var pool=Executors.newFixedThreadPool(2)){var close=pool.submit(()->{start.await();return execute("CLOSE_ORGANIZATION_UNIT",org,closing,Map.of("reasonCode","ADMINISTRATIVE_ACTION"));});var create=pool.submit(()->{start.await();try{return execute("CREATE_APPOINTMENT",null,null,app);}catch(IdentityCommands.Failure refused){assertEquals("NOT_AUTHORIZED",refused.code());return null;}});start.countDown();var closed=close.get(15,TimeUnit.SECONDS);var created=create.get(15,TimeUnit.SECONDS);
            if(created==null){success(closed);assertEquals("CLOSED",scalar("select state from identity.organization_unit where tenant_id=? and organization_unit_id=?",seed.tenant(),org));assertEquals("0",scalar("select count(*) from identity.appointment where tenant_id=? and organization_unit_id=?",seed.tenant(),org));}
            else {success(created);rejected("IDENTITY_ORGANIZATION_DEPENDENCY",closed);assertEquals("ACTIVE",scalar("select state from identity.organization_unit where tenant_id=? and organization_unit_id=?",seed.tenant(),org));}
        }
    }
    @Test void fresh_database_clock_rejects_management_grant_that_expires_while_writer_waits_on_fence()throws Exception {
        UUID org=organization();mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=?",seed.tenant());var until=java.time.Instant.now().plusSeconds(2);
        mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,valid_until,state,created_at) values (?,?,?,?,?,'IDENTITY_ORGANIZATION_MANAGE',clock_timestamp()-interval '1 hour',?::timestamptz,'ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),seed.appointment(),seed.appointment(),seed.org(),until.toString());String tag=tag("RENAME_ORGANIZATION_UNIT",org);var before=counts();
        try(var blocker=database.apiConnection();var pool=Executors.newSingleThreadExecutor()){blocker.setAutoCommit(false);setLocalRole(blocker,Capability.QUERY);R1BusinessFence.databaseBacked().shared(blocker,seed.tenant());var result=pool.submit(()->execute("RENAME_ORGANIZATION_UNIT",org,tag,Map.of("displayName","Expired")));assertThrows(TimeoutException.class,()->result.get(100,TimeUnit.MILLISECONDS));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean expired=false;while(System.nanoTime()<deadline){expired="YES".equals(scalar("select case when clock_timestamp()>=?::timestamptz then 'YES' else 'NO' end",until.toString()));if(expired)break;Thread.sleep(25);}blocker.commit();assertTrue(expired);var failure=assertThrows(ExecutionException.class,()->result.get(10,TimeUnit.SECONDS));assertInstanceOf(IdentityCommands.Failure.class,failure.getCause());}assertEquals(before,counts());
    }
    @Test void two_connections_with_same_etag_cannot_overwrite_each_other()throws Exception {UUID org=organization();String tag=tag("RENAME_ORGANIZATION_UNIT",org);var start=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->{start.await();return execute("RENAME_ORGANIZATION_UNIT",org,tag,Map.of("displayName","First"));});var b=pool.submit(()->{start.await();return execute("RENAME_ORGANIZATION_UNIT",org,tag,Map.of("displayName","Second"));});start.countDown();var results=List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));assertEquals(1,results.stream().filter(r->r.receipt().outcome().status()==CommandOutcome.Status.SUCCEEDED).count());assertEquals(1,results.stream().filter(r->"STALE_IDENTITY".equals(r.receipt().outcome().rejectionCode())).count());assertEquals("1",scalar("select revision from identity.organization_unit where tenant_id=? and organization_unit_id=?",seed.tenant(),org));}}
    @Test void business_shared_fence_blocks_identity_writer_until_commit_without_reverse_locks()throws Exception {UUID org=organization();String tag=tag("RENAME_ORGANIZATION_UNIT",org);try(var blocker=database.apiConnection();var pool=Executors.newSingleThreadExecutor()){blocker.setAutoCommit(false);setLocalRole(blocker,Capability.QUERY);R1BusinessFence.databaseBacked().shared(blocker,seed.tenant());var result=pool.submit(()->execute("RENAME_ORGANIZATION_UNIT",org,tag,Map.of("displayName","After fence")));assertThrows(TimeoutException.class,()->result.get(200,TimeUnit.MILLISECONDS));blocker.commit();success(result.get(10,TimeUnit.SECONDS));}}
    @ParameterizedTest @ValueSource(strings={"principal","command_execution_slot","command_receipt","audit_entry","COMMIT_ACK"})
    void injected_write_failure_rolls_back_all_facts_or_reconciles_unknown_commit(String fault)throws Exception {
        UUID key=UUID.randomUUID();String selector=candidates.issue(actor,"FIXTURE",directory.issuer(),"fixture",UUID.randomUUID().toString(),java.time.Instant.now());var body=Map.<String,Object>of("providerUserSelector",selector,"displayName","Fault target");var before=counts();
        try(var actual=database.apiConnection()) {var faulty=(Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,args)->{if(m.getName().equals("prepareStatement")&&args[0] instanceof String sql&&sql.toLowerCase(Locale.ROOT).startsWith("insert")&&sql.contains(fault))throw new SQLException("Synthetic Identity write fault","08006");try{var result=m.invoke(actual,args);if(fault.equals("COMMIT_ACK")&&m.getName().equals("commit"))throw new SQLException("Synthetic unknown commit","08006");return result;}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}});assertThrows(Exception.class,()->runtime.execute(faulty,envelope(key,"CREATE_IDENTITY_PRINCIPAL",null,null,body)));}
        if(fault.equals("COMMIT_ACK")){delta(before,0);var committed=counts();assertTrue(execute(key,"CREATE_IDENTITY_PRINCIPAL",null,null,body).replay());assertEquals(committed,counts());}else assertEquals(before,counts());
    }
}
