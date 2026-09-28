package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.contract.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;

class R2ContractExecutionSourceIT extends R2ManualSignatureWorkflowIT {
    @Test void execution_source_requires_exact_archive_and_does_not_create_execution()throws Exception {
        start();arrange();submit();verifyAll();
        var reader=ContractExecutionSourceReader.databaseBacked();
        try(var c=database.apiConnection()){assertTrue(inTransaction(c,Capability.QUERY,x->reader.find(x,seed.tenant(),UUID.randomUUID())).isEmpty());}
        command("ARCHIVE_CONTRACT_SIGNATURE",signaturePayload(Map.of("materialVersionId",material.toString(),"materialSha256",bodySha,"reason","执行条件交接验收归档","archiveComplete",true)));
        UUID handoff=UUID.fromString(scalar("select signature_handoff_id from contract.signature_handoff where tenant_id=?",seed.tenant()));
        ContractExecutionConditions.Basis basis;
        try(var c=database.apiConnection()){
            basis=inTransaction(c,Capability.QUERY,x->reader.find(x,seed.tenant(),handoff)).orElseThrow();
            assertEquals(seed.tenant(),basis.tenantId());assertEquals(handoff,basis.handoffId());
            assertEquals(scalar("select archive_id from contract.signature_handoff where tenant_id=?",seed.tenant()),basis.archiveId().toString());
            assertEquals(scalar("select current_revision_id from contract.contract where tenant_id=?",seed.tenant()),basis.revisionId().toString());
            assertTrue(inTransaction(c,Capability.QUERY,x->reader.find(x,UUID.randomUUID(),handoff)).isEmpty());
            assertEquals(basis,inTransaction(c,Capability.QUERY,x->reader.find(x,seed.tenant(),handoff)).orElseThrow());
            var page=inTransaction(c,Capability.QUERY,x->reader.page(x,seed.tenant(),1,null));
            assertEquals(1,page.size());var source=page.getFirst();assertEquals(basis,source.basis());assertEquals(opportunity.id(),source.opportunityId());
            assertEquals(scalar("select extract(epoch from created_at)::text from contract.signature_handoff where tenant_id=?",seed.tenant()),new java.math.BigDecimal(source.handoffAt().getEpochSecond()).add(java.math.BigDecimal.valueOf(source.handoffAt().getNano(),9)).setScale(6).toPlainString());
            assertTrue(inTransaction(c,Capability.QUERY,x->reader.page(x,seed.tenant(),1,handoff)).isEmpty());
            assertTrue(inTransaction(c,Capability.QUERY,x->reader.page(x,UUID.randomUUID(),1,null)).isEmpty());
            assertEquals(page,inTransaction(c,Capability.QUERY,x->reader.page(x,seed.tenant(),1,null)));
            assertThrows(IllegalArgumentException.class,()->reader.page(c,seed.tenant(),0,null));
            assertThrows(IllegalArgumentException.class,()->reader.page(c,seed.tenant(),101,null));
        }
        assertEquals("0",scalar("select count(*) from contract.contract_execution where tenant_id=?",seed.tenant()));
    }
}
