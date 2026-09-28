package io.github.windyzhu3.ontologylaw.payment;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PaymentTransactionProtectionTest {
 private final UUID tenant=UUID.randomUUID();
 private PaymentTransactionProtection protection(){return PaymentTransactionProtection.hmac(t->new SecretKeySpec(new byte[32],"HmacSHA256"));}
 @Test void original_transaction_is_stably_deduplicated_with_a_keyed_digest(){var p=protection();byte[] a=p.digest(tenant,"SYNTHETIC_BANK_A"," TX-001 ");assertEquals(32,a.length);assertArrayEquals(a,p.digest(tenant,"SYNTHETIC_BANK_A","TX-001"));a[0]^=1;assertFalse(Arrays.equals(a,p.digest(tenant,"SYNTHETIC_BANK_A","TX-001")));}
 @Test void tenant_account_and_exact_reference_are_separate_deduplication_boundaries(){var p=protection();byte[] base=p.digest(tenant,"SYNTHETIC_BANK_A","TX-001");assertFalse(Arrays.equals(base,p.digest(UUID.randomUUID(),"SYNTHETIC_BANK_A","TX-001")));assertFalse(Arrays.equals(base,p.digest(tenant,"SYNTHETIC_BANK_B","TX-001")));assertFalse(Arrays.equals(base,p.digest(tenant,"SYNTHETIC_BANK_A","TX-002")));}
 @Test void missing_key_or_invalid_account_fails_without_including_the_transaction(){var p=PaymentTransactionProtection.hmac(t->null);var ex=assertThrows(IllegalArgumentException.class,()->p.digest(tenant,"SYNTHETIC_BANK_A","PRIVATE-TX"));assertFalse(ex.getMessage().contains("PRIVATE-TX"));assertThrows(IllegalArgumentException.class,()->protection().digest(tenant,"untrusted account text","TX-001"));}
 @Test void different_trusted_keys_do_not_share_a_transaction_digest(){byte[] another=new byte[32];Arrays.fill(another,(byte)7);var q=PaymentTransactionProtection.hmac(t->new SecretKeySpec(another,"HmacSHA256"));assertFalse(Arrays.equals(protection().digest(tenant,"SYNTHETIC_BANK_A","TX-001"),q.digest(tenant,"SYNTHETIC_BANK_A","TX-001")));}
}
