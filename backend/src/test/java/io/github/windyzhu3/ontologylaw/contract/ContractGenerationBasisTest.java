package io.github.windyzhu3.ontologylaw.contract;
import java.util.*;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class ContractGenerationBasisTest {
    Map<String,Object> payload(){return new LinkedHashMap<>(Map.of("opportunityId",UUID.randomUUID().toString(),"expectedOpportunityRevision",0,"values",new LinkedHashMap<>(Map.of("commercial",Map.of("scope","scope"),"document",Map.of("templateVersionId","template","clauseVersionIds",List.of()),"signing",Map.of("requirements","sign"),"paymentGate",Map.of("receiptRequiredBeforeTransfer",false)))));}
    @Test @SuppressWarnings("unchecked") void material_reference_is_added_after_generation_but_business_changes_invalidate_proof(){var p=payload();String first=ContractGenerationBasis.digest(p);var values=(Map<String,Object>)p.get("values");values.put("document",Map.of("templateVersionId","template","clauseVersionIds",List.of(),"evidenceVersionId","material","bodySha256","sha","generationProof","token","humanConfirmed",true));assertEquals(first,ContractGenerationBasis.digest(p));values.put("signing",Map.of("requirements","changed"));assertNotEquals(first,ContractGenerationBasis.digest(p));}
}
