package io.github.windyzhu3.ontologylaw.identity;

import java.time.*;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Separate offline-only cryptographic purpose; no browser Actor or stored candidate. */
public final class BootstrapCandidateProtection {
    public record Binding(String operatorAssertion,String tenantCode,String provider,String issuer) {
        public Binding {if(operatorAssertion==null||operatorAssertion.isBlank()||operatorAssertion.codePointCount(0,operatorAssertion.length())>200||operatorAssertion.codePoints().anyMatch(Character::isISOControl)||tenantCode==null||!tenantCode.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}")||provider==null||!provider.matches("[A-Z][A-Z0-9_]{0,63}")||issuer==null||issuer.length()>2048)throw invalid();operatorAssertion=operatorAssertion.strip();}
    }
    public record Verified(Binding binding,String subject,Instant issuedAt,Instant expiresAt) {
        public String toString(){return "BootstrapCandidate[restricted]";}
        public void requireFresh(Instant now){if(now.isBefore(issuedAt)||!now.isBefore(expiresAt))throw new IllegalArgumentException("BOOTSTRAP_CANDIDATE_INVALID");}
    }
    private static final String PURPOSE="R1_IDENTITY_BOOTSTRAP_CANDIDATE_V1";
    private final String activeKeyId;private final Map<String,byte[]> keys;private final java.security.SecureRandom random=new java.security.SecureRandom();
    public BootstrapCandidateProtection(String activeKeyId,Map<String,byte[]> retainedKeys){
        var copy=new HashMap<String,byte[]>();Objects.requireNonNull(retainedKeys).forEach((id,key)->{if(id==null||!id.matches("[A-Za-z0-9_-]{1,64}")||key==null||key.length!=32||Arrays.equals(key,new byte[32]))throw invalid();copy.put(id,key.clone());});
        if(!copy.containsKey(activeKeyId))throw invalid();this.activeKeyId=activeKeyId;keys=Map.copyOf(copy);
    }
    public String issue(Binding binding,String exactAccount,IdentityProviderDirectory directory,Instant now){
        if(!binding.issuer().equals(Objects.requireNonNull(directory).issuer()))throw invalid();
        var account=directory.exact(exactAccount);Objects.requireNonNull(now);
        try {
            var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){out.writeUTF(PURPOSE);out.writeUTF(binding.operatorAssertion());out.writeUTF(binding.tenantCode());out.writeUTF(binding.provider());out.writeUTF(binding.issuer());out.writeUTF(account.subject());out.writeUTF(now.toString());out.writeUTF(now.plusSeconds(300).toString());}
            byte[] nonce=new byte[12];random.nextBytes(nonce);var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(keys.get(activeKeyId),"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(aad(activeKeyId));
            return "ibc1."+activeKeyId+"."+encode(nonce)+"."+encode(cipher.doFinal(bytes.toByteArray()));
        }catch(java.security.GeneralSecurityException|IOException failure){throw invalid();}
    }
    public Verified verify(String envelope,Binding binding){
        try {
            if(envelope==null||envelope.length()>8192)throw invalid();var parts=envelope.split("\\.",-1);
            if(parts.length!=4||!parts[0].equals("ibc1")||!keys.containsKey(parts[1]))throw invalid();byte[] nonce=decode(parts[2]);if(nonce.length!=12)throw invalid();
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(keys.get(parts[1]),"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(aad(parts[1]));
            try(var in=new DataInputStream(new ByteArrayInputStream(cipher.doFinal(decode(parts[3]))))){
                if(!PURPOSE.equals(in.readUTF()))throw invalid();var actual=new Binding(in.readUTF(),in.readUTF(),in.readUTF(),in.readUTF());if(!actual.equals(binding))throw invalid();
                String subject=new IdentityProviderDirectory.Account(in.readUTF()).subject();Instant issued=Instant.parse(in.readUTF()),expires=Instant.parse(in.readUTF());
                if(in.read()!=-1||!expires.isAfter(issued)||expires.isAfter(issued.plusSeconds(300)))throw invalid();return new Verified(actual,subject,issued,expires);
            }
        }catch(java.security.GeneralSecurityException|IOException|RuntimeException failure){throw invalid();}
    }
    private static String encode(byte[] value){return Base64.getUrlEncoder().withoutPadding().encodeToString(value);}
    private static byte[] decode(String value){byte[] bytes=Base64.getUrlDecoder().decode(value);if(!encode(bytes).equals(value))throw invalid();return bytes;}
    private static byte[] aad(String keyId){return (PURPOSE+"\n"+keyId).getBytes(StandardCharsets.UTF_8);}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("BOOTSTRAP_CANDIDATE_INVALID");}
}
