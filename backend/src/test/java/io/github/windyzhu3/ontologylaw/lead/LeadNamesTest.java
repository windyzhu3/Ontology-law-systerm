package io.github.windyzhu3.ontologylaw.lead;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class LeadNamesTest {
    static Map<String,Object> values(){return new TreeMap<>(Map.of("sourceChannelCode","FIXTURE","sourceAccountCode","FIXTURE","sourceRecordKey","names","capturedAt","2026-09-14T00:00:00Z","serviceCategoryCode","LEGAL","jurisdictionCode","CN","urgencyCode","NORMAL","legalNeedSummary","Need"));}
    @Test void independent_optional_names_normalize_without_changing_legacy_name(){
        var v=values();v.put("capturedName","Legacy");v.put("customerName","  Cafe\u0301  ");v.put("contactName"," Contact ");
        var normalized=LeadInputs.capture(v);assertEquals("Café",normalized.get("customerName"));assertEquals("Contact",normalized.get("contactName"));assertEquals("Legacy",normalized.get("capturedName"));
        assertFalse(LeadInputs.capture(values()).containsKey("customerName"));
    }
    @Test void independent_name_ciphertexts_bind_tenant_and_field(){
        var p=LeadProtectionTest.protection();var tenant=UUID.randomUUID();
        for(String name:List.of("CUSTOMER_NAME","CONTACT_NAME")){
            var field=LeadProtection.Field.valueOf(name);var encrypted=p.encrypt(tenant,field,"Independent name");
            assertEquals("Independent name",p.decrypt(tenant,field,encrypted));
            assertThrows(IllegalArgumentException.class,()->p.decrypt(UUID.randomUUID(),field,encrypted));
            for(var other:LeadProtection.Field.values())if(other!=field)assertThrows(IllegalArgumentException.class,()->p.decrypt(tenant,other,encrypted));
        }
    }
    @Test void named_r2_schema_expectation_is_explicitly_supported(){
        byte[] digest=new byte[32];digest[0]=1;
        assertEquals("52-plus-2-r2-v1", new io.github.windyzhu3.ontologylaw.execution.RuntimeDatabase.Expected("52-plus-2-r2-v1",digest,digest).schemaVersion());
    }
    @Test void optional_names_reject_null_empty_controls_and_overlength(){
        for(String name:List.of("customerName","contactName"))for(Object invalid:Arrays.asList(null," ","x\u0000y","x".repeat(201),123)){
            var v=values();v.put(name,invalid);assertThrows(IllegalArgumentException.class,()->LeadInputs.capture(v));
        }
    }
}
