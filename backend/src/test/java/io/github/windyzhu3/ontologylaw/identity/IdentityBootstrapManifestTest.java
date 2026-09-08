package io.github.windyzhu3.ontologylaw.identity;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdentityBootstrapManifestTest {
    @Test void offline_safe_text_is_trimmed_before_it_becomes_an_initial_fact_or_operator_binding() {
        // The active SafeText200 contract also governs the closed offline manifest.
        var manifest=new IdentityBootstrapService.Manifest("R1_IDENTITY_BOOTSTRAP_V1",UUID.randomUUID(),"TENANT","  Tenant  ","ROOT","  Root  ","PROVIDER","https://identity.example.invalid/realms/fixed","opaque-candidate","  Founder  ",Instant.EPOCH,"  Operator  ");
        assertAll(()->assertEquals("Tenant",manifest.tenantDisplayName()),()->assertEquals("Root",manifest.rootDisplayName()),()->assertEquals("Founder",manifest.principalDisplayName()),()->assertEquals("Operator",manifest.operatorAssertion()),
                ()->assertEquals("Operator",new BootstrapCandidateProtection.Binding("  Operator  ","TENANT","PROVIDER","https://identity.example.invalid/realms/fixed").operatorAssertion()));
    }
    @Test void normalizing_does_not_hide_empty_overlength_or_control_characters_in_operator_assertions() {
        for(String invalid:new String[]{"   ","a".repeat(201),"\nOperator","Operator\tAssertion"}) {
            assertThrows(IllegalArgumentException.class,()->new IdentityBootstrapService.Manifest("R1_IDENTITY_BOOTSTRAP_V1",UUID.randomUUID(),"TENANT","Tenant","ROOT","Root","PROVIDER","https://identity.example.invalid/realms/fixed","opaque-candidate","Founder",Instant.EPOCH,invalid));
            assertThrows(IllegalArgumentException.class,()->new BootstrapCandidateProtection.Binding(invalid,"TENANT","PROVIDER","https://identity.example.invalid/realms/fixed"));
        }
    }
}
