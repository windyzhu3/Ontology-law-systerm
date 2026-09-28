package io.github.windyzhu3.ontologylaw.api;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.execution.R1BusinessFence;
import io.github.windyzhu3.ontologylaw.contract.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
class R25PartySnapshotReadIT extends R2ContractVersionPersistenceIT {
 @Test void contract_party_snapshot_reuses_exact_raw_rows_only_in_its_owner_scope()throws Exception{
  initialize();var service=R2ContractServices.create(protectedBodies,cipher,null);
  try(var c=database.apiConnection()){
   var probe=new ReadConnectionProbe(c);inTransaction(probe.connection(),Capability.QUERY,tx->{
    R1BusinessFence.databaseBacked().shared(tx,seed.tenant());
    try(var scope=service.lockedLedgerFacts(tx,seed.tenant())){
     var digest=versions().partySnapshotDigest(tx,seed.tenant(),confirmation);
     for(int i=0;i<10;i++)assertEquals(digest,versions().partySnapshotDigest(tx,seed.tenant(),confirmation));
     assertEquals(1,probe.statements.stream().filter(q->q.contains("from opportunity.customer_requirement_participant x")).count());
     assertThrows(ContractPreparationSources.Unavailable.class,()->versions().partySnapshotDigest(tx,UUID.randomUUID(),confirmation));
    }
    long before=probe.statements.size();assertNotNull(versions().partySnapshotDigest(tx,seed.tenant(),confirmation));assertTrue(probe.statements.size()>before);return null;
   });
  }
 }
}
