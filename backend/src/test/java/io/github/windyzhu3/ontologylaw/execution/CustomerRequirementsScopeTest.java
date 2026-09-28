package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CustomerRequirementsScopeTest {
    @Test void exactDraftAndOwnerRemainInScopeWithoutTaskOrWaitMutation() {
        var actor=new Actor(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,null,PrincipalKind.HUMAN);
        var opportunity=new Subject("opportunity.opportunity",UUID.randomUUID(),4L,null);
        var basis=new Subject("opportunity.responsibility_handoff",UUID.randomUUID(),2L,null);
        var draft=new Subject("opportunity.customer_requirement_draft",UUID.randomUUID(),0L,null);
        var command=new CommandEnvelope(CommandEnvelope.Type.CONFIRM_OPPORTUNITY_CUSTOMER_REQUIREMENTS,UUID.randomUUID(),UUID.randomUUID(),actor,Map.of());
        var scope=CommandScope.customerRequirements(command,opportunity,basis,draft,null);
        assertNull(scope.taskId());assertTrue(scope.fields().containsKey("confirmation"));assertNull(scope.fields().get("confirmation"));
        assertFalse(scope.fields().containsKey("task"));assertFalse(scope.fields().containsKey("wait"));
        var changed=new Subject("opportunity.customer_requirement_draft",UUID.randomUUID(),0L,null);
        assertNotEquals(scope.canonical(),CommandScope.customerRequirements(command,opportunity,basis,changed,null).canonical());
        assertThrows(IllegalArgumentException.class,()->CommandScope.customerRequirements(command,opportunity,basis,null,null));
        assertThrows(IllegalArgumentException.class,()->CommandScope.customerRequirements(command,opportunity,basis,new Subject("execution.action_draft",draft.id(),0L,null),null));
        var delegate=new Actor(actor.tenantId(),actor.principalId(),actor.appointmentId(),UUID.randomUUID(),UUID.randomUUID(),PrincipalKind.HUMAN);
        var delegated=new CommandEnvelope(command.type(),UUID.randomUUID(),UUID.randomUUID(),delegate,Map.of());
        assertThrows(IllegalArgumentException.class,()->CommandScope.customerRequirements(delegated,opportunity,basis,draft,null));
    }
    @Test void customerCommandsRequireHumanAndNeverCreateOrdinaryTaskScope() {
        var tenant=UUID.randomUUID();var actor=new Actor(tenant,UUID.randomUUID(),UUID.randomUUID(),null,null,PrincipalKind.HUMAN);
        for(String name:List.of("SAVE_OPPORTUNITY_CUSTOMER_DRAFT","CONFIRM_OPPORTUNITY_CUSTOMER_REQUIREMENTS")) {
            var type=CommandEnvelope.Type.valueOf(name);
            var command=new CommandEnvelope(type,UUID.randomUUID(),UUID.randomUUID(),actor,Map.of());
            assertEquals(CommandEnvelope.Envelope.INTERNAL_ADMIN,command.envelope());
            assertFalse(type.internalMaintenance());assertFalse(type.recovery());
            var service=new Actor(tenant,actor.principalId(),actor.appointmentId(),null,null,PrincipalKind.SERVICE);
            assertThrows(IllegalArgumentException.class,()->new CommandEnvelope(type,UUID.randomUUID(),UUID.randomUUID(),service,Map.of()));
        }
    }
}
