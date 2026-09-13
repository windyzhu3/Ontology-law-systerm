package io.github.windyzhu3.ontologylaw.identity;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;

class ActorScopeProtectionTest {
    @Test void persisted_purpose_key_survives_provider_reconstruction_and_binds_every_actor_dimension() {
        // Omitting any tuple field would conflate two authorization/receipt-recovery scopes.
        byte[] material=new byte[32];new java.security.SecureRandom().nextBytes(material);
        UUID tenant=UUID.randomUUID(),principal=UUID.randomUUID(),own=UUID.randomUUID(),represented=UUID.randomUUID(),behalf=UUID.randomUUID();
        var actor=new Actor(tenant,principal,own,represented,behalf,PrincipalKind.HUMAN);
        String key=new ActorScopeProtection(t->material.clone()).key(actor);
        assertTrue(key.matches("ask1\\.[A-Za-z0-9_-]{43}"));
        assertTrue(key.equals(new ActorScopeProtection(t->material.clone()).key(actor)),"Restart with persisted purpose key preserves the scope (values restricted)");
        for(var different:List.of(new Actor(UUID.randomUUID(),principal,own,represented,behalf,PrincipalKind.HUMAN),new Actor(tenant,UUID.randomUUID(),own,represented,behalf,PrincipalKind.HUMAN),new Actor(tenant,principal,UUID.randomUUID(),represented,behalf,PrincipalKind.HUMAN),new Actor(tenant,principal,own,UUID.randomUUID(),behalf,PrincipalKind.HUMAN),new Actor(tenant,principal,own,represented,UUID.randomUUID(),PrincipalKind.HUMAN),new Actor(tenant,principal,own,null,null,PrincipalKind.HUMAN)))
            assertFalse(key.equals(new ActorScopeProtection(t->material).key(different)),"Distinct full Actor tuples never share a recovery scope");
    }
}
