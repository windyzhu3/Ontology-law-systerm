package io.github.windyzhu3.ontologylaw.lead;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;

class LeadManagementReaderIT extends LeadBusinessFixture {
 @Test void bounded_metadata_never_opens_text_and_summary_requires_exact_tenant_and_revision()throws Exception {
  setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL,true);
  var first=run(capture("management-a",true)).resultFact();
  var second=run(capture("management-b",false)).resultFact();
  var raw=LeadManagementReader.databaseBacked(new LeadProtection(){
   public byte[] encrypt(UUID t,Field f,String value){throw new AssertionError();}
   public String decrypt(UUID t,Field f,byte[] value){throw new AssertionError("Metadata decrypted a protected field");}
   public byte[] hmac(UUID t,Purpose p,String a,String v){throw new AssertionError();}
  });
  var reader=LeadManagementReader.databaseBacked(LeadProtectionTest.protection());
  try(var c=database.apiConnection()){
   assertThrows(java.sql.SQLException.class,()->raw.scan(c,seed.tenant(),null,20));
   inTransaction(c,Capability.QUERY,x->{
    var all=raw.scan(x,seed.tenant(),null,100);assertEquals(2,all.size());
    var one=raw.scan(x,seed.tenant(),null,1);assertEquals(List.of(all.getFirst()),one);
    assertEquals(List.of(all.getLast()),raw.scan(x,seed.tenant(),one.getFirst().selector().id(),1));
    assertTrue(raw.scan(x,UUID.randomUUID(),null,20).isEmpty());
    assertEquals("FIXTURE",raw.metadata(x,seed.tenant(),first.id()).sourceAccount());
    assertNull(raw.metadata(x,UUID.randomUUID(),first.id()));
    assertNull(raw.latestContact(x,seed.tenant(),first.id()));
    assertThrows(IllegalArgumentException.class,()->raw.scan(x,seed.tenant(),null,101));
    assertThrows(IllegalArgumentException.class,()->raw.scan(x,seed.tenant(),null,0));
    var text=reader.summary(x,seed.tenant(),first);assertEquals("Synthetic contact",text.capturedName());assertEquals("+12025550123",text.phone());
    assertNull(reader.summary(x,seed.tenant(),second).phone());
    assertThrows(java.sql.SQLException.class,()->reader.summary(x,UUID.randomUUID(),first));
    assertThrows(java.sql.SQLException.class,()->reader.summary(x,seed.tenant(),new Subject(first.type(),first.id(),first.revision()+1,null)));
    assertThrows(java.sql.SQLException.class,()->reader.summary(x,seed.tenant(),new Subject("party.party",first.id(),first.revision(),null)));
    return null;
   });
  }
 }
}
