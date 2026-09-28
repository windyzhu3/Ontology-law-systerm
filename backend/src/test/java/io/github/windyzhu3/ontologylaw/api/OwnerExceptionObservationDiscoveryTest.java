package io.github.windyzhu3.ontologylaw.api;

import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.opportunity.OpportunityOwnerExceptionCandidates;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OwnerExceptionObservationDiscoveryTest {
    private final Actor actor=new Actor(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,null,PrincipalKind.SERVICE);
    private final Instant now=Instant.parse("2026-09-15T00:00:00Z");
    private final OwnerExceptionObservationDiscovery discovery=new OwnerExceptionObservationDiscovery(new byte[32]);
    @Test void cursor_is_exact_actor_bound_authenticated_and_expires(){
        var cursor=new OwnerExceptionObservationDiscovery.Cursor(now,new OpportunityOwnerExceptionCandidates.Position(now.minusSeconds(1),UUID.randomUUID()));
        var encoded=discovery.encode(actor,cursor);
        assertEquals(cursor,discovery.decode(actor,encoded,now.plusSeconds(299)));
        assertThrows(RuntimeException.class,()->discovery.decode(actor,encoded,now.plusSeconds(300)));
        assertThrows(RuntimeException.class,()->discovery.decode(actor,encoded,now.minusSeconds(1)));
        assertThrows(RuntimeException.class,()->discovery.decode(new Actor(actor.tenantId(),actor.principalId(),UUID.randomUUID(),null,null,PrincipalKind.SERVICE),encoded,now));
        assertThrows(RuntimeException.class,()->discovery.decode(actor,encoded+"=",now));
        assertThrows(RuntimeException.class,()->discovery.decode(actor,"A"+encoded.substring(1),now));
    }
    @Test void replay_key_preserves_exact_observation_but_separates_versions_and_actors(){
        var opportunity=new Subject("opportunity.opportunity",UUID.randomUUID(),0L,null);
        var key=OwnerExceptionObservationDiscovery.commandId(actor,now,opportunity);
        assertEquals(5,key.version());assertEquals(key,OwnerExceptionObservationDiscovery.commandId(actor,now,opportunity));
        assertNotEquals(key,OwnerExceptionObservationDiscovery.commandId(actor,now.plusNanos(1),opportunity));
        assertNotEquals(key,OwnerExceptionObservationDiscovery.commandId(actor,now,new Subject(opportunity.type(),opportunity.id(),1L,null)));
        assertNotEquals(key,OwnerExceptionObservationDiscovery.commandId(new Actor(UUID.randomUUID(),actor.principalId(),actor.appointmentId(),null,null,PrincipalKind.SERVICE),now,opportunity));
    }
    @Test void invalid_limit_is_rejected_before_database_access(){
        assertEquals(400,discovery.list(null,actor,0,null).status());
        assertEquals(400,discovery.list(null,actor,101,null).status());
    }
}
