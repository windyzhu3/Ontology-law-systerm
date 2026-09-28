package io.github.windyzhu3.ontologylaw.contract;

import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Contract Owner protection. Tenant keys come only from trusted deployment composition. */
public interface ContractProtection {
    enum Kind { REQUEST, DECISION, DRAFT, PACKAGE, REVIEW, GENERATION, SIGNATURE, NEGOTIATION, EXECUTION, PAYMENT, TRANSFER }
    interface Keys { SecretKey encryption(UUID tenant); }
    byte[] seal(UUID tenant,UUID opportunity,UUID fact,Kind kind,String clear);
    String open(UUID tenant,UUID opportunity,UUID fact,Kind kind,byte[] encrypted);

    static ContractProtection aesGcm(Keys keys) {
        Objects.requireNonNull(keys);
        return new ContractProtection() {
            private final SecureRandom random=new SecureRandom();
            private byte[] associated(UUID tenant,UUID opportunity,UUID fact,Kind kind) {
                Objects.requireNonNull(tenant);Objects.requireNonNull(opportunity);Objects.requireNonNull(fact);Objects.requireNonNull(kind);
                byte[] profile=("R2_CONTRACT_"+kind.name()+"_AES_GCM_V1").getBytes(StandardCharsets.US_ASCII);
                return ByteBuffer.allocate(profile.length+48).put(profile)
                        .putLong(tenant.getMostSignificantBits()).putLong(tenant.getLeastSignificantBits())
                        .putLong(opportunity.getMostSignificantBits()).putLong(opportunity.getLeastSignificantBits())
                        .putLong(fact.getMostSignificantBits()).putLong(fact.getLeastSignificantBits()).array();
            }
            public byte[] seal(UUID tenant,UUID opportunity,UUID fact,Kind kind,String clear) {
                if(clear==null||clear.isEmpty()||clear.length()>131043)throw invalid();
                try {
                    var encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(CharBuffer.wrap(clear));
                    if(encoded.remaining()>131043)throw invalid();
                    byte[] plaintext=new byte[encoded.remaining()];encoded.get(plaintext);
                    byte[] nonce=new byte[12];random.nextBytes(nonce);
                    var cipher=Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.ENCRYPT_MODE,Objects.requireNonNull(keys.encryption(tenant)),new GCMParameterSpec(128,nonce));
                    cipher.updateAAD(associated(tenant,opportunity,fact,kind));
                    byte[] encrypted=cipher.doFinal(plaintext);
                    return ByteBuffer.allocate(13+encrypted.length).put((byte)1).put(nonce).put(encrypted).array();
                } catch(GeneralSecurityException|CharacterCodingException failure) { throw invalid(); }
            }
            public String open(UUID tenant,UUID opportunity,UUID fact,Kind kind,byte[] encrypted) {
                if(encrypted==null||encrypted.length<30||encrypted.length>131072||encrypted[0]!=1)throw invalid();
                try {
                    var cipher=Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.DECRYPT_MODE,Objects.requireNonNull(keys.encryption(tenant)),new GCMParameterSpec(128,Arrays.copyOfRange(encrypted,1,13)));
                    cipher.updateAAD(associated(tenant,opportunity,fact,kind));
                    byte[] clear=cipher.doFinal(Arrays.copyOfRange(encrypted,13,encrypted.length));
                    return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(clear)).toString();
                } catch(GeneralSecurityException|CharacterCodingException failure) { throw invalid(); }
            }
            private IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid protected contract body"); }
        };
    }
}
