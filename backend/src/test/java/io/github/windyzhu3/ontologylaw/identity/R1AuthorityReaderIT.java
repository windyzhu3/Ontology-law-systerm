package io.github.windyzhu3.ontologylaw.identity;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.testing.PostgresIntegrationTest;
import java.util.*;
import org.junit.jupiter.api.Test;

class R1AuthorityReaderIT extends PostgresIntegrationTest {
    @Test void resolves_a_complete_current_path_and_drops_it_after_exact_deny() throws Exception {
        var s=AuthorizationServiceIT.seed(database);
        try(var c=database.apiConnection()) {
            inTransaction(c,Capability.QUERY,x->{
                var reader=reader();
                var request=reader.select(x,s.request().actor(),s.request().subject(),s.org(),"SOURCE_INTAKE_OWNER","LEAD_INGRESS_COMPLETE");
                assertNotNull(request);assertEquals(s.grant(),request.requirement().authorityFactId());
                assertNull(reader.select(x,s.request().actor(),s.request().subject(),UUID.randomUUID(),"SOURCE_INTAKE_OWNER","LEAD_INGRESS_COMPLETE"));
                return null;
            });
            inTransaction(c,Capability.COMMAND,x->{
                sql(x,"insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) values (?,?,?,?,'LEAD_INGRESS_COMPLETE','DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),'lead.lead',?,0)",s.tenant(),UUID.randomUUID(),s.principal(),s.appointment(),s.subject());return null;
            });
            inTransaction(c,Capability.QUERY,x->{assertNull(reader().select(x,s.request().actor(),s.request().subject(),s.org(),"SOURCE_INTAKE_OWNER","LEAD_INGRESS_COMPLETE"));return null;});
        }
    }
    @Test void candidates_are_tenant_bound_and_require_active_human_complete_grants() throws Exception {
        var s=AuthorizationServiceIT.seed(database,"HUMAN","SALES_CONTACT_OWNER");
        var other=AuthorizationServiceIT.seed(database,"HUMAN","SALES_CONTACT_OWNER");
        try(var c=database.apiConnection()) {
            inTransaction(c,Capability.QUERY,x->{
                var candidates=reader().candidates(x,s.tenant(),s.request().subject(),s.org(),"ASSIGNMENT_OWNER","SALES_CONTACT_OWNER");
                assertEquals(List.of(s.appointment()),candidates.stream().map(R1AuthorityReader.Candidate::appointmentId).toList());
                assertTrue(reader().candidates(x,other.tenant(),s.request().subject(),s.org(),"ASSIGNMENT_OWNER","SALES_CONTACT_OWNER").isEmpty());return null;
            });
            inTransaction(c,Capability.COMMAND,x->{sql(x,"update identity.appointment set state='SUSPENDED',revision=revision+1 where tenant_id=? and appointment_id=?",s.tenant(),s.appointment());return null;});
            inTransaction(c,Capability.QUERY,x->{assertTrue(reader().candidates(x,s.tenant(),s.request().subject(),s.org(),"ASSIGNMENT_OWNER","SALES_CONTACT_OWNER").isEmpty());return null;});
        }
    }
    private R1AuthorityReader reader() {return R1AuthorityReader.databaseBacked();}
    @Test void candidate_appointment_must_belong_to_the_policy_root_subtree()throws Exception {
        var s=AuthorizationServiceIT.seed(database,"HUMAN","SALES_CONTACT_OWNER");UUID outside=UUID.randomUUID(),appointment=UUID.randomUUID();
        try(var c=database.apiConnection()){
            inTransaction(c,Capability.COMMAND,x->{
                sql(x,"insert into identity.organization_unit (tenant_id,organization_unit_id,unit_code,display_name,state,created_at) values (?,?,'OUTSIDE','outside','ACTIVE',clock_timestamp())",s.tenant(),outside);
                sql(x,"insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'SALES',clock_timestamp()-interval '2 days','ACTIVE',clock_timestamp())",s.tenant(),appointment,s.principal(),outside);
                sql(x,"insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values (?,?,?,?,?,'SALES_CONTACT_OWNER',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",s.tenant(),UUID.randomUUID(),appointment,s.appointment(),s.org());return null;
            });
            inTransaction(c,Capability.QUERY,x->{assertEquals(List.of(s.appointment()),reader().candidates(x,s.tenant(),s.request().subject(),s.org(),"ASSIGNMENT_OWNER","SALES_CONTACT_OWNER").stream().map(R1AuthorityReader.Candidate::appointmentId).toList());return null;});
        }
    }
}
