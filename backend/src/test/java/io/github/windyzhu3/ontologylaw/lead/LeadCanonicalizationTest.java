package io.github.windyzhu3.ontologylaw.lead;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class LeadCanonicalizationTest {
    @Test void normalizes_email_case_nfc_whitespace_and_strict_idna2008() {
        assertEquals("té@example.com",LeadCanonicalization.email("\u00A0TE\u0301@EXAMPLE.COM\u2003"));
        assertEquals("a@xn--fa-hia.de",LeadCanonicalization.email("A@faß.de"));
        assertEquals("a@xn--ll-0ea.cat",LeadCanonicalization.email("a@l·l.cat"));
        assertNull(LeadCanonicalization.email("\u00A0 "));
        for(String invalid:new String[]{"a@😀.example","a@☃.example","a@xn--e28h.example","a@xn--n3h.example",
                "a@a_b.example","a@a\u200Cb.example","a@a\u05D0.example","a@a·b.cat","a@ａｂ.example","a@\u0378.example","a@@example.com"})
            assertThrows(IllegalArgumentException.class,()->LeadCanonicalization.email(invalid));
    }
    @Test void phone_and_text_preserve_frozen_profiles() {
        assertEquals("+12025550123",LeadCanonicalization.phone("+12025550123"));
        for(String phone:new String[]{"12025550123"," +12025550123","+0123","+1234567890123456"})
            assertThrows(IllegalArgumentException.class,()->LeadCanonicalization.phone(phone));
        assertEquals("é\nA\nB\tC",LeadCanonicalization.text("\u00A0e\u0301\r\nA\rB\tC\u2003"));
        assertThrows(IllegalArgumentException.class,()->LeadCanonicalization.text("a\u0085b"));
        assertThrows(IllegalArgumentException.class,()->LeadCanonicalization.text("a\u0000b"));
    }
}
