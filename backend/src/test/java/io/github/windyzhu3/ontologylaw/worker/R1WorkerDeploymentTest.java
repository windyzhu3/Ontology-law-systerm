package io.github.windyzhu3.ontologylaw.worker;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class R1WorkerDeploymentTest {
    @Test void recovery_scans_leave_capacity_for_nested_deployment_checks_and_r1_workers(){
        assertEquals(2,R1WorkerDeployment.databasePoolSize(1,false,false));
        assertEquals(4,R1WorkerDeployment.databasePoolSize(1,false,true));
        assertEquals(8,R1WorkerDeployment.databasePoolSize(1,true,false));
        assertEquals(10,R1WorkerDeployment.databasePoolSize(1,true,true));
        assertEquals(32,R1WorkerDeployment.databasePoolSize(100,true,true));
    }
    @Test void owner_exception_observation_defaults_to_disabled() {
        var source=new MapConfigurationPropertySource(Map.of("ols.worker.node","worker-test"));
        var settings=new Binder(source).bind("ols.worker",R1WorkerDeployment.Settings.class).orElseThrow(IllegalStateException::new);
        assertFalse(settings.ownerExceptionObservationEnabled());
    }
    @Test void owner_exception_observation_requires_explicit_true() {
        var source=new MapConfigurationPropertySource(Map.of("ols.worker.node","worker-test","ols.worker.owner-exception-observation-enabled","true","ols.worker.database.schema-version","52-plus-2-r2-v4"));
        var settings=new Binder(source).bind("ols.worker",R1WorkerDeployment.Settings.class).orElseThrow(IllegalStateException::new);
        assertTrue(settings.ownerExceptionObservationEnabled());
    }
    @Test void observation_cannot_be_enabled_against_a_previous_schema(){
        var source=new MapConfigurationPropertySource(Map.of("ols.worker.node","worker-test","ols.worker.owner-exception-observation-enabled","true","ols.worker.database.schema-version","52-plus-2-r2-v3"));
        assertThrows(RuntimeException.class,()->new Binder(source).bind("ols.worker",R1WorkerDeployment.Settings.class));
    }
    @Test void normal_opportunity_scheduling_defaults_to_disabled() {
        var source=new MapConfigurationPropertySource(Map.of("ols.worker.node","worker-test"));
        var settings=new Binder(source).bind("ols.worker",R1WorkerDeployment.Settings.class).orElseThrow(IllegalStateException::new);
        assertFalse(settings.opportunityTaskSchedulingEnabled());
    }
    @Test void normal_opportunity_scheduling_accepts_only_registered_checkpoint_schemas() {
        for(String schema:java.util.List.of("52-plus-2-r2-v3","52-plus-2-r2-v4","52-plus-2-r2-v5","52-plus-2-r2-v6","52-plus-2-r2-v7","52-plus-2-r2-v8","52-plus-2-r2-v9","52-plus-2-r2-v10","52-plus-2-r2-v11","52-plus-2-r2-v12","52-plus-2-r2-v13","52-plus-2-r2-v14","52-plus-2-r2-v15","52-plus-2-r2-v16","52-plus-2-r2-v17","52-plus-2-r2-v18","52-plus-2-r2-v19","52-plus-2-r2-v20","52-plus-2-r2-v21")) {
            var source=new MapConfigurationPropertySource(Map.of("ols.worker.node","worker-test","ols.worker.opportunity-task-scheduling-enabled","true","ols.worker.database.schema-version",schema));
            var settings=new Binder(source).bind("ols.worker",R1WorkerDeployment.Settings.class).orElseThrow(IllegalStateException::new);
            assertTrue(settings.opportunityTaskSchedulingEnabled());
            assertFalse(settings.ownerExceptionObservationEnabled());
        }
    }
    @Test void normal_opportunity_scheduling_rejects_missing_old_and_unknown_schemas() {
        for(String schema:java.util.List.of("52-plus-2","52-plus-2-r2-v1","52-plus-2-r2-v2","52-plus-2-r2-v22","52-plus-2-r2-v5-extra","52-plus-2-r2-v3-extra")) {
            var source=new MapConfigurationPropertySource(Map.of("ols.worker.node","worker-test","ols.worker.opportunity-task-scheduling-enabled","true","ols.worker.database.schema-version",schema));
            assertThrows(RuntimeException.class,()->new Binder(source).bind("ols.worker",R1WorkerDeployment.Settings.class),schema);
        }
        var source=new MapConfigurationPropertySource(Map.of("ols.worker.node","worker-test","ols.worker.opportunity-task-scheduling-enabled","true"));
        assertThrows(RuntimeException.class,()->new Binder(source).bind("ols.worker",R1WorkerDeployment.Settings.class));
    }
    @Test void normal_and_exception_toggles_remain_independent_on_manual_signature_schema() {
        for(boolean normal:new boolean[]{false,true})for(boolean exception:new boolean[]{false,true}) {
            var source=new MapConfigurationPropertySource(Map.of("ols.worker.node","worker-test","ols.worker.opportunity-task-scheduling-enabled",normal,"ols.worker.owner-exception-observation-enabled",exception,"ols.worker.database.schema-version","52-plus-2-r2-v13"));
            var settings=new Binder(source).bind("ols.worker",R1WorkerDeployment.Settings.class).orElseThrow(IllegalStateException::new);
            assertEquals(normal,settings.opportunityTaskSchedulingEnabled());
            assertEquals(exception,settings.ownerExceptionObservationEnabled());
        }
    }
}

