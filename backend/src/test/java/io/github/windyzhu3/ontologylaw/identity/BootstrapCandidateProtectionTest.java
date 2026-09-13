package io.github.windyzhu3.ontologylaw.identity;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Crypto unit proof only; real directory provenance is covered separately by KeycloakDirectoryIT. */
class BootstrapCandidateProtectionTest {
    @Test void candidate_is_authenticated_purpose_bound_short_lived_and_recoverable_only_with_retained_key() {
        byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);
        var protection=new BootstrapCandidateProtection("offline-v1",Map.of("offline-v1",key));
        var binding=new BootstrapCandidateProtection.Binding("approved synthetic operator","TENANT","PROVIDER","https://idp.example.invalid/realms/business");
        var now=Instant.parse("2026-09-08T00:00:00Z");
        IdentityProviderDirectory directory=new IdentityProviderDirectory(){public String issuer(){return binding.issuer();}public Account exact(String identifier){if(!"exact-account".equals(identifier))throw new IllegalArgumentException();return new Account("synthetic-private-subject");}public Account enabled(String subject){return new Account(subject);}};
        String envelope=assertDoesNotThrow(()->protection.issue(binding,"exact-account",directory,now));
        assertFalse(envelope.contains("synthetic-private-subject"));assertFalse(envelope.contains(binding.operatorAssertion()));
        assertFalse(envelope.equals(protection.issue(binding,"exact-account",directory,now)),"Each issuance requires a fresh nonce (envelopes restricted)");
        var verified=protection.verify(envelope,binding);assertTrue("synthetic-private-subject".equals(verified.subject()),"Verified subject must match (values restricted)");verified.requireFresh(now.plusSeconds(299));assertThrows(IllegalArgumentException.class,()->verified.requireFresh(now.plusSeconds(300)));
        assertEquals(verified,new BootstrapCandidateProtection("offline-v1",Map.of("offline-v1",key)).verify(envelope,binding),"Integrity verification itself must remain possible after expiry for exact original-key recovery");
        assertThrows(IllegalArgumentException.class,()->protection.verify(envelope,new BootstrapCandidateProtection.Binding("another operator",binding.tenantCode(),binding.provider(),binding.issuer())));
        assertThrows(IllegalArgumentException.class,()->protection.verify("online."+envelope,binding));
        char end=envelope.charAt(envelope.length()-2);String changed=envelope.substring(0,envelope.length()-2)+(end=='A'?'B':'A')+envelope.substring(envelope.length()-1);
        assertThrows(IllegalArgumentException.class,()->protection.verify(changed,binding));
        byte[] other=key.clone();other[0]^=1;assertThrows(IllegalArgumentException.class,()->new BootstrapCandidateProtection("offline-v1",Map.of("offline-v1",other)).verify(envelope,binding));
    }
}
