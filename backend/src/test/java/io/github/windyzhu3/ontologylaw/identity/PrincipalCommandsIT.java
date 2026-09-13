package io.github.windyzhu3.ontologylaw.identity;

import io.github.windyzhu3.ontologylaw.execution.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PrincipalCommandsIT extends IdentityAdminFixture {
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"PROVIDER","ISSUER","TENANT","APPOINTMENT","EXPIRED","TAMPER","KEY"})
    void candidate_rejects_wrong_trust_actor_expiry_and_integrity_before_slot(String fault)throws Exception {
        var bound=actor;if(fault.equals("TENANT"))bound=new AuthorizationService.Actor(UUID.randomUUID(),actor.principalId(),actor.appointmentId(),null,null);if(fault.equals("APPOINTMENT"))bound=new AuthorizationService.Actor(actor.tenantId(),actor.principalId(),UUID.randomUUID(),null,null);
        var cipher=fault.equals("KEY")?new IdentityCandidateProtection("different",Map.of("different",key())):candidates;
        String selector=cipher.issue(bound,fault.equals("PROVIDER")?"OTHER":"FIXTURE",fault.equals("ISSUER")?"https://other.example.invalid/realms/other":directory.issuer(),"name",UUID.randomUUID().toString(),Instant.now().minusSeconds(fault.equals("EXPIRED")?301:0));if(fault.equals("TAMPER"))selector=selector.substring(0,100)+(selector.charAt(100)=='A'?"B":"A")+selector.substring(101);String attempted=selector;var before=counts();assertThrows(IdentityCommands.Failure.class,()->execute("CREATE_IDENTITY_PRINCIPAL",null,null,Map.of("providerUserSelector",attempted,"displayName","Rejected")));assertEquals(before,counts());
    }
    @Test void bind_same_subject_is_unique_and_same_name_different_subject_is_distinct()throws Exception {
        String subject=UUID.randomUUID().toString(),selector=candidates.issue(actor,"FIXTURE",directory.issuer(),"name",subject,Instant.now());var body=Map.<String,Object>of("providerUserSelector",selector,"displayName","Same name");var before=counts();var first=success(execute("CREATE_IDENTITY_PRINCIPAL",null,null,body));delta(before,0);
        before=counts();rejected("IDENTITY_BINDING_CONFLICT",execute("CREATE_IDENTITY_PRINCIPAL",null,null,body));delta(before,-1);
        String second=candidates.issue(actor,"FIXTURE",directory.issuer(),"other",UUID.randomUUID().toString(),Instant.now());assertNotEquals(first.id(),success(execute("CREATE_IDENTITY_PRINCIPAL",null,null,Map.of("providerUserSelector",second,"displayName","Same name"))).id());
        assertFalse(scalar("select row_to_json(p)::text from identity.principal p where tenant_id=? and principal_id=?",seed.tenant(),first.id()).contains(subject));
    }
    @Test void rename_normalizes_no_change_and_preserves_frozen_binding()throws Exception {
        UUID id=principal();String old=scalar("select identity_provider_code||encode(external_subject_hmac,'hex')||principal_kind from identity.principal where tenant_id=? and principal_id=?",seed.tenant(),id);String tag=tag("RENAME_IDENTITY_PRINCIPAL",id);var before=counts();
        var result=execute("RENAME_IDENTITY_PRINCIPAL",id,tag,Map.of("displayName","  Synthetic user  "));assertEquals(CommandOutcome.Status.NO_CHANGE,result.receipt().outcome().status());delta(before,-1);
        assertEquals(1,success(execute("RENAME_IDENTITY_PRINCIPAL",id,tag,Map.of("displayName","Renamed"))).revision());assertEquals(old,scalar("select identity_provider_code||encode(external_subject_hmac,'hex')||principal_kind from identity.principal where tenant_id=? and principal_id=?",seed.tenant(),id));
    }
    @Test void lifecycle_requires_fresh_cas_and_disabled_is_terminal()throws Exception {
        UUID id=principal();String tag=tag("SUSPEND_IDENTITY_PRINCIPAL",id);success(change("SUSPEND_IDENTITY_PRINCIPAL",id));
        rejected("STALE_IDENTITY",execute("RESUME_IDENTITY_PRINCIPAL",id,tag,Map.of("reasonCode","SECURITY_RESPONSE")));success(change("RESUME_IDENTITY_PRINCIPAL",id));success(change("DISABLE_IDENTITY_PRINCIPAL",id));rejected("IDENTITY_STATE_CONFLICT",change("RESUME_IDENTITY_PRINCIPAL",id));
    }
    @Test void own_disable_and_last_founder_suspend_are_terminal_refusals()throws Exception {rejected("IDENTITY_SELF_LOCKOUT",change("DISABLE_IDENTITY_PRINCIPAL",seed.principal()));rejected("IDENTITY_LAST_ADMIN",change("SUSPEND_IDENTITY_PRINCIPAL",seed.principal()));}
    @Test void disable_requires_all_appointments_ended()throws Exception {UUID id=principal();appointment(id,organization());var before=counts();rejected("IDENTITY_ORGANIZATION_DEPENDENCY",change("DISABLE_IDENTITY_PRINCIPAL",id));delta(before,-1);}
    @Test void original_create_replay_survives_selector_expiry_and_rejects_changed_payload()throws Exception {
        UUID key=UUID.randomUUID();String selector=candidates.issue(actor,"FIXTURE",directory.issuer(),"name",UUID.randomUUID().toString(),Instant.now().minusSeconds(298));var body=Map.<String,Object>of("providerUserSelector",selector,"displayName","Original");var result=execute(key,"CREATE_IDENTITY_PRINCIPAL",null,null,body);success(result);Thread.sleep(2200);var before=counts();var replay=execute(key,"CREATE_IDENTITY_PRINCIPAL",null,null,body);assertTrue(replay.replay());assertEquals(result.receipt().outcome().receiptId(),replay.receipt().outcome().receiptId());assertEquals(before,counts());assertTrue(execute(key,"CREATE_IDENTITY_PRINCIPAL",null,null,Map.of("providerUserSelector",selector,"displayName","Altered")).conflict());assertEquals(before,counts());
    }
}
