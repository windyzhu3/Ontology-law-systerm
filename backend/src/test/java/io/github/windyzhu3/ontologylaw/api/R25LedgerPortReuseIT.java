package io.github.windyzhu3.ontologylaw.api;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.execution.R1BusinessFence;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService;
import io.github.windyzhu3.ontologylaw.opportunity.OpportunityMaterials;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class R25LedgerPortReuseIT extends R2QuoteWorkflowIT {
 @Test void exact_contract_task_reads_reuse_only_the_same_fenced_connection_and_tenant()throws Exception {
  setup(true,true);confirmed();
  var id=UUID.fromString(scalar("select task_occurrence_id::text from responsibility.task_occurrence where tenant_id=? and subject_id=? and state in ('OPEN','WAITING')",seed.tenant(),opportunity.id()));
  try(var c=database.apiConnection()){
   var probe=new ReadConnectionProbe(c);inTransaction(probe.connection(),Capability.QUERY,tx->{
    R1BusinessFence.databaseBacked().shared(tx,seed.tenant());
    try(var identity=AuthorizationService.databaseBacked().lockedReadScope(tx,seed.tenant());var scope=R2LedgerSourceFacts.open(tx,seed.tenant())){
     var first=new ContractWorkflowPorts(cipher,null).read(tx,seed.tenant(),id);assertNotNull(first);
     for(int i=0;i<20;i++)assertEquals(first,new ContractWorkflowPorts(cipher,null).read(tx,seed.tenant(),id));
     assertEquals(1,probe.statements.stream().filter(q->q.contains("from responsibility.task_occurrence")||q.contains("from \"responsibility\".\"task_occurrence\"")).count());
     assertNull(new ContractWorkflowPorts(cipher,null).read(tx,UUID.randomUUID(),id));
     try(var other=database.apiConnection()){
      var otherProbe=new ReadConnectionProbe(other);inTransaction(otherProbe.connection(),Capability.QUERY,otherTx->{assertEquals(first,new ContractWorkflowPorts(cipher,null).read(otherTx,seed.tenant(),id));return null;});
      assertTrue(otherProbe.statements.stream().anyMatch(q->q.contains("task_occurrence")),"Another connection must query its own source");
     }
    }
    long count=probe.statements.size();assertNotNull(new ContractWorkflowPorts(cipher,null).read(tx,seed.tenant(),id));assertTrue(probe.statements.size()>count);return null;
   });
  }
 }
 @Test void contract_and_quote_reuse_the_same_task_lineage_only_inside_one_fenced_read()throws Exception {
  setup(true,true);confirmed();
  var id=UUID.fromString(scalar("select task_occurrence_id::text from responsibility.task_occurrence where tenant_id=? and subject_id=? and state in ('OPEN','WAITING')",seed.tenant(),opportunity.id()));
  try(var c=database.apiConnection()){
   var probe=new ReadConnectionProbe(c);inTransaction(probe.connection(),Capability.QUERY,tx->{
    R1BusinessFence.databaseBacked().shared(tx,seed.tenant());
    try(var identity=AuthorizationService.databaseBacked().lockedReadScope(tx,seed.tenant());var scope=R2LedgerSourceFacts.open(tx,seed.tenant())){
     var contracts=new ContractWorkflowPorts(cipher,null);var quotes=new QuoteWorkflowPorts(cipher);
     var first=contracts.currentTask(tx,seed.tenant(),id);assertNotNull(first);
     for(int i=0;i<10;i++){assertEquals(first,contracts.currentTask(tx,seed.tenant(),id));assertEquals(first.selector(),quotes.read(tx,seed.tenant(),id).selector());}
     assertEquals(2,probe.statements.stream().filter(q->q.contains("from responsibility.task_occurrence")||q.contains("from \"responsibility\".\"task_occurrence\"")).count());
     assertNull(contracts.currentTask(tx,UUID.randomUUID(),id));
    }
    long count=probe.statements.size();assertNotNull(new ContractWorkflowPorts(cipher,null).currentTask(tx,seed.tenant(),id));assertTrue(probe.statements.size()>count);return null;
   });
  }
 }
 @Test void material_source_metadata_is_reused_but_never_across_tenants_or_scope_closure()throws Exception {
  setup(true,true);confirmed();var exact=material();
  try(var c=database.apiConnection()){
   var probe=new ReadConnectionProbe(c);inTransaction(probe.connection(),Capability.QUERY,tx->{
    R1BusinessFence.databaseBacked().shared(tx,seed.tenant());var version=OpportunityMaterials.databaseBacked().version(tx,seed.tenant(),exact.id());
    try(var identity=AuthorizationService.databaseBacked().lockedReadScope(tx,seed.tenant());var scope=R2LedgerSourceFacts.open(tx,seed.tenant())){
     var ports=new QuoteWorkflowPorts(cipher);var first=ports.materialFacts(tx,seed.tenant(),version);
     for(int i=0;i<10;i++)assertEquals(first,ports.materialFacts(tx,seed.tenant(),version));
     assertEquals(1,probe.statements.stream().filter(q->q.contains("from evidence.material_upload_basis")).count());
     assertEquals(1,probe.statements.stream().filter(q->q.contains("from \"evidence\".\"evidence_submission\"")).count());
     assertThrows(io.github.windyzhu3.ontologylaw.opportunity.QuoteWorkflowService.Blocked.class,()->ports.materialFacts(tx,UUID.randomUUID(),version));
    }
    long count=probe.statements.size();assertFalse(new QuoteWorkflowPorts(cipher).materialFacts(tx,seed.tenant(),version).isEmpty());assertTrue(probe.statements.size()>count);return null;
   });
  }
 }
}
