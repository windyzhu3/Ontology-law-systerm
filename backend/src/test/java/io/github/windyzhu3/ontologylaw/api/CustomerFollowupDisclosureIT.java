package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import io.github.windyzhu3.ontologylaw.execution.R1ServiceReadRuntime;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Customer maintenance remains readable across immutable waiting sources, with exact denial. */
class CustomerFollowupDisclosureIT extends R2FollowupAttemptIT {
    @Test void customer_context_after_followup_attempt_keeps_audit_and_exact_denial() throws Exception {
        setup(true,true);confirmed();verifyAttempt(false);
        verifyContextAndDenial("opportunity.followup_attempt","followup_attempt","followup_attempt_id","body_digest");
    }
    @Test void customer_context_after_ambiguous_quote_reply_keeps_audit_and_exact_denial() throws Exception {
        var evidence=delivered();
        quoteCommand("RECORD_QUOTE_RESPONSE",Map.of("kind","AMBIGUOUS","statement","请说明付款安排",
            "occurredAt",now().toString(),"nextCheckAt",now().plusSeconds(3600).toString(),
            "evidence",R2CustomerRequirementsServices.selector(evidence)));
        verifyContextAndDenial("opportunity.quote_response","quote_response","quote_response_id","response_content_digest");
    }
    private void verifyContextAndDenial(String type,String table,String idColumn,String digestColumn) throws Exception {
        var body=read();assertTrue(body.containsKey("confirmation"));
        assertTrue(Integer.parseInt(scalar("select count(*) from audit.audit_entry_classified_v where tenant_id=? and change_summary::text like '%R2_CUSTOMER_REQUIREMENTS_DISCLOSURE_V1%'",seed.tenant()))>0);
        var id=UUID.fromString(scalar("select "+idColumn+"::text from opportunity."+table+" where tenant_id=?",seed.tenant()));
        var digest=Base64.getDecoder().decode(scalar("select encode("+digestColumn+",'base64') from opportunity."+table+" where tenant_id=?",seed.tenant()));
        for(var code:List.of("SALES_OPPORTUNITY_OWNER","CUSTOMER_REQUIREMENTS_MANAGE"))
            mutate("insert into identity.object_access_grant(tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_hash) values(?,?,?,?,?,'DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),?,?,?)",
                seed.tenant(),UUID.randomUUID(),seed.request().actor().principalId(),seed.appointment(),code,type,id,digest);
        try {
            var denied=read();
            // A separately authorized source summary may remain; the protected customer
            // draft and confirmation must not survive denial of an exact basis fact.
            assertFalse(denied.containsKey("confirmation"));
            assertFalse(denied.containsKey("draft"));
            assertTrue(((List<?>)denied.get("history")).isEmpty());
        } catch (R1ServiceReadRuntime.Failure denied) {
            assertEquals(403,denied.status());
        }
    }
}
