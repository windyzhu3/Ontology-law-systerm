package io.github.windyzhu3.ontologylaw.identity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
class OwnerExceptionGrantContractTest {
 private Map<String,Object> grant(String authority){var body=new TreeMap<String,Object>();body.put("appointmentId",UUID.randomUUID().toString());body.put("authorityCode",authority);body.put("scopeOrganizationId",UUID.randomUUID().toString());body.put("validFrom","2026-09-15T00:00:00Z");body.put("validUntil",null);return body;}
 @Test void named_sales_exception_capabilities_use_existing_identity_authority_handler(){var handler=IdentityCommands.handler("CREATE_AUTHORITY_GRANT");assertEquals("IDENTITY_AUTHORITY_MANAGE",handler.authority());for(String code:List.of("SALES_OPPORTUNITY_OWNER","OPPORTUNITY_OWNER_EXCEPTION_DISCOVER","OPPORTUNITY_OWNER_EXCEPTION_READ","OPPORTUNITY_OWNER_EXCEPTION_RESOLVE","OPPORTUNITY_OWNER_EXCEPTION_OPERATIONS_READ"))assertEquals(code,IdentityCommands.validate(handler,grant(code)).get("authorityCode"));}
 @Test void registered_capabilities_do_not_enable_arbitrary_grants(){var handler=IdentityCommands.handler("CREATE_AUTHORITY_GRANT");for(String code:List.of("OPPORTUNITY_OWNER_EXCEPTION_ADMIN","GRANT_ALL"))assertThrows(IdentityCommands.Failure.class,()->IdentityCommands.validate(handler,grant(code)));}
}
