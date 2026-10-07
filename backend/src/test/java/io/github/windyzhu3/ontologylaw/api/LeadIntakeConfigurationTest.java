package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.lead.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.*;
import static org.junit.jupiter.api.Assertions.*;

class LeadIntakeConfigurationTest {
    @Test void deployment_binds_human_source_by_exact_tenant_and_principal() {
        var tenant=UUID.randomUUID();var principal=UUID.randomUUID();var values=new HashMap<String,Object>();
        values.put("ols.api.node","TEST");
        values.put("ols.api.human-intake-bindings[0].tenant-id",tenant.toString());
        values.put("ols.api.human-intake-bindings[0].principal-id",principal.toString());
        values.put("ols.api.human-intake-bindings[0].source-account-code","helong");
        var environment=new StandardEnvironment();environment.getPropertySources().addFirst(new MapPropertySource("fixture",values));
        assertEquals(List.of(new R1HumanSourceBinding.Entry(tenant,principal,"helong")),Binder.get(environment).bind("ols.api",R1ApiDeployment.Settings.class).get().humanIntakeBindings());
    }
    @Test void deployment_binds_explicit_source_labels_without_inventing_defaults() {
        var values = new HashMap<String,Object>();
        values.put("ols.api.node","TEST");
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("fixture",values));
        assertNull(Binder.get(environment).bind("ols.api",R1ApiDeployment.Settings.class).get().intakeSources());
        Map.of("source-account-code","FIXTURE","display-name","客户转介绍","source-channel-code","MANUAL","service-category-code","CONSULTATION","jurisdiction-code","CN","urgency-code","NORMAL")
            .forEach((key,value)->values.put("ols.api.intake-sources[0]."+key,value));
        var configured = Binder.get(environment).bind("ols.api",R1ApiDeployment.Settings.class).get().intakeSources();
        assertEquals(List.of(new LeadIntakeSources.Source("FIXTURE","客户转介绍","MANUAL","CONSULTATION","CN","NORMAL")),configured);
        assertThrows(IllegalArgumentException.class,()->new LeadIntakeSources(new R1SourcePolicyRegistry(Map.of()),configured));
        assertDoesNotThrow(()->new LeadIntakeSources.Source("sales_intake","手工录入","MANUAL","CONSULTATION","CN","NORMAL"));
        assertThrows(IllegalArgumentException.class,()->new LeadIntakeSources.Source("sales_intake","手工录入","manual","CONSULTATION","CN","NORMAL"));
    }
}
