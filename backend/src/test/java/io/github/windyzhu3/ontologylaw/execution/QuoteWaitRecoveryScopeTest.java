package io.github.windyzhu3.ontologylaw.execution;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import static org.junit.jupiter.api.Assertions.*;

class QuoteWaitRecoveryScopeTest {
    @Test void quote_reply_is_an_exact_distinct_recovery_source_without_fabricated_progress() {
        UUID tenant=UUID.randomUUID(),task=UUID.randomUUID(),source=UUID.randomUUID();
        var opportunity=new Subject("opportunity.opportunity",UUID.randomUUID(),1L,null);
        String digest="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        var wait=new Subject("responsibility.wait_receipt",UUID.randomUUID(),null,digest);
        var quote=new Subject("opportunity.quote_response",source,null,digest);
        var scope=assertDoesNotThrow(()->CommandScope.opportunityRecovery(tenant,task,opportunity,wait,quote));
        assertTrue(scope.canonical().contains("opportunity.quote_response"));
        assertNotEquals(scope.canonical(),CommandScope.opportunityRecovery(tenant,task,opportunity,wait,
            new Subject("opportunity.opportunity_progress",source,null,digest)).canonical());
    }
}
