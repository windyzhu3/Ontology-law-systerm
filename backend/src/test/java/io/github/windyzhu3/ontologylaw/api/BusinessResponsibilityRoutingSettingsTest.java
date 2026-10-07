package io.github.windyzhu3.ontologylaw.api;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class BusinessResponsibilityRoutingSettingsTest {
 @Test void deployment_binding_retains_exact_ids_and_legacy_absence(){
  UUID tenant=UUID.randomUUID(),org=UUID.randomUUID(),appointment=UUID.randomUUID();
  var values=Map.of("ols.api.responsibility-routes[0].tenant-id",tenant.toString(),"ols.api.responsibility-routes[0].source-organization-id",org.toString(),"ols.api.responsibility-routes[0].stage-code","CHECK_RECEIPT","ols.api.responsibility-routes[0].appointment-id",appointment.toString());
  var settings=new Binder(new MapConfigurationPropertySource(values)).bind("ols.api",R1ApiDeployment.Settings.class).get();
  assertEquals(List.of(new BusinessResponsibilityRouting.Entry(tenant,org,"CHECK_RECEIPT",appointment)),settings.responsibilityRoutes());
  var legacy=new Binder(new MapConfigurationPropertySource(Map.of("ols.api.node","fixture"))).bind("ols.api",R1ApiDeployment.Settings.class).get();
  assertNull(legacy.responsibilityRoutes());
 }
}
