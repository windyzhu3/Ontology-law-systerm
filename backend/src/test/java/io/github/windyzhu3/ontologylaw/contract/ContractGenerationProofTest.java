package io.github.windyzhu3.ontologylaw.contract;

import java.time.Instant;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContractGenerationProofTest {
    final ContractProtection protection=ContractProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES"));
    final UUID tenant=UUID.randomUUID(),opportunity=UUID.randomUUID(),actor=UUID.randomUUID();
    final Instant now=Instant.parse("2026-09-23T00:00:00Z");
    @Test void generated_proof_binds_actor_tenant_opportunity_inputs_and_pdf_until_exact_expiry(){
        var proofs=new ContractGenerationProof(protection);var token=proofs.issue(tenant,opportunity,actor,"ab".repeat(32),"cd".repeat(32),now.plusSeconds(3600));
        assertDoesNotThrow(()->proofs.verify(token,tenant,opportunity,actor,"ab".repeat(32),"cd".repeat(32),now));
        assertThrows(IllegalArgumentException.class,()->proofs.verify(token,UUID.randomUUID(),opportunity,actor,"ab".repeat(32),"cd".repeat(32),now));
        assertThrows(IllegalArgumentException.class,()->proofs.verify(token,tenant,UUID.randomUUID(),actor,"ab".repeat(32),"cd".repeat(32),now));
        assertThrows(IllegalArgumentException.class,()->proofs.verify(token,tenant,opportunity,UUID.randomUUID(),"ab".repeat(32),"cd".repeat(32),now));
        assertThrows(IllegalArgumentException.class,()->proofs.verify(token,tenant,opportunity,actor,"ef".repeat(32),"cd".repeat(32),now));
        assertThrows(IllegalArgumentException.class,()->proofs.verify(token,tenant,opportunity,actor,"ab".repeat(32),"ef".repeat(32),now));
        assertThrows(IllegalArgumentException.class,()->proofs.verify(token,tenant,opportunity,actor,"ab".repeat(32),"cd".repeat(32),now.plusSeconds(3600)));
    }
    @Test void forged_or_truncated_proof_is_rejected(){var proofs=new ContractGenerationProof(protection);assertThrows(IllegalArgumentException.class,()->proofs.verify("forged",tenant,opportunity,actor,"ab".repeat(32),"cd".repeat(32),now));}
}
