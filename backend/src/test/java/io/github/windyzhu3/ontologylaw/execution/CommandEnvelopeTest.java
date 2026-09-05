package io.github.windyzhu3.ontologylaw.execution;

import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.PrincipalKind;
import java.util.*;
import org.junit.jupiter.api.Test;

class CommandEnvelopeTest {
    @Test void closed_type_and_trusted_kind_mapping_rejects_every_unregistered_pair() {
        for(var type:CommandEnvelope.Type.values())for(var kind:PrincipalKind.values()) {
            var actor=new Actor(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,null,kind);
            if(type.recovery() && kind==PrincipalKind.HUMAN || !type.recovery() && type!=CommandEnvelope.Type.CAPTURE_LEAD && kind==PrincipalKind.SERVICE)
                assertThrows(IllegalArgumentException.class,()->new CommandEnvelope(type,UUID.randomUUID(),UUID.randomUUID(),actor,Map.of()),type+":"+kind);
            else {
                var expected=kind==PrincipalKind.SERVICE?CommandEnvelope.Envelope.SERVICE_ACTOR:
                        type==CommandEnvelope.Type.CAPTURE_LEAD?CommandEnvelope.Envelope.INTERNAL_ADMIN:CommandEnvelope.Envelope.INTERNAL_TASK;
                assertEquals(expected,new CommandEnvelope(type,UUID.randomUUID(),UUID.randomUUID(),actor,Map.of()).envelope(),type+":"+kind);
            }
        }
    }
    @Test void service_cannot_represent_another_actor() {
        assertThrows(IllegalArgumentException.class,()->new Actor(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),PrincipalKind.SERVICE));
    }
    @Test void legacy_actor_is_human_and_cannot_enter_recovery() {
        var actor=new Actor(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,null);
        for(var type:List.of(CommandEnvelope.Type.REOPEN_DUE_CONTACT_TASKS,CommandEnvelope.Type.REOPEN_DUE_ROUTING_REVIEW_TASKS))
            assertThrows(IllegalArgumentException.class,()->new CommandEnvelope(type,UUID.randomUUID(),UUID.randomUUID(),actor,Map.of()));
    }
}
