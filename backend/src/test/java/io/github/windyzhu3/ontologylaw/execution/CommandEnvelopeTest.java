package io.github.windyzhu3.ontologylaw.execution;

import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.PrincipalKind;
import java.util.*;
import org.junit.jupiter.api.Test;

class CommandEnvelopeTest {
    @Test void closed_type_and_trusted_kind_mapping_rejects_every_unregistered_pair() {
        var business=List.of(CommandEnvelope.Type.RESOLVE_DUPLICATE_LEAD,CommandEnvelope.Type.COMPLETE_LEAD_INGRESS,CommandEnvelope.Type.ASSIGN_LEAD,CommandEnvelope.Type.RECORD_ROUTING_DISPOSITION,CommandEnvelope.Type.ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST,CommandEnvelope.Type.RECORD_CONTACT_RESULT,CommandEnvelope.Type.REVIEW_LEAD_VALIDITY,CommandEnvelope.Type.CAPTURE_LEAD,CommandEnvelope.Type.SAVE_ACTION_DRAFT,CommandEnvelope.Type.REOPEN_DUE_CONTACT_TASKS,CommandEnvelope.Type.REOPEN_DUE_ROUTING_REVIEW_TASKS);
        for(var type:business)for(var kind:PrincipalKind.values()) {
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
    @Test void fourteen_identity_types_are_human_internal_admin_and_reject_service() {
        var identity=List.of("CREATE_IDENTITY_PRINCIPAL","RENAME_IDENTITY_PRINCIPAL","SUSPEND_IDENTITY_PRINCIPAL","RESUME_IDENTITY_PRINCIPAL","DISABLE_IDENTITY_PRINCIPAL","CREATE_ORGANIZATION_UNIT","RENAME_ORGANIZATION_UNIT","CLOSE_ORGANIZATION_UNIT","CREATE_APPOINTMENT","SUSPEND_APPOINTMENT","RESUME_APPOINTMENT","END_APPOINTMENT","CREATE_AUTHORITY_GRANT","REVOKE_AUTHORITY_GRANT");
        assertEquals(25,CommandEnvelope.Type.values().length);
        for(String name:identity){var type=CommandEnvelope.Type.valueOf(name);var human=new Actor(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,null);assertEquals(CommandEnvelope.Envelope.INTERNAL_ADMIN,new CommandEnvelope(type,UUID.randomUUID(),UUID.randomUUID(),human,Map.of()).envelope());var service=new Actor(human.tenantId(),human.principalId(),human.appointmentId(),null,null,PrincipalKind.SERVICE);assertThrows(IllegalArgumentException.class,()->new CommandEnvelope(type,UUID.randomUUID(),UUID.randomUUID(),service,Map.of()));}
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
