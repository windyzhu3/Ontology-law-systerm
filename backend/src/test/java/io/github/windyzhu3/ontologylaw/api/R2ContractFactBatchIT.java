package io.github.windyzhu3.ontologylaw.api;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.util.*;import org.junit.jupiter.api.Test;
class R2ContractFactBatchIT extends R2ManualSignatureWorkflowIT {
 @Test void document_graph_is_reused_only_for_the_exact_fenced_connection_and_tenant()throws Exception {
  start();var ports=new ContractWorkflowPorts(cipher,null);
  try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
   io.github.windyzhu3.ontologylaw.execution.R1BusinessFence.databaseBacked().shared(x,seed.tenant());List<Subject> first;
   try(var identity=io.github.windyzhu3.ontologylaw.identity.AuthorizationService.databaseBacked().lockedReadScope(x,seed.tenant());var scope=R2LedgerSourceFacts.open(x,seed.tenant())){
    first=ports.documentFacts(x,seed.tenant(),material);assertFalse(first.isEmpty());assertSame(first,ports.documentFacts(x,seed.tenant(),material));
    assertTrue(ports.documentFacts(x,UUID.randomUUID(),material).isEmpty());assertTrue(ports.documentFacts(x,seed.tenant(),UUID.randomUUID()).isEmpty());
    try(var other=database.apiConnection()){inTransaction(other,Capability.QUERY,y->{assertNotSame(first,ports.documentFacts(y,seed.tenant(),material));return null;});}
   }
   assertNotSame(first,ports.documentFacts(x,seed.tenant(),material));return null;
  });}
 }
 @Test void contract_reference_reads_keep_all_exact_facts_with_bounded_round_trips()throws Exception {
  start();
  var tables=List.of("preparation_request","preparation_draft","preparation_workflow","execution_workflow","execution_verification","payment_request","payment_workflow","payment_review","signature_arrangement","signature_draft","signature_submission","signature_verification","signature_archive","signature_revision_return","signature_workflow","signature_handoff");
  try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
   var expected=new HashSet<Subject>();
   for(var table:tables)try(var p=x.prepareStatement("select "+table+"_id from contract."+table+" where tenant_id=? and opportunity_id=?")){
    p.setObject(1,seed.tenant());p.setObject(2,opportunity.id());try(var r=p.executeQuery()){while(r.next())expected.add(new Subject("contract."+table,r.getObject(1,UUID.class),0L,null));}
   }
   assertFalse(expected.isEmpty());var probe=new ReadConnectionProbe(x);var facts=service().protectedFacts(probe.connection(),seed.tenant(),opportunity.id());
   assertEquals(expected,new HashSet<>(facts.stream().filter(f->tables.contains(f.type().substring(f.type().indexOf('.')+1))&&f.type().startsWith("contract.")).toList()),"Batching must neither omit nor add exact references");
   var revisionTables=List.of("revision_review_request","revision_review_binding","revision_approval_request","revision_approval_requirement","signature_readiness");
   var revisionFacts=new HashSet<Subject>();
   for(var table:revisionTables)try(var p=x.prepareStatement("select f."+table+"_id from contract."+table+" f join contract.contract_revision v on v.tenant_id=f.tenant_id and v.contract_revision_id=f.contract_revision_id join contract.contract root on root.tenant_id=v.tenant_id and root.contract_id=v.contract_id where f.tenant_id=? and root.opportunity_id=?")){
    p.setObject(1,seed.tenant());p.setObject(2,opportunity.id());try(var r=p.executeQuery()){while(r.next())revisionFacts.add(new Subject("contract."+table,r.getObject(1,UUID.class),0L,null));}
   }
   assertFalse(revisionFacts.isEmpty());assertEquals(revisionFacts,new HashSet<>(facts.stream().filter(f->f.type().startsWith("contract.")&&revisionTables.contains(f.type().substring("contract.".length()))).toList()));
   assertEquals(1,probe.statements.stream().filter(sql->revisionTables.stream().anyMatch(table->sql.contains(" id from contract."+table+" where tenant_id=? and contract_revision_id=?"))).count());
   long reads=probe.statements.stream().filter(sql->tables.stream().anyMatch(table->sql.contains(" id from contract."+table+" where tenant_id=? and opportunity_id=?"))).count();
   assertEquals(2,reads,"Contract and signature reference sets should each use one database round trip");return null;
  });}
 }
}
