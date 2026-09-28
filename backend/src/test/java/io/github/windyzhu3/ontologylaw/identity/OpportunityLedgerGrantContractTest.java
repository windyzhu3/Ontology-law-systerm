package io.github.windyzhu3.ontologylaw.identity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
class OpportunityLedgerGrantContractTest {
    @Test void exactly_named_ledger_read_uses_existing_controlled_grant_handler(){
        var handler=IdentityCommands.handler("CREATE_AUTHORITY_GRANT");
        var body=new TreeMap<String,Object>();body.put("appointmentId",UUID.randomUUID().toString());body.put("scopeOrganizationId",UUID.randomUUID().toString());body.put("validFrom","2026-09-15T00:00:00Z");body.put("validUntil",null);
        assertEquals("IDENTITY_AUTHORITY_MANAGE",handler.authority());
        body.put("authorityCode","OPPORTUNITY_LEDGER_READ");assertEquals("OPPORTUNITY_LEDGER_READ",IdentityCommands.validate(handler,body).get("authorityCode"));
        for(String unregistered:List.of("OPPORTUNITY_LEDGER_ADMIN","OPPORTUNITY_LEDGER_WRITE","OPPORTUNITY_LEDGER_READ_ALL")){body.put("authorityCode",unregistered);assertThrows(IdentityCommands.Failure.class,()->IdentityCommands.validate(handler,body));}
    }
}
