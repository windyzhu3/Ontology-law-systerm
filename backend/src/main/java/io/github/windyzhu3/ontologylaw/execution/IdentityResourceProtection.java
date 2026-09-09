package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.identity.IdentityAdminReader.Position;
import io.github.windyzhu3.ontologylaw.identity.IdentityCommands.Failure;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.security.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Deployment-owned separate ETag and encrypted-cursor keys. */
public final class IdentityResourceProtection {
    private final byte[] tags,cursors;
    public IdentityResourceProtection(byte[] tags,byte[] cursors){if(tags.length!=32||cursors.length!=32||Arrays.equals(tags,cursors)||Arrays.equals(tags,new byte[32])||Arrays.equals(cursors,new byte[32]))throw new IllegalArgumentException("Identity keys unavailable");this.tags=tags.clone();this.cursors=cursors.clone();}
    public String tag(Actor actor,Subject fact,byte[] authorizationDigest) {
        String value=CanonicalJson.encode(Map.of("profile","R1_IDENTITY_ETAG_V1","actor",actor(actor),"type",fact.type(),"id",fact.id().toString(),"revision",fact.revision(),"authorization",Base64.getUrlEncoder().withoutPadding().encodeToString(authorizationDigest)));
        try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(tags,"HmacSHA256"));return "\"identity."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)))+"\"";}catch(GeneralSecurityException impossible){throw new IllegalStateException(impossible);}
    }
    public String cursor(String binding,Position position) {
        try{var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){out.writeUTF(binding);out.writeUTF(position.createdAt().toString());out.writeUTF(position.id().toString());}
            byte[] nonce=new byte[12];new SecureRandom().nextBytes(nonce);var result=new ByteArrayOutputStream();result.write(nonce);result.write(cipher(Cipher.ENCRYPT_MODE,nonce).doFinal(bytes.toByteArray()));
            String cursor=Base64.getUrlEncoder().withoutPadding().encodeToString(result.toByteArray());if(cursor.length()>2048)throw new Failure("SERVICE_UNAVAILABLE");return cursor;
        }catch(IOException|GeneralSecurityException unavailable){throw new Failure("SERVICE_UNAVAILABLE");}
    }
    public Position position(String cursor,String binding) {
        if(cursor==null)return null;
        try{if(!cursor.matches("[A-Za-z0-9_-]{1,2048}"))throw new IllegalArgumentException();byte[] data=Base64.getUrlDecoder().decode(cursor);if(data.length<29||!Base64.getUrlEncoder().withoutPadding().encodeToString(data).equals(cursor))throw new IllegalArgumentException();
            try(var in=new DataInputStream(new ByteArrayInputStream(cipher(Cipher.DECRYPT_MODE,Arrays.copyOf(data,12)).doFinal(Arrays.copyOfRange(data,12,data.length))))){if(!binding.equals(in.readUTF()))throw new IllegalArgumentException();var position=new Position(Instant.parse(in.readUTF()),UUID.fromString(in.readUTF()));if(in.available()!=0)throw new IllegalArgumentException();return position;}
        }catch(IOException|GeneralSecurityException|IllegalArgumentException invalid){throw new Failure("VALIDATION_FAILED");}
    }
    private Cipher cipher(int mode,byte[] nonce)throws GeneralSecurityException{var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(mode,new SecretKeySpec(cursors,"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD("R1_IDENTITY_CURSOR_V1".getBytes(java.nio.charset.StandardCharsets.UTF_8));return cipher;}
    public static Map<String,Object> actor(Actor actor){var tuple=new TreeMap<String,Object>();tuple.put("tenantId",actor.tenantId().toString());tuple.put("principalId",actor.principalId().toString());tuple.put("appointmentId",actor.appointmentId().toString());tuple.put("onBehalfPrincipalId",actor.onBehalfPrincipalId()==null?null:actor.onBehalfPrincipalId().toString());tuple.put("onBehalfAppointmentId",actor.onBehalfAppointmentId()==null?null:actor.onBehalfAppointmentId().toString());return tuple;}
}
