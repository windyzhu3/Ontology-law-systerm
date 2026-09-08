package io.github.windyzhu3.ontologylaw.identity;

import java.util.*;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
/** Dedicated credential subject keys, independent of all business-data keys. */
public final class ExternalSubjectProtection {
    @FunctionalInterface public interface Keys { byte[] forTenant(UUID tenant); }
    private final Keys keys;
    public ExternalSubjectProtection(Keys keys) {this.keys=Objects.requireNonNull(keys);}
    public byte[] digest(UUID tenant,String verifiedSubject) {
        if(tenant==null||verifiedSubject==null||verifiedSubject.isEmpty())throw new IllegalArgumentException("Credential subject unavailable");
        for(int i=0;i<verifiedSubject.length();i++)if(Character.isSurrogate(verifiedSubject.charAt(i))) {
            if(!Character.isHighSurrogate(verifiedSubject.charAt(i))||i+1>=verifiedSubject.length()||!Character.isLowSurrogate(verifiedSubject.charAt(++i)))throw new IllegalArgumentException("Credential subject unavailable");
        }
        byte[] supplied=keys.forTenant(tenant);if(supplied==null||supplied.length<32)throw new IllegalStateException("Credential protection unavailable");
        byte[] key=supplied.clone();
        try {var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(verifiedSubject.getBytes(StandardCharsets.UTF_8));}
        catch(java.security.GeneralSecurityException unavailable){throw new IllegalStateException("Credential protection unavailable");}
        finally {Arrays.fill(key,(byte)0);}
    }
}
