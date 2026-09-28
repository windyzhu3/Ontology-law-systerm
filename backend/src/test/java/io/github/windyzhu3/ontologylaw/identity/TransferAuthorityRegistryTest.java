package io.github.windyzhu3.ontologylaw.identity;
import java.util.*;import org.junit.jupiter.params.ParameterizedTest;import org.junit.jupiter.params.provider.ValueSource;import static org.junit.jupiter.api.Assertions.*;
class TransferAuthorityRegistryTest {
 @ParameterizedTest @ValueSource(strings={"TRANSFER_SUBMIT","TRANSFER_REVIEW","TRANSFER_ACCEPT","MATTER_CLASSIFY","MATTER_RECEIVE","PAYMENT_SUBMIT","PAYMENT_CONFIRM","CONTRACT_TERMINATION_REVIEW"})
 void authorized_admin_can_prepare_only_registered_named_business_grants(String code){var body=new HashMap<String,Object>();body.put("appointmentId",UUID.randomUUID().toString());body.put("authorityCode",code);body.put("scopeOrganizationId",UUID.randomUUID().toString());body.put("validFrom","2026-09-27T01:00:00Z");body.put("validUntil",null);assertEquals(code,IdentityCommands.validate(IdentityCommands.handler("CREATE_AUTHORITY_GRANT"),body).get("authorityCode"));}
}
