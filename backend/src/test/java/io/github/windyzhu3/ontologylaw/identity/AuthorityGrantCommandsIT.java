package io.github.windyzhu3.ontologylaw.identity;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class AuthorityGrantCommandsIT extends IdentityAdminFixture {
    @Test void grant_is_exact_immutable_then_revoked_without_any_event()throws Exception {UUID org=organization(),app=appointment(principal(),org);var before=counts();UUID id=grant(app,org);delta(before,3);before=counts();success(change("REVOKE_AUTHORITY_GRANT",id));delta(before,-1);rejected("IDENTITY_STATE_CONFLICT",change("REVOKE_AUTHORITY_GRANT",id));}
    @ParameterizedTest @ValueSource(strings={"IDENTITY_PRINCIPAL_MANAGE","IDENTITY_ORGANIZATION_MANAGE","IDENTITY_APPOINTMENT_MANAGE","IDENTITY_AUTHORITY_MANAGE","REOPEN_DUE_CONTACT_TASKS","ARBITRARY"})
    void non_business_authority_is_preslot_validation(String code)throws Exception {var before=counts();assertThrows(IdentityCommands.Failure.class,()->execute("CREATE_AUTHORITY_GRANT",null,null,body("appointmentId",seed.appointment().toString(),"scopeOrganizationId",seed.org().toString(),"authorityCode",code,"validFrom","2026-01-01T00:00:00Z","validUntil",null)));assertEquals(before,counts());}
    @Test void self_grant_to_any_appointment_of_caller_is_refused()throws Exception {UUID org=organization(),app=appointment(seed.principal(),org);rejected("IDENTITY_SELF_LOCKOUT",execute("CREATE_AUTHORITY_GRANT",null,null,body("appointmentId",app.toString(),"scopeOrganizationId",org.toString(),"authorityCode","SALES_CONTACT_OWNER","validFrom",java.time.Instant.now().toString(),"validUntil",null)));}
    @Test void last_root_management_grant_cannot_be_revoked()throws Exception {rejected("IDENTITY_LAST_ADMIN",change("REVOKE_AUTHORITY_GRANT",seed.grant()));}
    @Test void grant_window_outside_frozen_appointment_is_terminal_refusal()throws Exception {UUID org=organization(),app=appointment(principal(),org);rejected("IDENTITY_STATE_CONFLICT",execute("CREATE_AUTHORITY_GRANT",null,null,body("appointmentId",app.toString(),"scopeOrganizationId",org.toString(),"authorityCode","SALES_CONTACT_OWNER","validFrom","2025-01-01T00:00:00Z","validUntil",null)));}
}
