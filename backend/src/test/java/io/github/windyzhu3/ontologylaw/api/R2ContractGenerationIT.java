package io.github.windyzhu3.ontologylaw.api;
import java.util.*;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.contract.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
class R2ContractGenerationIT extends R2ContractWorkflowPersistenceIT {
    @Test void proven_candidate_forms_exact_version_and_never_reuses_old_proof_for_changed_inputs()throws Exception {
        versionFixture();var p=generationPayload();ContractWorkflowService.Generation candidate;try(var c=database.apiConnection()){candidate=inTransaction(c,Capability.QUERY,x->service().generation(x,actor(),p));}
        var values=(Map<String,Object>)p.get("values");var doc=new LinkedHashMap<>((Map<String,Object>)values.get("document"));doc.put("generationProof",new ContractGenerationProof(protectedBodies).issue(seed.tenant(),opportunity.id(),seed.appointment(),candidate.basisDigest(),bodySha,candidate.expiresAt()));doc.put("humanConfirmed",true);values.put("document",doc);
        var altered=new LinkedHashMap<>(values);altered.put("signing",Map.of("partySnapshotDigest",partyDigest,"requirements","changed"));var stale=new LinkedHashMap<>(p);stale.put("values",altered);assertThrows(ContractWorkflowService.Blocked.class,()->command("FORM_CONTRACT",stale));
        var formed=command("FORM_CONTRACT",p);assertEquals("contract.contract_revision",formed.type());assertEquals("SUBMIT_REVIEW",((Map<?,?>)context().get("workflow")).get("stage"));
    }
    @Test void snapshot_contains_exact_terms_and_source_without_reading_object_bytes_or_forming_a_version()throws Exception{
        versionFixture();var p=generationPayload();ContractWorkflowService.Generation first;
        try(var c=database.apiConnection()){first=inTransaction(c,Capability.QUERY,x->service().generation(x,actor(),p));}
        assertEquals(bodySha,first.template().sha256());assertEquals(terms.scope(),first.fields().get("scope"));assertTrue(first.fields().get("fees").contains(java.math.BigDecimal.valueOf(terms.totalMinor(),2).toPlainString()));
        assertEquals("0",scalar("select count(*) from contract.contract_revision where tenant_id=?",seed.tenant()));
        var values=(Map<String,Object>)p.get("values");var signing=new LinkedHashMap<>((Map<String,Object>)values.get("signing"));signing.put("requirements","修改后的签署要求");values.put("signing",signing);
        try(var c=database.apiConnection()){var second=inTransaction(c,Capability.QUERY,x->service().generation(x,actor(),p));assertNotEquals(first.basisDigest(),second.basisDigest());}
    }
    Map<String,Object> generationPayload()throws Exception{var values=new LinkedHashMap<>(preparation(input(1,null)));values.put("commercial",terms.canonical());var doc=new LinkedHashMap<>((Map<String,Object>)values.get("document"));doc.put("clauseVersionIds",List.of());values.put("document",doc);return payload(context(),values);}
    @Test void generation_rejects_stale_workflow_and_denied_preparation_without_writing_version()throws Exception{
        versionFixture();var p=generationPayload();p.put("expectedOpportunityRevision",999);
        try(var c=database.apiConnection()){assertThrows(ContractWorkflowService.Blocked.class,()->inTransaction(c,Capability.QUERY,x->service().generation(x,actor(),p)));}
        p.put("expectedOpportunityRevision",opportunity.revision());permitted=false;
        try(var c=database.apiConnection()){assertThrows(ContractWorkflowService.Blocked.class,()->inTransaction(c,Capability.QUERY,x->service().generation(x,actor(),p)));}
        assertEquals("0",scalar("select count(*) from contract.contract_revision where tenant_id=?",seed.tenant()));
    }
}
