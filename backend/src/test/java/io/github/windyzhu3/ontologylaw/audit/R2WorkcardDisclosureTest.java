package io.github.windyzhu3.ontologylaw.audit;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationSnapshot;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class R2WorkcardDisclosureTest {
    @Test void signature_verifier_uses_registered_direct_workcard_disclosure_without_widening_authority(){
        for(String type:java.util.List.of("opportunity.opportunity","opportunity.responsibility_handoff")){
            var source=new Subject(type,UUID.randomUUID(),0L,null);
            assertEquals("R2_CURRENT_WORKCARD_DISCLOSURE_AUDIT_V1",entry(source,source,"CONTRACT_SIGNATURE_VERIFY","OPPORTUNITY_OWNER",Path.DIRECT).schemaCode());
            assertThrows(IllegalArgumentException.class,()->entry(source,source,"CONTRACT_SIGNATURE_VERIFY","OPPORTUNITY_OWNER",Path.DELEGATED));
            assertThrows(IllegalArgumentException.class,()->entry(source,source,"CONTRACT_SIGNATURE_VERIFY","CONTACT_OWNER",Path.DIRECT));
            assertThrows(IllegalArgumentException.class,()->entry(source,new Subject(type,UUID.randomUUID(),0L,null),"CONTRACT_SIGNATURE_VERIFY","OPPORTUNITY_OWNER",Path.DIRECT));
        }
    }
    private AuthorizationSnapshot authorization(Subject anchor,String code,String slot,Path path) {
        var actor=new Actor(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,null);
        var request=new Request(actor,anchor,UUID.randomUUID(),new Requirement(code,slot,path,UUID.randomUUID()));
        return new AuthorizationSnapshot(request,Instant.now(),true,null,null,"fixture",new byte[32]);
    }
    private AuditAppender.ReadDisclosureEntry entry(Subject source,Subject anchor,String code,String slot,Path path) {
        return new AuditAppender.ReadDisclosureEntry(UUID.randomUUID(),UUID.randomUUID(),source,anchor,authorization(anchor,code,slot,path),AuditAppender.ResponseMode.BODY);
    }
    @Test void opportunity_requires_its_exact_direct_authority_and_source() {
        var opportunity=new Subject("opportunity.opportunity",UUID.randomUUID(),1L,null);
        var record=entry(opportunity,opportunity,"SALES_OPPORTUNITY_OWNER","OPPORTUNITY_OWNER",Path.DIRECT);
        assertEquals("R2_CURRENT_WORKCARD_DISCLOSURE_AUDIT_V1",record.schemaCode());
        assertTrue(record.summary().contains("R2_CURRENT_WORKCARD_DISCLOSURE_V1"));
        assertThrows(IllegalArgumentException.class,()->entry(opportunity,opportunity,"SALES_CONTACT_OWNER","CONTACT_OWNER",Path.DIRECT));
        assertThrows(IllegalArgumentException.class,()->entry(opportunity,opportunity,"SALES_OPPORTUNITY_OWNER","OPPORTUNITY_OWNER",Path.DELEGATED));
        assertThrows(IllegalArgumentException.class,()->entry(opportunity,new Subject(opportunity.type(),opportunity.id(),2L,null),"SALES_OPPORTUNITY_OWNER","OPPORTUNITY_OWNER",Path.DIRECT));
    }
    @Test void handoff_requires_exact_direct_r2_authority_and_source() {
        var handoff=new Subject("opportunity.responsibility_handoff",UUID.randomUUID(),0L,null);
        assertEquals("R2_CURRENT_WORKCARD_DISCLOSURE_AUDIT_V1",entry(handoff,handoff,"SALES_OPPORTUNITY_OWNER","OPPORTUNITY_OWNER",Path.DIRECT).schemaCode());
        assertThrows(IllegalArgumentException.class,()->entry(handoff,handoff,"SALES_CONTACT_OWNER","CONTACT_OWNER",Path.DIRECT));
        assertThrows(IllegalArgumentException.class,()->entry(handoff,handoff,"SALES_OPPORTUNITY_OWNER","OPPORTUNITY_OWNER",Path.DELEGATED));
        assertThrows(IllegalArgumentException.class,()->entry(handoff,new Subject(handoff.type(),UUID.randomUUID(),0L,null),"SALES_OPPORTUNITY_OWNER","OPPORTUNITY_OWNER",Path.DIRECT));
        assertThrows(IllegalArgumentException.class,()->entry(handoff,new Subject("opportunity.opportunity",handoff.id(),0L,null),"SALES_OPPORTUNITY_OWNER","OPPORTUNITY_OWNER",Path.DIRECT));
    }
    @Test void r2_draft_must_be_self_authorized_while_r1_keeps_its_task_anchor() {
        var draft=new Subject("responsibility.action_draft",UUID.randomUUID(),0L,null);
        var task=new Subject("responsibility.task_occurrence",UUID.randomUUID(),0L,null);
        assertDoesNotThrow(()->entry(draft,draft,"SALES_OPPORTUNITY_OWNER","OPPORTUNITY_OWNER",Path.DIRECT));
        assertThrows(IllegalArgumentException.class,()->entry(draft,task,"SALES_OPPORTUNITY_OWNER","OPPORTUNITY_OWNER",Path.DIRECT));
        var legacy=entry(draft,task,"SALES_CONTACT_OWNER","CONTACT_OWNER",Path.DIRECT);
        assertEquals("R1_CURRENT_WORKCARD_DISCLOSURE_AUDIT_V1",legacy.schemaCode());
        assertTrue(legacy.summary().contains("R1_CURRENT_WORKCARD_DISCLOSURE_V1"));
        assertThrows(IllegalArgumentException.class,()->entry(draft,draft,"SALES_CONTACT_OWNER","CONTACT_OWNER",Path.DIRECT));
    }
    @Test void named_quote_workcards_retain_exact_direct_disclosure_guards() {
        var opportunity=new Subject("opportunity.opportunity",UUID.randomUUID(),1L,null);
        for(var code:java.util.List.of("QUOTE_PREPARE","QUOTE_APPROVE","QUOTE_DELIVER","QUOTE_RESPONSE")) {
            assertEquals("R2_CURRENT_WORKCARD_DISCLOSURE_AUDIT_V1",entry(opportunity,opportunity,code,"OPPORTUNITY_OWNER",Path.DIRECT).schemaCode());
            assertThrows(IllegalArgumentException.class,()->entry(opportunity,opportunity,code,"OPPORTUNITY_OWNER",Path.DELEGATED));
            assertThrows(IllegalArgumentException.class,()->entry(opportunity,opportunity,code,"CONTACT_OWNER",Path.DIRECT));
            assertThrows(IllegalArgumentException.class,()->entry(opportunity,new Subject(opportunity.type(),UUID.randomUUID(),1L,null),code,"OPPORTUNITY_OWNER",Path.DIRECT));
        }
        assertThrows(IllegalArgumentException.class,()->entry(opportunity,opportunity,"QUOTE_SELF_AUTHORIZE","OPPORTUNITY_OWNER",Path.DIRECT));
    }
}
