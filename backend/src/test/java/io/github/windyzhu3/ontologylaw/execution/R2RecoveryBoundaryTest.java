package io.github.windyzhu3.ontologylaw.execution;
import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class R2RecoveryBoundaryTest {
    @Test void only_service_can_use_named_recovery_and_only_r2_queue_receives_event(){
        var type=CommandEnvelope.Type.REOPEN_DUE_OPPORTUNITY_TASKS;
        var human=new Actor(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,null);
        assertThrows(IllegalArgumentException.class,()->new CommandEnvelope(type,UUID.randomUUID(),UUID.randomUUID(),human,Map.of()));
        var service=new Actor(human.tenantId(),human.principalId(),human.appointmentId(),null,null,PrincipalKind.SERVICE);
        assertEquals(CommandEnvelope.Envelope.SERVICE_ACTOR,new CommandEnvelope(type,UUID.randomUUID(),UUID.randomUUID(),service,Map.of()).envelope());
        assertTrue(type.recovery());assertEquals(Set.of(CommandHandler.QueueOwner.R2_PROJECTION),CommandHandler.Event.OpportunityTaskReopenedV1.queueOwners());
        assertTrue(R1EventPolicy.branches().stream().noneMatch(b->b.command()==type));
        assertEquals(1,R1EventPolicy.r2Branches().stream().filter(b->b.command()==type).count());
        assertThrows(IllegalArgumentException.class,()->CommandScope.reopen(service.tenantId(),type,UUID.randomUUID(),UUID.randomUUID(),"A".repeat(43)));
    }
}
