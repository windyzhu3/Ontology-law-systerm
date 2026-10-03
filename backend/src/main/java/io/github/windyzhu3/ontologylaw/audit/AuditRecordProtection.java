package io.github.windyzhu3.ontologylaw.audit;

import io.github.windyzhu3.ontologylaw.audit.AuditRecordReader.Position;
import io.github.windyzhu3.ontologylaw.identity.IdentityCommands.Failure;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.security.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.nio.charset.StandardCharsets;

/** Encrypted, authenticated keyset with a distinct protocol and derived deployment key. */
public final class AuditRecordProtection {
 private final byte[] key;
 public AuditRecordProtection(byte[] material){if(material==null||material.length!=32||Arrays.equals(material,new byte[32]))throw new IllegalArgumentException("Audit cursor key unavailable");try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(material,"HmacSHA256"));key=mac.doFinal("ADM07_AUDIT_CURSOR_KEY_V1".getBytes(StandardCharsets.UTF_8));}catch(GeneralSecurityException e){throw new IllegalStateException(e);}}
 public String cursor(String binding,Position position){
  try{var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){out.writeUTF(binding);out.writeUTF(position.trustedAt().toString());out.writeUTF(position.id().toString());out.writeUTF(position.watermark().toString());}byte[] nonce=new byte[12];new SecureRandom().nextBytes(nonce);var result=new ByteArrayOutputStream();result.write(nonce);result.write(cipher(Cipher.ENCRYPT_MODE,nonce).doFinal(bytes.toByteArray()));return Base64.getUrlEncoder().withoutPadding().encodeToString(result.toByteArray());}catch(IOException|GeneralSecurityException e){throw new Failure("SERVICE_UNAVAILABLE");}
 }
 public Position position(String cursor,String binding){
  if(cursor==null)return null;
  try{if(!cursor.matches("[A-Za-z0-9_-]{1,2048}"))throw new IllegalArgumentException();byte[] bytes=Base64.getUrlDecoder().decode(cursor);if(bytes.length<29||!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(cursor))throw new IllegalArgumentException();try(var in=new DataInputStream(new ByteArrayInputStream(cipher(Cipher.DECRYPT_MODE,Arrays.copyOf(bytes,12)).doFinal(Arrays.copyOfRange(bytes,12,bytes.length))))){if(!binding.equals(in.readUTF()))throw new IllegalArgumentException();var position=new Position(Instant.parse(in.readUTF()),UUID.fromString(in.readUTF()),Instant.parse(in.readUTF()));if(in.available()!=0)throw new IllegalArgumentException();return position;}}catch(IOException|GeneralSecurityException|IllegalArgumentException invalid){throw new Failure("VALIDATION_FAILED");}
 }
 private Cipher cipher(int mode,byte[] nonce)throws GeneralSecurityException{var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD("ADM07_AUDIT_CURSOR_V1".getBytes(StandardCharsets.UTF_8));return cipher;}
}
