package io.github.windyzhu3.ontologylaw.opportunity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Owner protection port. Key provisioning remains the trusted deployment's responsibility. */
public interface OpportunityProgressProtection {
    interface Keys { SecretKey encryption(UUID tenant); }
    byte[] encrypt(UUID tenant,UUID opportunity,UUID progress,String body);
    String decrypt(UUID tenant,UUID opportunity,UUID progress,byte[] encrypted);
    default byte[] encryptClosure(UUID tenant,UUID opportunity,UUID closure,String body){throw new IllegalStateException("Closure protection unavailable");}
    default String decryptClosure(UUID tenant,UUID opportunity,UUID closure,byte[] encrypted){throw new IllegalStateException("Closure protection unavailable");}
    default byte[] encryptCustomerRequirements(UUID tenant,UUID opportunity,UUID fact,boolean draft,String body){throw new IllegalStateException("Customer protection unavailable");}
    default String decryptCustomerRequirements(UUID tenant,UUID opportunity,UUID fact,boolean draft,byte[] body){throw new IllegalStateException("Customer protection unavailable");}
    default byte[] encryptMaterial(UUID tenant,UUID opportunity,UUID fact,String body){throw new IllegalStateException("Material protection unavailable");}
    default String decryptMaterial(UUID tenant,UUID opportunity,UUID fact,byte[] body){throw new IllegalStateException("Material protection unavailable");}
    default byte[] encryptQuote(UUID tenant,UUID opportunity,UUID fact,boolean draft,String body){throw new IllegalStateException("Quote protection unavailable");}
    default String decryptQuote(UUID tenant,UUID opportunity,UUID fact,boolean draft,byte[] body){throw new IllegalStateException("Quote protection unavailable");}
    default byte[] encryptAttempt(UUID tenant,UUID opportunity,UUID fact,String body){throw new IllegalStateException("Attempt protection unavailable");}
    default String decryptAttempt(UUID tenant,UUID opportunity,UUID fact,byte[] body){throw new IllegalStateException("Attempt protection unavailable");}
    static OpportunityProgressProtection aesGcm(Keys keys){
        Objects.requireNonNull(keys);
        return new OpportunityProgressProtection(){
            private final SecureRandom random=new SecureRandom();
            private byte[] aad(UUID tenant,UUID opportunity,UUID progress,String code){
                byte[] profile=code.getBytes(StandardCharsets.US_ASCII);
                return ByteBuffer.allocate(profile.length+48).put(profile).putLong(tenant.getMostSignificantBits()).putLong(tenant.getLeastSignificantBits())
                    .putLong(opportunity.getMostSignificantBits()).putLong(opportunity.getLeastSignificantBits())
                    .putLong(progress.getMostSignificantBits()).putLong(progress.getLeastSignificantBits()).array();
            }
            public byte[] encrypt(UUID tenant,UUID opportunity,UUID progress,String body){
                return encrypt(tenant,opportunity,progress,body,"R2_PROGRESS_AES_GCM_V1");
            }
            public byte[] encryptAttempt(UUID tenant,UUID opportunity,UUID fact,String body){return encrypt(tenant,opportunity,fact,body,"R2_ATTEMPT_AES_GCM_V1");}
            public String decryptAttempt(UUID tenant,UUID opportunity,UUID fact,byte[] body){return decrypt(tenant,opportunity,fact,body,"R2_ATTEMPT_AES_GCM_V1");}
            public byte[] encryptClosure(UUID tenant,UUID opportunity,UUID closure,String body){return encrypt(tenant,opportunity,closure,body,"R2_CLOSURE_AES_GCM_V1");}
            public byte[] encryptCustomerRequirements(UUID tenant,UUID opportunity,UUID fact,boolean draft,String body){return encrypt(tenant,opportunity,fact,body,draft?"R2_CUSTOMER_DRAFT_AES_GCM_V1":"R2_CUSTOMER_CONFIRMATION_AES_GCM_V1");}
            public String decryptCustomerRequirements(UUID tenant,UUID opportunity,UUID fact,boolean draft,byte[] body){return decrypt(tenant,opportunity,fact,body,draft?"R2_CUSTOMER_DRAFT_AES_GCM_V1":"R2_CUSTOMER_CONFIRMATION_AES_GCM_V1");}
            public byte[] encryptMaterial(UUID tenant,UUID opportunity,UUID fact,String body){return encrypt(tenant,opportunity,fact,body,"R2_MATERIAL_AES_GCM_V1");}
            public String decryptMaterial(UUID tenant,UUID opportunity,UUID fact,byte[] body){return decrypt(tenant,opportunity,fact,body,"R2_MATERIAL_AES_GCM_V1");}
            public byte[] encryptQuote(UUID tenant,UUID opportunity,UUID fact,boolean draft,String body){return encrypt(tenant,opportunity,fact,body,draft?"R2_QUOTE_DRAFT_AES_GCM_V1":"R2_QUOTE_PACKAGE_AES_GCM_V1");}
            public String decryptQuote(UUID tenant,UUID opportunity,UUID fact,boolean draft,byte[] body){return decrypt(tenant,opportunity,fact,body,draft?"R2_QUOTE_DRAFT_AES_GCM_V1":"R2_QUOTE_PACKAGE_AES_GCM_V1");}
            private byte[] encrypt(UUID tenant,UUID opportunity,UUID progress,String body,String profile){
                Objects.requireNonNull(body);
                try {
                    byte[] nonce=new byte[12];random.nextBytes(nonce);
                    var cipher=Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.ENCRYPT_MODE,Objects.requireNonNull(keys.encryption(tenant)),new GCMParameterSpec(128,nonce));
                    cipher.updateAAD(aad(tenant,opportunity,progress,profile));
                    byte[] ciphertext=cipher.doFinal(body.getBytes(StandardCharsets.UTF_8));
                    return ByteBuffer.allocate(13+ciphertext.length).put((byte)1).put(nonce).put(ciphertext).array();
                }catch(GeneralSecurityException failure){throw new IllegalStateException("Progress protection unavailable");}
            }
            public String decrypt(UUID tenant,UUID opportunity,UUID progress,byte[] encrypted){
                return decrypt(tenant,opportunity,progress,encrypted,"R2_PROGRESS_AES_GCM_V1");
            }
            public String decryptClosure(UUID tenant,UUID opportunity,UUID closure,byte[] encrypted){return decrypt(tenant,opportunity,closure,encrypted,"R2_CLOSURE_AES_GCM_V1");}
            private String decrypt(UUID tenant,UUID opportunity,UUID progress,byte[] encrypted,String profile){
                if(encrypted==null||encrypted.length<29||encrypted[0]!=1)throw new IllegalArgumentException("Invalid protected progress");
                try {
                    var cipher=Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.DECRYPT_MODE,Objects.requireNonNull(keys.encryption(tenant)),new GCMParameterSpec(128,Arrays.copyOfRange(encrypted,1,13)));
                    cipher.updateAAD(aad(tenant,opportunity,progress,profile));
                    return new String(cipher.doFinal(Arrays.copyOfRange(encrypted,13,encrypted.length)),StandardCharsets.UTF_8);
                }catch(GeneralSecurityException failure){throw new IllegalArgumentException("Invalid protected progress");}
            }
        };
    }
}


