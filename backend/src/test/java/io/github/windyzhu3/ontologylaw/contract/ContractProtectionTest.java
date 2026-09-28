package io.github.windyzhu3.ontologylaw.contract;

import org.junit.jupiter.api.Test;
import javax.crypto.spec.SecretKeySpec;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ContractProtectionTest {
    private final UUID tenant=UUID.randomUUID(),opportunity=UUID.randomUUID(),fact=UUID.randomUUID();
    private final ContractProtection protection=ContractProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES"));
    @Test void protected_contract_bodies_roundtrip_without_reusing_nonces() {
        for(var kind:ContractProtection.Kind.values()) {
            var a=protection.seal(tenant,opportunity,fact,kind,"含保密条款的合同正文");
            var b=protection.seal(tenant,opportunity,fact,kind,"含保密条款的合同正文");
            assertEquals("含保密条款的合同正文",protection.open(tenant,opportunity,fact,kind,a));
            assertFalse(Arrays.equals(a,b));
        }
    }
    @Test void binds_ciphertext_to_tenant_opportunity_fact_and_document_purpose() {
        var encrypted=protection.seal(tenant,opportunity,fact,ContractProtection.Kind.DRAFT,"敏感内容");
        assertThrows(IllegalArgumentException.class,()->protection.open(UUID.randomUUID(),opportunity,fact,ContractProtection.Kind.DRAFT,encrypted));
        assertThrows(IllegalArgumentException.class,()->protection.open(tenant,UUID.randomUUID(),fact,ContractProtection.Kind.DRAFT,encrypted));
        assertThrows(IllegalArgumentException.class,()->protection.open(tenant,opportunity,UUID.randomUUID(),ContractProtection.Kind.DRAFT,encrypted));
        assertThrows(IllegalArgumentException.class,()->protection.open(tenant,opportunity,fact,ContractProtection.Kind.DECISION,encrypted));
        encrypted[encrypted.length-1]^=1;
        var error=assertThrows(IllegalArgumentException.class,()->protection.open(tenant,opportunity,fact,ContractProtection.Kind.DRAFT,encrypted));
        assertFalse(error.getMessage().contains("敏感"));assertNull(error.getCause());
    }
    @Test void malformed_or_oversized_bodies_are_rejected() {
        assertThrows(IllegalArgumentException.class,()->protection.open(tenant,opportunity,fact,ContractProtection.Kind.DRAFT,new byte[28]));
        assertThrows(IllegalArgumentException.class,()->protection.seal(tenant,opportunity,fact,ContractProtection.Kind.DRAFT,"中".repeat(50000)));
        assertThrows(IllegalArgumentException.class,()->protection.seal(tenant,opportunity,fact,ContractProtection.Kind.DRAFT,"\ud800"));
        assertThrows(IllegalArgumentException.class,()->protection.seal(tenant,opportunity,fact,ContractProtection.Kind.DRAFT,""));
    }
}
