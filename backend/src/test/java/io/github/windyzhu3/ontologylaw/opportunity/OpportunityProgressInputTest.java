package io.github.windyzhu3.ontologylaw.opportunity;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class OpportunityProgressInputTest {
    private final Instant now=Instant.parse("2026-09-14T10:00:00Z");
    private OpportunityProgressInput input(String type,String summary,Instant occurred,Instant next){return new OpportunityProgressInput(type,summary,occurred,next);}
    @Test void effective_progress_requires_real_past_occurrence_and_future_responsibility(){
        for(String type:List.of("PHONE_CONNECTED","CLIENT_VISIT","MEETING","SITE_VISIT","WECHAT_CONNECTED"))
            assertDoesNotThrow(()->input(type,"有效沟通",now.minusSeconds(60),now.plusSeconds(60)).validateAt(now));
        for(String type:List.of("QUOTE_SENT","NOT_CONNECTED","INTERNAL_NOTE","FOLLOW_UP_RECORDED"))
            assertThrows(IllegalArgumentException.class,()->input(type,"不能计为有效进展",now,now.plusSeconds(60)));
        assertThrows(IllegalArgumentException.class,()->input("MEETING","摘要",now.plusSeconds(1),now.plusSeconds(60)).validateAt(now));
        assertThrows(IllegalArgumentException.class,()->input("MEETING","摘要",now,now).validateAt(now));
        assertThrows(IllegalArgumentException.class,()->input("MEETING","摘要",now.plusNanos(1),now.plusSeconds(60)));
    }
    @Test void summary_normalizes_and_rejects_unsafe_or_empty_content(){
        assertEquals("Café\n结果",input("MEETING","  Cafe\u0301\r\n结果  ",now,now.plusSeconds(60)).summary());
        for(String invalid:List.of(" ","\u0000","\ud800","a".repeat(2001)))
            assertThrows(IllegalArgumentException.class,()->input("MEETING",invalid,now,now.plusSeconds(60)));
        assertFalse(input("MEETING","保密内容",now,now.plusSeconds(60)).toString().contains("保密内容"));
    }
    @Test void protected_body_binds_tenant_opportunity_and_progress_identity_and_detects_tampering(){
        var protection=OpportunityProgressProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES"));
        var tenant=UUID.randomUUID();var opportunity=UUID.randomUUID();var progress=UUID.randomUUID();
        String body=io.github.windyzhu3.ontologylaw.execution.CanonicalJson.encode(input("MEETING","保密内容",now,now.plusSeconds(60)).bodyValues(tenant,opportunity,0L,progress,UUID.randomUUID(),0L,UUID.randomUUID(),now));
        var encrypted=protection.encrypt(tenant,opportunity,progress,body);
        assertEquals(body,protection.decrypt(tenant,opportunity,progress,encrypted));
        assertFalse(Arrays.equals(encrypted,protection.encrypt(tenant,opportunity,progress,body)));
        assertThrows(IllegalArgumentException.class,()->protection.decrypt(UUID.randomUUID(),opportunity,progress,encrypted));
        assertThrows(IllegalArgumentException.class,()->protection.decrypt(tenant,UUID.randomUUID(),progress,encrypted));
        assertThrows(IllegalArgumentException.class,()->protection.decrypt(tenant,opportunity,UUID.randomUUID(),encrypted));
        encrypted[encrypted.length-1]^=1;
        assertThrows(IllegalArgumentException.class,()->protection.decrypt(tenant,opportunity,progress,encrypted));
    }
}
