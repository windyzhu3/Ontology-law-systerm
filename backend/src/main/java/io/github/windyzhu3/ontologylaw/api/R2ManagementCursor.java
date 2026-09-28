package io.github.windyzhu3.ontologylaw.api;
import java.util.*;import java.time.Instant;import java.nio.charset.StandardCharsets;import java.security.*;
import javax.crypto.Cipher;import javax.crypto.spec.*;
/** Opaque authenticated position bound to exact actor, view, query and page size. */
final class R2ManagementCursor {
 private final byte[] key;
 R2ManagementCursor(byte[] key){if(key==null||key.length<32)throw new IllegalArgumentException("Cursor key required");this.key=Arrays.copyOf(key,32);}
 String encode(UUID after,String scope,Instant now){try{
  byte[] nonce=new byte[12];new SecureRandom().nextBytes(nonce);var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(scope.getBytes(StandardCharsets.UTF_8));
  byte[] body=cipher.doFinal((after+"|"+now.plusSeconds(600).getEpochSecond()).getBytes(StandardCharsets.UTF_8));byte[] output=Arrays.copyOf(nonce,nonce.length+body.length);System.arraycopy(body,0,output,nonce.length,body.length);return Base64.getUrlEncoder().withoutPadding().encodeToString(output);
 }catch(GeneralSecurityException e){throw new IllegalStateException(e);}}
 UUID decode(String text,String scope,Instant now){if(text==null)return null;try{
  if(text.length()>512)throw new IllegalArgumentException();byte[] body=Base64.getUrlDecoder().decode(text);if(body.length<29)throw new IllegalArgumentException();var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Arrays.copyOf(body,12)));cipher.updateAAD(scope.getBytes(StandardCharsets.UTF_8));var fields=new String(cipher.doFinal(Arrays.copyOfRange(body,12,body.length)),StandardCharsets.UTF_8).split("\\|",-1);if(fields.length!=2||now.getEpochSecond()>=Long.parseLong(fields[1]))throw new IllegalArgumentException();return UUID.fromString(fields[0]);
 }catch(GeneralSecurityException|IllegalArgumentException e){throw new io.github.windyzhu3.ontologylaw.execution.R1ServiceReadRuntime.Failure(400,"VALIDATION_FAILED");}}
}
