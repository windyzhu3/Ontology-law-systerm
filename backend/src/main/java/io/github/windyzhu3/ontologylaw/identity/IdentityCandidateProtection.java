package io.github.windyzhu3.ontologylaw.identity;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Actor;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Online-only encrypted selector. Keys are separate from offline bootstrap, cursors and ETags. */
public final class IdentityCandidateProtection {
    private static final String PURPOSE="R1_IDENTITY_PROVIDER_CANDIDATE_V1";
    public record Verified(String subject,String search,Instant issuedAt,Instant expiresAt) {
        public void requireFresh(Instant now){if(now.isBefore(issuedAt)||!now.isBefore(expiresAt))throw invalid();}
        public String toString(){return "OnlineIdentityCandidate[restricted]";}
    }
    private final String active;private final Map<String,byte[]> keys;
    private final java.security.SecureRandom random=new java.security.SecureRandom();
    public IdentityCandidateProtection(String active,Map<String,byte[]> keys){var copied=new HashMap<String,byte[]>();keys.forEach((id,key)->{if(!id.matches("[A-Za-z0-9_-]{1,64}")||key.length!=32||Arrays.equals(key,new byte[32]))throw invalid();copied.put(id,key.clone());});if(!copied.containsKey(active))throw invalid();this.active=active;this.keys=Map.copyOf(copied);}
    public String issue(Actor actor,String provider,String issuer,String search,String subject,Instant now) {
        try {
            var plain=new ByteArrayOutputStream();try(var out=new DataOutputStream(plain)){out.writeUTF(PURPOSE);out.writeUTF(binding(actor,provider,issuer));out.writeUTF(search);out.writeUTF(subject);out.writeUTF(now.toString());out.writeUTF(now.plusSeconds(300).toString());}
            byte[] nonce=new byte[12];random.nextBytes(nonce);var cipher=cipher(Cipher.ENCRYPT_MODE,active,nonce);
            var encoded=new ByteArrayOutputStream();try(var out=new DataOutputStream(encoded)){out.writeUTF(active);out.write(nonce);out.write(cipher.doFinal(plain.toByteArray()));}
            String result=Base64.getUrlEncoder().withoutPadding().encodeToString(encoded.toByteArray());if(result.length()>2048)throw invalid();return result;
        }catch(java.security.GeneralSecurityException|IOException invalid){throw invalid();}
    }
    public Verified verify(String selector,Actor actor,String provider,String issuer) {
        try {
            if(selector==null||!selector.matches("[A-Za-z0-9_-]{1,2048}"))throw invalid();byte[] bytes=Base64.getUrlDecoder().decode(selector);
            if(!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(selector))throw invalid();
            try(var input=new DataInputStream(new ByteArrayInputStream(bytes))){String id=input.readUTF();byte[] nonce=input.readNBytes(12);if(nonce.length!=12||!keys.containsKey(id))throw invalid();byte[] decrypted=cipher(Cipher.DECRYPT_MODE,id,nonce).doFinal(input.readAllBytes());
                try(var plain=new DataInputStream(new ByteArrayInputStream(decrypted))){if(!PURPOSE.equals(plain.readUTF())||!binding(actor,provider,issuer).equals(plain.readUTF()))throw invalid();String search=plain.readUTF(),subject=plain.readUTF();var issued=Instant.parse(plain.readUTF());var expires=Instant.parse(plain.readUTF());if(plain.available()!=0||!issued.plusSeconds(300).equals(expires))throw invalid();return new Verified(subject,search,issued,expires);}}
        }catch(java.security.GeneralSecurityException|IOException|RuntimeException invalid){throw invalid();}
    }
    private Cipher cipher(int mode,String id,byte[] nonce)throws java.security.GeneralSecurityException{var c=Cipher.getInstance("AES/GCM/NoPadding");c.init(mode,new SecretKeySpec(keys.get(id),"AES"),new GCMParameterSpec(128,nonce));c.updateAAD((PURPOSE+":"+id).getBytes(StandardCharsets.UTF_8));return c;}
    private static String binding(Actor a,String provider,String issuer){if(a.onBehalfAppointmentId()!=null||a.principalKind()!=AuthorizationService.PrincipalKind.HUMAN)throw invalid();return a.tenantId()+"\n"+a.principalId()+"\n"+a.appointmentId()+"\n"+provider+"\n"+issuer;}
    private static IdentityCommands.Failure invalid(){return new IdentityCommands.Failure("VALIDATION_FAILED");}
}
