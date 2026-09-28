package io.github.windyzhu3.ontologylaw.opportunity;
import org.junit.jupiter.api.Test;
import javax.crypto.spec.SecretKeySpec;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class QuoteProtectionTest {
 @Test void quote_body_binds_tenant_opportunity_fact_and_purpose_and_rejects_tamper(){
  var p=OpportunityProgressProtection.aesGcm(t->new SecretKeySpec(new byte[32],"AES"));
  UUID t=UUID.randomUUID(),o=UUID.randomUUID(),f=UUID.randomUUID();var encrypted=p.encryptQuote(t,o,f,true,"保密报价");
  assertEquals("保密报价",p.decryptQuote(t,o,f,true,encrypted));
  assertThrows(IllegalArgumentException.class,()->p.decryptQuote(UUID.randomUUID(),o,f,true,encrypted));
  assertThrows(IllegalArgumentException.class,()->p.decryptQuote(t,UUID.randomUUID(),f,true,encrypted));
  assertThrows(IllegalArgumentException.class,()->p.decryptQuote(t,o,UUID.randomUUID(),true,encrypted));
  assertThrows(IllegalArgumentException.class,()->p.decryptQuote(t,o,f,false,encrypted));
  assertThrows(IllegalArgumentException.class,()->p.decryptCustomerRequirements(t,o,f,true,encrypted));
  encrypted[encrypted.length-1]^=1;assertThrows(IllegalArgumentException.class,()->p.decryptQuote(t,o,f,true,encrypted));
 }
}
