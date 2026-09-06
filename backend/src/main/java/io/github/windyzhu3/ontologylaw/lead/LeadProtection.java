package io.github.windyzhu3.ontologylaw.lead;

import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.security.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import io.github.windyzhu3.ontologylaw.execution.CanonicalJson;

public interface LeadProtection {
    enum Purpose { LEAD_PHONE_EXACT, LEAD_EMAIL_EXACT, SOURCE_RECORD_KEY }
    enum Field { CAPTURED_NAME, CAPTURED_PHONE, CAPTURED_EMAIL, LEGAL_NEED_SUMMARY, INGRESS_PHONE, INGRESS_EMAIL, INGRESS_SOURCE_SUMMARY }
    interface Keys {SecretKey encryption(UUID tenant);SecretKey hmac(UUID tenant,Purpose purpose);}
    byte[] encrypt(UUID tenant,Field field,String value);
    String decrypt(UUID tenant,Field field,byte[] value);
    byte[] hmac(UUID tenant,Purpose purpose,String sourceAccountCode,String value);
    static LeadProtection aesGcm(Keys keys) {Objects.requireNonNull(keys);return new LeadProtection() {
        private final SecureRandom random=new SecureRandom();
        private byte[] aad(UUID tenant,Field field) {return CanonicalJson.encode(Map.of("profile","R1_LEAD_AES_GCM_V1","tenantId",tenant.toString(),"field",field.name())).getBytes(StandardCharsets.UTF_8);}
        public byte[] encrypt(UUID tenant,Field field,String value){
            if(value==null)return null;
            try {byte[] nonce=new byte[12];random.nextBytes(nonce);var cipher=Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE,Objects.requireNonNull(keys.encryption(tenant)),new GCMParameterSpec(128,nonce));cipher.updateAAD(aad(tenant,field));
                byte[] encrypted=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));return ByteBuffer.allocate(13+encrypted.length).put((byte)1).put(nonce).put(encrypted).array();
            } catch(GeneralSecurityException error){throw new IllegalStateException("Protection unavailable");}
        }
        public String decrypt(UUID tenant,Field field,byte[] value){
            if(value==null)return null;
            if(value.length<29||value[0]!=1)throw new IllegalArgumentException("Invalid protected value");
            try {var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,Objects.requireNonNull(keys.encryption(tenant)),new GCMParameterSpec(128,Arrays.copyOfRange(value,1,13)));
                cipher.updateAAD(aad(tenant,field));return new String(cipher.doFinal(Arrays.copyOfRange(value,13,value.length)),StandardCharsets.UTF_8);
            } catch(GeneralSecurityException error){throw new IllegalArgumentException("Invalid protected value");}
        }
        public byte[] hmac(UUID tenant,Purpose purpose,String account,String value){
            Objects.requireNonNull(value);if((purpose==Purpose.SOURCE_RECORD_KEY)!=(account!=null))throw new IllegalArgumentException("Invalid HMAC purpose scope");
            var input=new TreeMap<String,Object>();input.put("profile","R1_HMAC_SHA256_V1");input.put("purpose",purpose.name());input.put("sourceAccountCode",account);input.put("value",value);
            try {var mac=Mac.getInstance("HmacSHA256");mac.init(Objects.requireNonNull(keys.hmac(tenant,purpose)));return mac.doFinal(CanonicalJson.encode(input).getBytes(StandardCharsets.UTF_8));}
            catch(GeneralSecurityException error){throw new IllegalStateException("Protection unavailable");}
        }
    };}
}
