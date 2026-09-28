package io.github.windyzhu3.ontologylaw.api;
import io.github.windyzhu3.ontologylaw.testing.PostgresIntegrationTest;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContractDisclosureBatchIT extends PostgresIntegrationTest {
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true}) void batch_preserves_every_audit_field_and_rolls_back_all_chunks_on_failure(boolean rewrite)throws Exception {
  var seed=AuthorizationServiceIT.seed(database,"HUMAN","CONTRACT_READ");var audit=AuditAppender.databaseBacked("BATCH_IT");
  var entries=new ArrayList<AuditAppender.ContractDisclosureEntry>();
  var properties=new java.util.Properties();properties.setProperty("user","law_api_login");properties.setProperty("password",database.apiPassword());properties.setProperty("logServerErrorDetail","false");properties.setProperty("reWriteBatchedInserts",Boolean.toString(rewrite));
  try(var c=java.sql.DriverManager.getConnection(database.jdbcUrl(),properties)) {
   inTransaction(c,Capability.QUERY,tx->{try(var scope=AuthorizationService.databaseBacked().lockedReadScope(tx,seed.tenant())) {
    for(int n=0;n<130;n++) {
     var subject=n%2==0?new Subject("contract.contract",UUID.randomUUID(),0L,null):new Subject("contract.contract_revision",UUID.randomUUID(),null,Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
     var snapshot=R1AuthorityReader.databaseBacked().authorize(tx,seed.request().actor(),subject,seed.org(),"OPPORTUNITY_OWNER","CONTRACT_READ");assertNotNull(snapshot);
     entries.add(new AuditAppender.ContractDisclosureEntry(UUID.randomUUID(),UUID.randomUUID(),subject,snapshot,n%2==0?"HISTORY":"BODY"));
    }
   }return null;});
   var control=entries.getFirst();inTransaction(c,Capability.AUDIT,tx->{audit.append(tx,control);return null;});
   var copy=new AuditAppender.ContractDisclosureEntry(UUID.randomUUID(),control.correlationId(),control.disclosedSource(),control.authorization(),control.mode());entries.set(0,copy);
   var probe=new ReadConnectionProbe(c);
   inTransaction(probe.connection(),Capability.AUDIT,tx->{audit.appendContracts(tx,entries);return null;});
   assertEquals(row(control.id()),row(copy.id()),"Batched and individual audit rows have identical fields except their IDs");
   assertEquals(131,count(seed.tenant()));
   assertTrue(probe.inserts.get()<=2,"130 exact audit entries should take at most two bounded JDBC batches; got "+probe.inserts.get());
   var rejected=new ArrayList<AuditAppender.ContractDisclosureEntry>();
   for(var e:entries)rejected.add(new AuditAppender.ContractDisclosureEntry(UUID.randomUUID(),e.correlationId(),e.disclosedSource(),e.authorization(),e.mode()));
   rejected.add(control); // Duplicate at the end must roll back preceding successful chunks.
   assertThrows(Exception.class,()->inTransaction(c,Capability.AUDIT,tx->{audit.appendContracts(tx,rejected);return null;}));
   assertEquals(131,count(seed.tenant()));
  }
 }
 private String row(UUID id)throws Exception {try(var c=database.adminConnection();var p=c.prepareStatement("select (to_jsonb(a)-'audit_entry_id')::text from audit.audit_entry a where audit_entry_id=?")){p.setObject(1,id);try(var r=p.executeQuery()){assertTrue(r.next());return r.getString(1);}}}
 private int count(UUID tenant)throws Exception {try(var c=database.adminConnection();var p=c.prepareStatement("select count(*) from audit.audit_entry where tenant_id=?")){p.setObject(1,tenant);try(var r=p.executeQuery()){r.next();return r.getInt(1);}}}
}
