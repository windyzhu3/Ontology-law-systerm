package io.github.windyzhu3.ontologylaw.api;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;import org.junit.jupiter.api.Test;import tools.jackson.databind.json.JsonMapper;
import io.github.windyzhu3.ontologylaw.api.adapter.generated.model.*;
class MaterialsJsonTest {
 @Test void only_absent_optional_fields_are_omitted(){var mapper=JsonMapper.builder().addModule(R1JsonConfiguration.oneOfModule()).build();var ctx=mapper.valueToTree(new OpportunityMaterialsContextV1());assertTrue(ctx.has("responsibilityBasis"));assertFalse(ctx.has("confirmation"));assertFalse(ctx.has("currentOwnerLabel"));var upload=mapper.valueToTree(new OpportunityMaterialUploadV1());for(var key:List.of("note","previousVersion","confirmation"))assertTrue(upload.has(key),key);for(var key:List.of("sizeBytes","mediaType","resultCode"))assertFalse(upload.has(key),key);}
 @Test void both_material_receipt_variants_bind(){for(var fact:List.of("OPPORTUNITY_MATERIAL_UPLOAD","OPPORTUNITY_MATERIAL_VERSION")){var body=Map.of("commandId",UUID.randomUUID().toString(),"receiptId",UUID.randomUUID().toString(),"outcome","SUCCEEDED","completedAt","2026-09-19T10:00:00Z","resultFact",Map.of("factType",fact,"factRef","opaque-material-fact-reference","revision",0));assertDoesNotThrow(()->R1WireModels.model(body,OpportunityMaterialCommandReceiptV1.class));assertDoesNotThrow(()->R1WireModels.receipt(body));}}
}
