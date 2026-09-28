package io.github.windyzhu3.ontologylaw.payment;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

/** Tenant-keyed transaction deduplication. Account codes and keys come from trusted deployment
 * composition; an HTTP request cannot choose a deduplication namespace or supply its digest. */
public interface PaymentTransactionProtection {
 interface Keys { SecretKey hmac(UUID tenant); }
 byte[] digest(UUID tenant,String configuredAccountCode,String transactionReference);
 static PaymentTransactionProtection hmac(Keys keys){
  Objects.requireNonNull(keys);
  return (tenant,account,reference)->{
   if(tenant==null||account==null||!account.matches("[A-Z][A-Z0-9_]{0,63}"))throw invalid();
   byte[] text=PaymentReceiptInput.normalizeReference(reference).getBytes(StandardCharsets.UTF_8);
   byte[] code=account.getBytes(StandardCharsets.US_ASCII),profile="R2_PAYMENT_TRANSACTION_HMAC_V1".getBytes(StandardCharsets.US_ASCII);
   SecretKey key=keys.hmac(tenant);if(key==null||key.getEncoded()==null||key.getEncoded().length<32)throw invalid();
   try {
    var mac=Mac.getInstance("HmacSHA256");mac.init(key);
    var input=ByteBuffer.allocate(profile.length+16+8+code.length+text.length).put(profile)
      .putLong(tenant.getMostSignificantBits()).putLong(tenant.getLeastSignificantBits())
      .putInt(code.length).put(code).putInt(text.length).put(text);
    return mac.doFinal(input.array());
   }catch(GeneralSecurityException failure){throw invalid();}
  };
 }
 private static IllegalArgumentException invalid(){return new IllegalArgumentException("Trusted receipt transaction protection required");}
}
