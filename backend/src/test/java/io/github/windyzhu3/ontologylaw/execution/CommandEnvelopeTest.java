package io.github.windyzhu3.ontologylaw.execution;

import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.PrincipalKind;
import java.util.*;
import org.junit.jupiter.api.Test;

class CommandEnvelopeTest {
    @Test void closed_type_and_trusted_kind_mapping_rejects_every_unregistered_pair() {
        var business=List.of(CommandEnvelope.Type.RESOLVE_DUPLICATE_LEAD,CommandEnvelope.Type.COMPLETE_LEAD_INGRESS,CommandEnvelope.Type.ASSIGN_LEAD,CommandEnvelope.Type.RECORD_ROUTING_DISPOSITION,CommandEnvelope.Type.ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST,CommandEnvelope.Type.RECORD_CONTACT_RESULT,CommandEnvelope.Type.REVIEW_LEAD_VALIDITY,CommandEnvelope.Type.CAPTURE_LEAD,CommandEnvelope.Type.SAVE_ACTION_DRAFT,CommandEnvelope.Type.RECORD_OPPORTUNITY_PROGRESS,CommandEnvelope.Type.REOPEN_DUE_CONTACT_TASKS,CommandEnvelope.Type.REOPEN_DUE_ROUTING_REVIEW_TASKS);
        for(var type:business)for(var kind:PrincipalKind.values()) {
            var actor=new Actor(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,null,kind);
            if(type.internalMaintenance() && kind==PrincipalKind.HUMAN || !type.internalMaintenance() && type!=CommandEnvelope.Type.CAPTURE_LEAD && kind==PrincipalKind.SERVICE)
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
        assertEquals(76,Arrays.stream(CommandEnvelope.Type.values()).filter(type->type!=CommandEnvelope.Type.ACTIVATE_INITIAL_OPPORTUNITY_TASK&&type!=CommandEnvelope.Type.RECORD_OPPORTUNITY_PROGRESS&&type!=CommandEnvelope.Type.REOPEN_DUE_OPPORTUNITY_TASKS).count());
        assertEquals(79,CommandEnvelope.Type.values().length);
        assertEquals(Set.of(CommandEnvelope.Type.SAVE_OPPORTUNITY_CUSTOMER_DRAFT,CommandEnvelope.Type.CONFIRM_OPPORTUNITY_CUSTOMER_REQUIREMENTS),Arrays.stream(CommandEnvelope.Type.values()).filter(CommandEnvelope.Type::customerRequirements).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of(CommandEnvelope.Type.OBSERVE_OPPORTUNITY_OWNER_EXCEPTION,CommandEnvelope.Type.TRANSFER_OPPORTUNITY_RESPONSIBILITY,CommandEnvelope.Type.RECORD_OPPORTUNITY_OWNER_COORDINATION),Arrays.stream(CommandEnvelope.Type.values()).filter(CommandEnvelope.Type::ownerException).collect(java.util.stream.Collectors.toSet()));
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
    @Test void initial_activation_is_service_maintenance_without_wait_recovery_semantics() {
        var type=CommandEnvelope.Type.ACTIVATE_INITIAL_OPPORTUNITY_TASK;
        assertTrue(type.internalMaintenance());assertFalse(type.recovery());
        var human=new Actor(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,null);
        assertThrows(IllegalArgumentException.class,()->new CommandEnvelope(type,UUID.randomUUID(),UUID.randomUUID(),human,Map.of()));
        var actor=new Actor(human.tenantId(),human.principalId(),human.appointmentId(),null,null,PrincipalKind.SERVICE);
        assertEquals(CommandEnvelope.Envelope.SERVICE_ACTOR,new CommandEnvelope(type,UUID.randomUUID(),UUID.randomUUID(),actor,Map.of()).envelope());
    }
    @Test void registered_contract_commands_are_human_admin_and_reconciliation_is_service_only() {
        var names=Set.of("REQUEST_CONTRACT_PREPARATION","RECORD_CONTRACT_PREPARATION_DECISION","START_CONTRACT_PREPARATION","SAVE_CONTRACT_DRAFT","FORM_CONTRACT","REQUEST_CONTRACT_REVIEW","RECORD_CONTRACT_REVIEW","REQUEST_CONTRACT_APPROVAL","RECORD_CONTRACT_DECISION",
                "SAVE_CONTRACT_SIGNATURE_DRAFT","CONFIRM_CONTRACT_SIGNATURE_ARRANGEMENT",
                "SUBMIT_CONTRACT_SIGNATURE","RECORD_CONTRACT_SIGNATURE_VERIFICATION","ARCHIVE_CONTRACT_SIGNATURE",
                "RETURN_CONTRACT_SIGNATURE_FOR_REVISION","RETURN_CONTRACT_FOR_REVISION",
                "END_CONTRACT_NEGOTIATION","REQUEST_CONTRACT_TERMINATION_REVIEW","RECORD_CONTRACT_TERMINATION_REVIEW",
                "VERIFY_CONTRACT_EXECUTION_CONDITIONS","REQUEST_CONTRACT_RECEIPT_REVIEW",
                "RECORD_CONTRACT_RECEIPT_REVIEW","SUPPLEMENT_CONTRACT_RECEIPT");
        assertEquals(names,Arrays.stream(CommandEnvelope.Type.values()).filter(CommandEnvelope.Type::contracts).map(Enum::name).collect(java.util.stream.Collectors.toSet()));
        var human=new Actor(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,null);
        var service=new Actor(human.tenantId(),human.principalId(),human.appointmentId(),null,null,PrincipalKind.SERVICE);
        for(String name:names){var type=CommandEnvelope.Type.valueOf(name);assertFalse(type.internalMaintenance());assertEquals(CommandEnvelope.Envelope.INTERNAL_ADMIN,new CommandEnvelope(type,UUID.randomUUID(),UUID.randomUUID(),human,Map.of()).envelope());assertThrows(IllegalArgumentException.class,()->new CommandEnvelope(type,UUID.randomUUID(),UUID.randomUUID(),service,Map.of()));}
        var recovery=CommandEnvelope.Type.RECONCILE_CONTRACT_PREPARATION;
        assertFalse(recovery.contracts());assertTrue(recovery.internalMaintenance());
        assertEquals(CommandEnvelope.Envelope.SERVICE_ACTOR,new CommandEnvelope(recovery,UUID.randomUUID(),UUID.randomUUID(),service,Map.of()).envelope());
        assertThrows(IllegalArgumentException.class,()->new CommandEnvelope(recovery,UUID.randomUUID(),UUID.randomUUID(),human,Map.of()));
    }
}
