package io.github.windyzhu3.ontologylaw.lead;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class LeadProtectionTest {
    static LeadProtection protection() {
        byte[] key=new byte[32];Arrays.fill(key,(byte)0x51);
        return LeadProtection.aesGcm(new LeadProtection.Keys() {
            public SecretKey encryption(UUID tenant) {return new SecretKeySpec(key,"AES");}
            public SecretKey hmac(UUID tenant,LeadProtection.Purpose purpose) {byte[] value=key.clone();value[0]=(byte)purpose.ordinal();return new SecretKeySpec(value,"HmacSHA256");}
        });
    }
    @Test void authenticated_ciphertext_is_randomized_and_bound_to_tenant_and_field() {
        var p=protection();UUID tenant=UUID.randomUUID();
        byte[] encrypted=p.encrypt(tenant,LeadProtection.Field.CAPTURED_PHONE,"+12025550123");
        assertEquals("+12025550123",p.decrypt(tenant,LeadProtection.Field.CAPTURED_PHONE,encrypted));
        assertFalse(Arrays.equals(encrypted,p.encrypt(tenant,LeadProtection.Field.CAPTURED_PHONE,"+12025550123")));
        assertThrows(IllegalArgumentException.class,()->p.decrypt(UUID.randomUUID(),LeadProtection.Field.CAPTURED_PHONE,encrypted));
        assertThrows(IllegalArgumentException.class,()->p.decrypt(tenant,LeadProtection.Field.CAPTURED_EMAIL,encrypted));
        encrypted[encrypted.length-1]^=1;
        assertThrows(IllegalArgumentException.class,()->p.decrypt(tenant,LeadProtection.Field.CAPTURED_PHONE,encrypted));
    }
    @Test void blind_index_uses_exact_frozen_jcs_and_preserves_source_record_key() throws Exception {
        var p=protection();UUID tenant=UUID.randomUUID();byte[] key=new byte[32];Arrays.fill(key,(byte)0x51);key[0]=0;
        var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
        assertArrayEquals(mac.doFinal("{\"profile\":\"R1_HMAC_SHA256_V1\",\"purpose\":\"LEAD_PHONE_EXACT\",\"sourceAccountCode\":null,\"value\":\"+12025550123\"}".getBytes(StandardCharsets.UTF_8)),p.hmac(tenant,LeadProtection.Purpose.LEAD_PHONE_EXACT,null,"+12025550123"));
        assertFalse(Arrays.equals(p.hmac(tenant,LeadProtection.Purpose.SOURCE_RECORD_KEY,"FIXTURE"," A "),p.hmac(tenant,LeadProtection.Purpose.SOURCE_RECORD_KEY,"FIXTURE","A")));
        assertThrows(IllegalArgumentException.class,()->p.hmac(tenant,LeadProtection.Purpose.LEAD_PHONE_EXACT,"FIXTURE","+12025550123"));
    }
}
