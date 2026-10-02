package io.github.windyzhu3.ontologylaw.identity;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class IdentityBusinessRolesTest {
    private Map<String,Object> appointment(String role) {
        var body = new LinkedHashMap<String,Object>();
        body.put("principalId", "11111111-1111-4111-8111-111111111111");
        body.put("organizationId", "22222222-2222-4222-8222-222222222222");
        body.put("roleCode", role);
        body.put("effectiveFrom", "2026-10-01T00:00:00Z");
        body.put("effectiveUntil", null);
        return body;
    }

    @ParameterizedTest
    @ValueSource(strings={"SALES_REPRESENTATIVE","SALES_MANAGER","FINANCE_OPERATOR","CASE_ADMINISTRATOR","INTAKE_OPERATOR","ROUTING_SUPERVISOR","CONTACT_OPERATOR","CUSTOM_ADVISOR","IDENTITY_ADMIN","SERVICE_OPERATOR"})
    void creates_a_valid_appointment_with_each_supported_business_role(String role) {
        var result = IdentityCommands.validate(IdentityCommands.handler("CREATE_APPOINTMENT"), appointment(role));
        assertEquals(role, result.get("roleCode"));
    }

    @ParameterizedTest
    @ValueSource(strings={"sales_manager","","BAD-ROLE","AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void ordinary_appointment_creation_rejects_platform_and_unknown_roles(String role) {
        var failure = assertThrows(IdentityCommands.Failure.class,
            () -> IdentityCommands.validate(IdentityCommands.handler("CREATE_APPOINTMENT"), appointment(role)));
        assertEquals("VALIDATION_FAILED", failure.code());
    }
}
