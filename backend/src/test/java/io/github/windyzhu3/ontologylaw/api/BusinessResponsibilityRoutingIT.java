package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.testing.PostgresIntegrationTest;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService;
import io.github.windyzhu3.ontologylaw.opportunity.OpportunityProgressProtection;
import java.sql.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;

/** Real identity, scope, validity and denial checks; configuration never grants authority. */
class BusinessResponsibilityRoutingIT extends PostgresIntegrationTest {
    @Test void fixed_stages_duplicate_keys_and_missing_targets_are_closed(){
        var tenant=UUID.randomUUID();var org=UUID.randomUUID();var appointment=UUID.randomUUID();
        var entry=new BusinessResponsibilityRouting.Entry(tenant,org,"AWAIT_REVIEW",appointment);
        var routes=new BusinessResponsibilityRouting(List.of(entry));
        assertEquals(Optional.of(appointment),routes.target(tenant,org,"AWAIT_REVIEW"));
        assertTrue(routes.enabled(tenant));assertFalse(routes.enabled(UUID.randomUUID()));
        assertTrue(routes.target(tenant,org,"CHECK_RECEIPT").isEmpty());
        assertThrows(IllegalArgumentException.class,()->new BusinessResponsibilityRouting(List.of(entry,entry)));
        assertThrows(IllegalArgumentException.class,()->new BusinessResponsibilityRouting(List.of(new BusinessResponsibilityRouting.Entry(tenant,org,"PREPARE",appointment))));
    }

    UUID person(AuthorizationServiceIT.Seed seed,String name)throws Exception{
        var principal=UUID.randomUUID();var appointment=UUID.randomUUID();var subject=java.security.MessageDigest.getInstance("SHA-256").digest(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            sql(x,"insert into identity.principal(tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) values(?,?,'HUMAN','ROUTING_FIXTURE',?,?, 'ACTIVE',clock_timestamp())",seed.tenant(),principal,subject,name);
            sql(x,"insert into identity.appointment(tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values(?,?,?,?,'OWNER',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),appointment,principal,seed.org());
            for(String authority:List.of("CONTRACT_REVIEW","CONTRACT_SIGNATURE_VERIFY","CONTRACT_READ"))sql(x,"insert into identity.authority_grant(tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values(?,?,?,?,?,?,clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),appointment,seed.appointment(),seed.org(),authority);
            return null;
        });}return appointment;
    }
    static void sql(Connection c,String text,Object... args)throws SQLException{try(var p=c.prepareStatement(text)){for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);p.execute();}}
    ContractWorkflowPorts ports(BusinessResponsibilityRouting routes){return new ContractWorkflowPorts(OpportunityProgressProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES")),null,routes);}
    UUID selected(AuthorizationServiceIT.Seed seed,ContractWorkflowPorts ports,UUID incumbent)throws Exception{
        try(var c=database.apiConnection()){return inTransaction(c,Capability.QUERY,x->{AuthorizationService.databaseBacked().lockForEvaluation(x,seed.tenant());return ports.responsibilityOwner(x,seed.tenant(),seed.org(),List.of(seed.request().subject()),"AWAIT_REVIEW","CONTRACT_REVIEW",incumbent);});}
    }
    @Test void configured_yang_wins_over_two_root_directors_and_valid_incumbent_survives_config_change()throws Exception{
        var seed=AuthorizationServiceIT.seed(database,"HUMAN","CONTRACT_REVIEW");
        var huang=person(seed,"Huang director");var yang=person(seed,"Yang case supervisor");
        var routes=new BusinessResponsibilityRouting(List.of(new BusinessResponsibilityRouting.Entry(seed.tenant(),seed.org(),"AWAIT_REVIEW",yang)));
        assertEquals(yang,selected(seed,ports(routes),null));
        var changed=new BusinessResponsibilityRouting(List.of(new BusinessResponsibilityRouting.Entry(seed.tenant(),seed.org(),"AWAIT_REVIEW",huang)));
        assertEquals(yang,selected(seed,ports(changed),yang));
        assertNull(selected(seed,ports(new BusinessResponsibilityRouting(List.of())),null));
    }
    @Test void inactive_or_revoked_configured_owner_never_falls_back_to_director()throws Exception{
        var seed=AuthorizationServiceIT.seed(database,"HUMAN","CONTRACT_REVIEW");var yang=person(seed,"Yang case supervisor");
        var ports=ports(new BusinessResponsibilityRouting(List.of(new BusinessResponsibilityRouting.Entry(seed.tenant(),seed.org(),"AWAIT_REVIEW",yang))));
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{sql(x,"update identity.appointment set state='SUSPENDED',revision=revision+1 where tenant_id=? and appointment_id=?",seed.tenant(),yang);return null;});}
        assertNull(selected(seed,ports,null));
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{sql(x,"update identity.appointment set state='ACTIVE',revision=revision+1 where tenant_id=? and appointment_id=?",seed.tenant(),yang);sql(x,"update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='TEST',revision=revision+1 where tenant_id=? and grantee_appointment_id=? and authority_code='CONTRACT_REVIEW'",seed.tenant(),yang);return null;});}
        assertNull(selected(seed,ports,null));
    }
    @Test void foreign_target_and_missing_stage_are_closed_while_legacy_unique_candidate_remains_valid()throws Exception{
        var seed=AuthorizationServiceIT.seed(database,"HUMAN","CONTRACT_REVIEW");var other=AuthorizationServiceIT.seed(database,"HUMAN","CONTRACT_REVIEW");
        assertNull(selected(seed,ports(new BusinessResponsibilityRouting(List.of(new BusinessResponsibilityRouting.Entry(seed.tenant(),seed.org(),"AWAIT_REVIEW",other.appointment())))),null));
        assertNull(selected(seed,ports(new BusinessResponsibilityRouting(List.of(new BusinessResponsibilityRouting.Entry(seed.tenant(),seed.org(),"CHECK_RECEIPT",seed.appointment())))),null));
        assertEquals(seed.appointment(),selected(seed,ports(new BusinessResponsibilityRouting(List.of())),null));
    }
    @Test void direct_preparation_decision_uses_existing_department_policy_instead_of_arbitrary_director()throws Exception{
        var seed=AuthorizationServiceIT.seed(database,"HUMAN","CONTRACT_PREPARATION_DECIDE");var ding=person(seed,"Ding director");var huang=person(seed,"Huang director");var policy=UUID.randomUUID();
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{
            for(var appointment:List.of(seed.appointment(),ding,huang))for(String code:List.of("CONTRACT_APPROVE","CONTRACT_PREPARATION_DECIDE"))if(!appointment.equals(seed.appointment())||!code.equals("CONTRACT_PREPARATION_DECIDE"))sql(x,"insert into identity.authority_grant(tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values(?,?,?,?,?,?,clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),appointment,seed.appointment(),seed.org(),code);
            sql(x,"insert into contract.approval_policy(tenant_id,approval_policy_id,organization_unit_id,policy_code,policy_version,mode,policy_digest,created_at) values(?,?,?,'R2_CONTRACT_APPROVAL_V1',1,'REQUIRE_APPROVAL',decode(repeat('aa',32),'hex'),clock_timestamp())",seed.tenant(),policy,seed.org());
            sql(x,"insert into contract.approval_policy_member(tenant_id,approval_policy_member_id,policy_id,requirement_code,appointment_id,created_at) values(?,?,?,'LEGAL',?,clock_timestamp())",seed.tenant(),UUID.randomUUID(),policy,seed.appointment());return null;
        });}
        var ports=ports(new BusinessResponsibilityRouting(List.of(new BusinessResponsibilityRouting.Entry(seed.tenant(),seed.org(),"AWAIT_REVIEW",ding))));
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{AuthorizationService.databaseBacked().lockForEvaluation(x,seed.tenant());assertEquals(seed.appointment(),ports.preparationDecisionOwner(x,seed.tenant(),seed.org(),List.of(seed.request().subject())));return null;});}
    }
}
