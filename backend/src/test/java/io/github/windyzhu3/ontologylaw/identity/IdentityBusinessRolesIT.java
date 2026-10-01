package io.github.windyzhu3.ontologylaw.identity;

import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import io.github.windyzhu3.ontologylaw.execution.IdentityAdminReadRuntime;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class IdentityBusinessRolesIT extends IdentityAdminFixture {
    @ParameterizedTest
    @CsvSource({"SALES_REPRESENTATIVE,销售","SALES_MANAGER,销售主管","FINANCE_OPERATOR,财务人员","CASE_ADMINISTRATOR,案管员"})
    void new_business_appointment_is_manageable_and_has_a_precise_self_label(String role,String label)throws Exception {
        UUID org=organization(),person=principal();
        UUID app=success(execute("CREATE_APPOINTMENT",null,null,body("principalId",person.toString(),"organizationId",org.toString(),"roleCode",role,"effectiveFrom",Instant.now().minusSeconds(60).toString(),"effectiveUntil",null))).id();
        Map<String,Object> response;
        try(var c=database.apiConnection()) {
            response=new IdentityAdminReadRuntime(audit,protection,candidates,"FIXTURE",directory).read(c,actor,"listAppointments",null,null,50,null,null);
        }
        @SuppressWarnings("unchecked") var listed=(List<Map<String,Object>>)response.get("items");
        assertTrue(listed.stream().anyMatch(item->app.toString().equals(item.get("id"))&&role.equals(item.get("roleCode"))),"created business appointment must be visible for administration");
        try(var c=database.apiConnection()) {
            c.setAutoCommit(false);
            io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.setLocalRole(c,io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.Capability.QUERY);
            var identity=new HumanIdentityReader.VerifiedHumanIdentity(seed.tenant(),person,"FIXTURE");
            var self=HumanIdentityReader.databaseBacked().self(c,identity);
            assertEquals(1,self.choices().size());
            assertTrue(self.choices().getFirst().label().endsWith(" · "+label));
            c.rollback();
        }
        try(var c=database.apiConnection()) {
            var options=new IdentityAdminReadRuntime(audit,protection,candidates,"FIXTURE",directory).read(c,actor,"getIdentityAdminOptions","AUTHORITY_GRANTS","APPOINTMENT",50,null,null);
            @SuppressWarnings("unchecked") var candidates=(Map<String,Object>)options.get("candidates");
            @SuppressWarnings("unchecked") var choices=(List<Map<String,Object>>)candidates.get("items");
            assertTrue(choices.stream().anyMatch(item->app.toString().equals(item.get("id"))),"created appointment must appear as a grant recipient");
        }
        assertNotNull(grant(app,org),"the new appointment must accept an explicit named grant");
    }
}
