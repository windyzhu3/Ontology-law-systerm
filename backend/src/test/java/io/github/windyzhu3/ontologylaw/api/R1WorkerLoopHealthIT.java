package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.execution.R1ProjectionOutboxPort;
import io.github.windyzhu3.ontologylaw.testing.TlsFixture;
import io.github.windyzhu3.ontologylaw.worker.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class R1WorkerLoopHealthIT extends R1HttpFixture {
    @TempDir Path directory;
    @ParameterizedTest @ValueSource(strings={"R1_PROJECTION_CONSUME","CONTACT_TASK_RECOVER","ROUTING_REVIEW_TASK_RECOVER"})
    void each_real_scheduled_operation_has_independent_attempted_failure_and_recovery_health(String revoked)throws Exception {
        setupContact();var actor=service("R1_PROJECTION_CONSUME");
        for(String authority:List.of("CONTACT_TASK_RECOVER","ROUTING_REVIEW_TASK_RECOVER"))grant(actor.appointmentId(),authority);
        try(var http=new HttpHarness(actor,new TlsFixture(directory));var client=http.workerClient()) {
            var registry=new R1WorkerTenantBindings("MVP-2026-09-08.1",List.of(http.workerBinding));var clock=new R1WorkerTransportIT.MutableClock();
            try(var projection=new R1ProjectionDispatcher(registry,client,R1ProjectionOutboxPort.databaseBacked(database::workerConnection),"HEALTH_IT",clock);var due=new DueTaskScheduler(registry,client,clock)) {
                var binding=http.workerBinding;assertFalse(projection.healthy(binding));for(var type:InternalApiClient.RecoveryType.values())assertFalse(due.healthy(binding,type));
                projection.poll(binding);for(var type:InternalApiClient.RecoveryType.values())due.poll(binding,type);
                assertTrue(projection.healthy(binding));for(var type:InternalApiClient.RecoveryType.values())assertTrue(due.healthy(binding,type));
                var before=counts();mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and grantee_appointment_id=? and authority_code=? and state='ACTIVE'",seed.tenant(),actor.appointmentId(),revoked);
                projection.poll(binding);for(var type:InternalApiClient.RecoveryType.values())due.poll(binding,type);
                assertEquals(!revoked.equals("R1_PROJECTION_CONSUME"),projection.healthy(binding));assertEquals(!revoked.equals("CONTACT_TASK_RECOVER"),due.healthy(binding,InternalApiClient.RecoveryType.CONTACT_TASK));assertEquals(!revoked.equals("ROUTING_REVIEW_TASK_RECOVER"),due.healthy(binding,InternalApiClient.RecoveryType.ROUTING_REVIEW_TASK));assertEquals(before,counts());
                grant(actor.appointmentId(),revoked);clock.advance(5);projection.poll(binding);for(var type:InternalApiClient.RecoveryType.values())due.poll(binding,type);
                assertTrue(projection.healthy(binding));for(var type:InternalApiClient.RecoveryType.values())assertTrue(due.healthy(binding,type));assertEquals(before,counts());
            }
        }
    }
    private void grant(UUID appointment,String authority)throws Exception {mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,?,clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),appointment,seed.appointment(),seed.org(),authority);}
}
