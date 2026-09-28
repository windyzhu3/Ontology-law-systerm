package io.github.windyzhu3.ontologylaw.api;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;import java.util.*;
import org.springframework.boot.context.properties.bind.Binder;import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
class TransferDeploymentSettingsTest {
 @Test void trusted_transfer_destination_and_synthetic_receipt_account_bind_without_exposing_key_values(){
  UUID tenant=UUID.randomUUID(),destination=UUID.randomUUID();String prefix="ols.api.tenant-keys["+tenant+"].";
  var properties=new LinkedHashMap<String,Object>();properties.put(prefix+"encryption","synthetic-encryption");properties.put(prefix+"transfer-destination-organization-id",destination.toString());properties.put(prefix+"payment.transaction-hmac","synthetic-hmac");properties.put(prefix+"payment.account-code","SYNTHETIC_REVIEW");properties.put(prefix+"payment.account-label","Synthetic review account");
  var settings=new Binder(new MapConfigurationPropertySource(properties)).bind("ols.api",R1ApiDeployment.Settings.class).get();var keys=settings.tenantKeys().get(tenant);assertEquals(destination,keys.transferDestinationOrganizationId());assertEquals("SYNTHETIC_REVIEW",keys.payment().accountCode());assertEquals("Synthetic review account",keys.payment().accountLabel());assertFalse(keys.toString().contains("synthetic-hmac"));
 }
 @Test void legacy_tenant_configuration_keeps_new_deployment_options_absent(){
  UUID tenant=UUID.randomUUID();var settings=new Binder(new MapConfigurationPropertySource(Map.of("ols.api.tenant-keys["+tenant+"].encryption","synthetic"))).bind("ols.api",R1ApiDeployment.Settings.class).get();assertNull(settings.tenantKeys().get(tenant).transferDestinationOrganizationId());assertNull(settings.tenantKeys().get(tenant).payment());
 }
}
