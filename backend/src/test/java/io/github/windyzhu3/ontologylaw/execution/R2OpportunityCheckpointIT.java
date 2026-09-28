package io.github.windyzhu3.ontologylaw.execution;

import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.testing.PostgresIntegrationTest;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class R2OpportunityCheckpointIT extends PostgresIntegrationTest {
    private R2OpportunityCheckpointPort.Key key(){return new R2OpportunityCheckpointPort.Key(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"INITIAL");}
    @Test void physical_session_lock_excludes_peer_and_committed_body_survives_reopen()throws Exception {
        var port=R2OpportunityCheckpointPort.databaseBacked(database::workerConnection);var key=key();
        try(var first=port.tryOpen(key)){
            assertNotNull(first);assertNull(first.load());first.save(new byte[]{1,2,3});assertNull(port.tryOpen(key));
            try(var other=port.tryOpen(new R2OpportunityCheckpointPort.Key(key.tenantId(),key.principalId(),key.appointmentId(),"DUE"))){assertNotNull(other);assertNull(other.load());}
        }
        try(var recovered=port.tryOpen(key)){assertArrayEquals(new byte[]{1,2,3},recovered.load());recovered.save(new byte[]{4,5});}
        try(var recovered=port.tryOpen(key)){assertArrayEquals(new byte[]{4,5},recovered.load());}
    }
    @Test void worker_cannot_change_identity_delete_or_write_business_and_api_cannot_read_checkpoint()throws Exception {
        var port=R2OpportunityCheckpointPort.databaseBacked(database::workerConnection);var key=key();
        try(var session=port.tryOpen(key)){session.load();session.save(new byte[]{1});}
        try(var c=database.workerConnection();var q=c.createStatement()){
            q.execute("set role law_app_worker");
            for(String sql:List.of("update platform_meta.r2_opportunity_checkpoint set tenant_id=gen_random_uuid()","delete from platform_meta.r2_opportunity_checkpoint","update responsibility.task_occurrence set revision=revision+1")){
                assertEquals("42501",assertThrows(SQLException.class,()->q.execute(sql)).getSQLState());
            }
        }
        try(var c=database.apiConnection();var q=c.createStatement()){
            for(String role:List.of("law_app_command","law_app_query","law_audit_append")){
                q.execute("set role "+role);assertEquals("42501",assertThrows(SQLException.class,()->q.execute("select * from platform_meta.r2_opportunity_checkpoint")).getSQLState());
            }
        }
    }
    @Test void dead_session_releases_lock_and_cannot_acknowledge_after_disconnect()throws Exception {
        var connection=database.workerConnection();var port=R2OpportunityCheckpointPort.databaseBacked(()->connection);var key=key();
        try(var session=port.tryOpen(key)){
            session.load();session.save(new byte[]{8});connection.close();assertThrows(SQLException.class,()->session.save(new byte[]{9}));
            try(var next=R2OpportunityCheckpointPort.databaseBacked(database::workerConnection).tryOpen(key)){assertNotNull(next);assertArrayEquals(new byte[]{8},next.load());}
        }
    }
    @Test void stale_revision_cannot_overwrite_newer_checkpoint()throws Exception {
        var port=R2OpportunityCheckpointPort.databaseBacked(database::workerConnection);var key=key();
        try(var session=port.tryOpen(key)){
            session.load();session.save(new byte[]{1});
            try(var c=database.workerConnection();var q=c.createStatement()){
                q.execute("set role law_app_worker");
                try(var update=c.prepareStatement("update platform_meta.r2_opportunity_checkpoint set checkpoint_body=?,revision=revision+1 where tenant_id=? and principal_id=? and appointment_id=? and scan_kind=?")){
                    update.setBytes(1,new byte[]{2});update.setObject(2,key.tenantId());update.setObject(3,key.principalId());update.setObject(4,key.appointmentId());update.setString(5,key.kind());assertEquals(1,update.executeUpdate());
                }
            }
            assertEquals("40001",assertThrows(SQLException.class,()->session.save(new byte[]{3})).getSQLState());
        }
        try(var session=port.tryOpen(key)){assertArrayEquals(new byte[]{2},session.load());}
    }
    @Test void owner_exception_checkpoint_is_durable_and_has_an_independent_physical_lock()throws Exception {
        var port=R2OpportunityCheckpointPort.databaseBacked(database::workerConnection);var initial=key();
        var owner=new R2OpportunityCheckpointPort.Key(initial.tenantId(),initial.principalId(),initial.appointmentId(),"OWNER_EXCEPTION");
        try(var first=port.tryOpen(initial);var observation=port.tryOpen(owner)){
            assertNotNull(first);assertNotNull(observation);assertNull(first.load());assertNull(observation.load());
            observation.save(new byte[]{4,2});assertNull(port.tryOpen(owner));
        }
        try(var recovered=port.tryOpen(owner)){assertArrayEquals(new byte[]{4,2},recovered.load());}
    }
}
