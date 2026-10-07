package io.github.windyzhu3.ontologylaw.identity;

import io.github.windyzhu3.ontologylaw.testing.PostgresIntegrationTest;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CrossDepartmentWorkbenchEntryIT extends PostgresIntegrationTest {
    @Test void entry_uses_the_selected_appointments_actual_scope_without_granting_its_home_department() throws Exception {
        var seed=AuthorizationServiceIT.seed(database,"HUMAN","PAYMENT_CONFIRM");var finance=UUID.randomUUID();var sales=UUID.randomUUID();var other=UUID.randomUUID();var appointment=UUID.randomUUID();var grant=UUID.randomUUID();
        try(var c=database.migratorConnection()) {
            sql(c,"insert into identity.organization_unit(tenant_id,organization_unit_id,parent_organization_unit_id,unit_code,display_name,state,created_at) values (?, ?, ?, 'FINANCE','Finance','ACTIVE',clock_timestamp()),(?, ?, ?, 'SALES','Sales','ACTIVE',clock_timestamp())",seed.tenant(),finance,seed.org(),seed.tenant(),sales,seed.org());
            sql(c,"insert into identity.appointment(tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values(?,?,?,?,'OWNER',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),appointment,seed.principal(),finance);
            sql(c,"insert into identity.authority_grant(tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,state,created_at) values(?,?,?,?,?,'PAYMENT_CONFIRM',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),grant,appointment,seed.appointment(),sales);
            sql(c,"insert into identity.appointment(tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values(?,?,?,?,'OWNER',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),other,seed.principal(),finance);
        }
        var actor=new Actor(seed.tenant(),seed.principal(),appointment,null,null);var reader=R1AuthorityReader.databaseBacked();
        try(var c=database.apiConnection()) {
            inTransaction(c,Capability.QUERY,x->{
                var paths=reader.entryAuthorizations(x,actor,"OPPORTUNITY_OWNER","PAYMENT_CONFIRM");assertEquals(1,paths.size());assertEquals(sales,paths.getFirst().request().scopeOrganizationId());assertEquals(grant,paths.getFirst().request().requirement().authorityFactId());
                assertTrue(reader.entryAuthorizations(x,new Actor(seed.tenant(),seed.principal(),other,null,null),"OPPORTUNITY_OWNER","PAYMENT_CONFIRM").isEmpty());
                assertNull(reader.select(x,actor,new Subject("identity.organization_unit",finance,0L,null),finance,"OPPORTUNITY_OWNER","PAYMENT_CONFIRM"));return null;
            });
        }
        try(var c=database.migratorConnection()){sql(c,"update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='TEST',revision=revision+1 where tenant_id=? and authority_grant_id=?",seed.tenant(),grant);}
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{assertTrue(reader.entryAuthorizations(x,actor,"OPPORTUNITY_OWNER","PAYMENT_CONFIRM").isEmpty());return null;});}
    }
}
