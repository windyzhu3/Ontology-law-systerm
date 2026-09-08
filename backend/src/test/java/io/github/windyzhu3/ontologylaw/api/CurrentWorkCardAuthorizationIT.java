package io.github.windyzhu3.ontologylaw.api;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;
import io.github.windyzhu3.ontologylaw.lead.WorkcardTestFixture;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import io.github.windyzhu3.ontologylaw.execution.CanonicalJson;
import java.util.*;
import java.sql.*;
import org.junit.jupiter.api.Test;

class CurrentWorkCardAuthorizationIT extends WorkcardTestFixture {
    private ResponseRead read(Actor actor,String tag)throws Exception {
        try(var c=database.apiConnection()){var r=new CurrentWorkCardDisclosureService(protection,policies,"AUTH_WORKCARD_IT").read(c,actor,UUID.randomUUID(),tag);return new ResponseRead(r.status(),r.body(),r.etag(),r.errorCode());}
    }
    private record ResponseRead(int status,Map<String,Object> body,String etag,String code) {}
    private Actor other(String kind,boolean grant)throws Exception {
        UUID principal=UUID.randomUUID(),appointment=UUID.randomUUID();
        mutate("insert into identity.principal (tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) values (?,?,?,'OTHER',?,'其他用户','ACTIVE',clock_timestamp())",seed.tenant(),principal,kind,CanonicalJson.digest(principal.toString()));
        mutate("insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'OWNER',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),appointment,principal,seed.org());
        if(grant)mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,'LEAD_INGRESS_COMPLETE',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),appointment,seed.appointment(),seed.org());
        return new Actor(seed.tenant(),principal,appointment,null,null,PrincipalKind.valueOf(kind));
    }
    @Test void direct_other_owner_has_safe_zero_and_delegation_only_discloses_exact_represented_owner_with_isolated_etag()throws Exception {
        setupCard(TaskFactory.Type.COMPLETE_LEAD_INGRESS);var own=read(seed.request().actor(),null);assertEquals(200,own.status());
        var delegate=other("HUMAN",true);var direct=read(delegate,own.etag());assertEquals(200,direct.status());assertNull(direct.body().get("currentCard"));assertEquals(5,auditCount());assertNotEquals(own.etag(),direct.etag());
        mutate("insert into identity.delegation_grant (tenant_id,delegation_grant_id,source_authority_grant_id,delegator_appointment_id,delegate_appointment_id,scope_organization_unit_id,valid_from,state,created_at) values (?,?,?,?,?,?,clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),seed.grant(),seed.appointment(),delegate.appointmentId(),seed.org());
        var represented=new Actor(seed.tenant(),delegate.principalId(),delegate.appointmentId(),seed.principal(),seed.appointment());
        var response=read(represented,own.etag());assertEquals(200,response.status());assertNotEquals(own.etag(),response.etag());assertEquals(current.selector().id().toString(),((Map<?,?>)response.body().get("currentCard")).get("taskId"));assertEquals(10,auditCount());
        assertEquals(304,read(represented,response.etag()).status());assertEquals(15,auditCount());
        var wrong=new Actor(seed.tenant(),delegate.principalId(),delegate.appointmentId(),delegate.principalId(),delegate.appointmentId());assertEquals(403,read(wrong,null).status());
    }
    @Test void missing_authentication_service_invalid_identity_and_object_only_allow_cannot_enter_workbench()throws Exception {
        setupCard(TaskFactory.Type.COMPLETE_LEAD_INGRESS);assertEquals(401,read(null,null).status());assertEquals(403,read(other("SERVICE",true),null).status());assertEquals(403,read(other("HUMAN",false),null).status());
        assertEquals(403,read(new Actor(UUID.randomUUID(),seed.principal(),seed.appointment(),null,null),null).status());
        mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='TEST',revision=revision+1 where tenant_id=? and authority_grant_id=?",seed.tenant(),seed.grant());
        mutate("insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values (?,?,?,?,'LEAD_INGRESS_COMPLETE','ALLOW',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),'responsibility.task_occurrence',?,0)",seed.tenant(),UUID.randomUUID(),seed.principal(),seed.appointment(),current.selector().id());
        var response=read(seed.request().actor(),null);assertEquals(403,response.status());assertEquals("NOT_AUTHORIZED",response.code());assertNull(response.body());assertNull(response.etag());assertEquals(0,auditCount());
    }
    @Test void lead_and_candidate_denies_are_separate_and_expired_or_revoked_grants_fail_closed()throws Exception {
        for(boolean leadDeny:List.of(false,true)) {
            setupCard(TaskFactory.Type.RESOLVE_LEAD_DUPLICATE);
            deny(leadDeny?current.lead():new Subject("lead.lead",secondaryLead,1L,null),"LEAD_INGRESS_RESOLVE");
            var response=readCard(null);assertEquals(200,response.status());assertNull(response.body().get("currentCard"));assertEquals(0,auditCount());assertFalse(response.body().toString().contains(secondaryLead.toString()));
        }
    }
    @Test void corrupt_protected_source_is_safe_500_but_denied_source_is_not_decrypted()throws Exception {
        setupCard(TaskFactory.Type.COMPLETE_LEAD_INGRESS);
        // The fixture imports corrupt ciphertext in a new Lead; it does not loosen the immutable capture guard.
        try(var c=database.adminConnection();var s=c.createStatement()) {
            s.execute("set session_replication_role = replica");
            try{sql(c,"update lead.lead set captured_name_ciphertext=decode('00','hex'),revision=1 where tenant_id=? and lead_id=?",seed.tenant(),current.lead().id());}
            finally{s.execute("set session_replication_role = origin");}
        }
        var broken=readCard(null);assertEquals(500,broken.status());assertEquals("INTERNAL_ERROR",broken.errorCode());assertNull(broken.body());assertNull(broken.etag());assertEquals(0,auditCount());
        deny(new Subject("lead.lead",current.lead().id(),1L,null),"LEAD_INGRESS_COMPLETE");var hidden=readCard(null);assertEquals(200,hidden.status());assertNull(hidden.body().get("currentCard"));assertEquals(0,auditCount());
    }
    @Test void persisted_unsafe_revision_or_unknown_task_type_is_safe_500()throws Exception {
        for(boolean revision:List.of(false,true)) {
            setupCard(TaskFactory.Type.COMPLETE_LEAD_INGRESS);
            try(var c=database.adminConnection();var s=c.createStatement()) {
                s.execute("set session_replication_role = replica");
                try {if(revision)sql(c,"update responsibility.task_occurrence set revision=9007199254740992 where tenant_id=? and task_occurrence_id=?",seed.tenant(),current.selector().id());
                    else sql(c,"update responsibility.task_occurrence set business_purpose_code='UNKNOWN_TASK' where tenant_id=? and task_occurrence_id=?",seed.tenant(),current.selector().id());}
                finally{s.execute("set session_replication_role = origin");}
            }
            var response=readCard(null);assertEquals(500,response.status());assertNull(response.body());assertNull(response.etag());assertEquals(0,auditCount());
        }
    }
    @Test void persisted_known_task_cannot_substitute_an_unregistered_primary_command_or_sla()throws Exception {
        for(String column:List.of("primary_command_code","original_sla_code")) {
            setupCard(TaskFactory.Type.COMPLETE_LEAD_INGRESS);
            try(var c=database.adminConnection();var s=c.createStatement()) {s.execute("set session_replication_role=replica");try{
                sql(c,"update responsibility.task_occurrence set "+column+"='WRONG_REGISTRY' where tenant_id=? and task_occurrence_id=?",seed.tenant(),current.selector().id());
            }finally{s.execute("set session_replication_role=origin");}}
            var response=readCard(null);assertEquals(500,response.status());assertNull(response.body());assertNull(response.etag());assertEquals(0,auditCount());
        }
    }
}
