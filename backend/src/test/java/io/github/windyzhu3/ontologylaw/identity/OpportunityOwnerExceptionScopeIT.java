package io.github.windyzhu3.ontologylaw.identity;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.testing.PostgresIntegrationTest;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class OpportunityOwnerExceptionScopeIT extends PostgresIntegrationTest {
    @Test void discovers_authorized_organizations_without_filtering_inactive_owners() throws Exception {
        var seed=AuthorizationServiceIT.seed(database,"SERVICE","OPPORTUNITY_OWNER_EXCEPTION_DISCOVER");
        var child=UUID.randomUUID();var human=UUID.randomUUID();var appointment=UUID.randomUUID();
        try(var c=database.apiConnection()) {
            inTransaction(c,Capability.COMMAND,x->{
                sql(x,"insert into identity.organization_unit (tenant_id,organization_unit_id,parent_organization_unit_id,unit_code,display_name,state,created_at) values (?,?,?,'CHILD','child','ACTIVE',clock_timestamp())",seed.tenant(),child,seed.org());
                sql(x,"insert into identity.principal (tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) values (?,?,'HUMAN','FORMER_FIXTURE',?,'former owner','ACTIVE',clock_timestamp())",seed.tenant(),human,new byte[32]);
                sql(x,"insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'SALES',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),appointment,human,child);
                sql(x,"update identity.appointment set state='SUSPENDED',revision=revision+1 where tenant_id=? and appointment_id=?",seed.tenant(),appointment);
                return null;
            });
            inTransaction(c,Capability.QUERY,x->{
                var scope=R2OpportunityServiceScopeReader.databaseBacked().ownerExceptionScope(x,actor(seed),Instant.now());
                assertTrue(scope.authorized());assertEquals(Set.of(seed.org(),child),scope.organizations());
                assertFalse(AuthorizationIdentityReader.databaseBacked().owner(x,seed.tenant(),appointment,Instant.now()).active());
                assertFalse(R2OpportunityServiceScopeReader.databaseBacked().opportunityScope(x,actor(seed),"OPPORTUNITY_TASK_ACTIVATE",Instant.now()).authorized());
                return null;
            });
        }
    }
    @Test void requires_current_exact_service_grant_and_keeps_tenants_separate() throws Exception {
        var seed=AuthorizationServiceIT.seed(database,"SERVICE","OPPORTUNITY_OWNER_EXCEPTION_DISCOVER");
        var other=AuthorizationServiceIT.seed(database,"SERVICE","OPPORTUNITY_OWNER_EXCEPTION_DISCOVER");
        try(var c=database.apiConnection()) {
            inTransaction(c,Capability.QUERY,x->{
                var reader=R2OpportunityServiceScopeReader.databaseBacked();
                assertFalse(reader.ownerExceptionScope(x,seed.request().actor(),Instant.now()).authorized());
                assertFalse(reader.ownerExceptionScope(x,new Actor(other.tenant(),seed.principal(),seed.appointment(),null,null,PrincipalKind.SERVICE),Instant.now()).authorized());
                assertEquals(Set.of(other.org()),reader.ownerExceptionScope(x,actor(other),Instant.now()).organizations());return null;
            });
            inTransaction(c,Capability.COMMAND,x->{sql(x,"update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_grant_id=?",seed.tenant(),seed.grant());return null;});
            inTransaction(c,Capability.QUERY,x->{assertFalse(R2OpportunityServiceScopeReader.databaseBacked().ownerExceptionScope(x,actor(seed),Instant.now()).authorized());return null;});
        }
    }
    private static Actor actor(AuthorizationServiceIT.Seed s){return new Actor(s.tenant(),s.principal(),s.appointment(),null,null,PrincipalKind.SERVICE);}
}
