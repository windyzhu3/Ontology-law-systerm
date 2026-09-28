package io.github.windyzhu3.ontologylaw.lead;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class LeadNamesIT extends LeadBusinessFixture {
    @Test void capture_reads_independent_names_and_replays_without_writes() throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL,true);
        var values=captureValues("independent",true);values.put("customerName"," Customer ");values.put("contactName"," Contact ");
        var e=new CommandEnvelope(CommandEnvelope.Type.CAPTURE_LEAD,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),values);
        var receipt=run(e);assertEquals(CommandOutcome.Status.SUCCEEDED,receipt.status());
        try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
            var lead=CurrentLeadReader.databaseBacked(LeadProtectionTest.protection()).read(x,seed.tenant(),receipt.resultFact().id());
            assertEquals("Synthetic contact",lead.capturedName());
            assertEquals("Customer",lead.customerName());assertEquals("Contact",lead.contactName());
            return null;
        });}
        var before=counts();assertEquals(receipt,run(e));assertEquals(before,counts());
        assertEquals(CommandOutcome.Status.NO_CHANGE,run(new CommandEnvelope(e.type(),UUID.randomUUID(),UUID.randomUUID(),e.actor(),values)).status());
    }
    @Test void each_name_is_optional_and_digest_covers_only_present_new_fields() throws Exception {
        for(String field:List.of("customerName","contactName")) {
            setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL,true);var values=captureValues(field,true);values.put(field,"Independent");
            var receipt=run(new CommandEnvelope(CommandEnvelope.Type.CAPTURE_LEAD,UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),values));
            assertEquals(CommandOutcome.Status.SUCCEEDED,receipt.status());
            try(var c=database.apiConnection()){inTransaction(c,Capability.QUERY,x->{
                var lead=CurrentLeadReader.databaseBacked(LeadProtectionTest.protection()).read(x,seed.tenant(),receipt.resultFact().id());
                assertEquals(field.equals("customerName")?"Independent":null,lead.customerName());
                assertEquals(field.equals("contactName")?"Independent":null,lead.contactName());
                assertNull(CurrentLeadReader.databaseBacked(LeadProtectionTest.protection()).read(x,UUID.randomUUID(),receipt.resultFact().id()));
                try(var statement=x.prepareStatement("select customer_name_ciphertext,contact_name_ciphertext,source_record_key_digest,captured_content_digest from lead.lead where tenant_id=? and lead_id=?")) {
                    statement.setObject(1,seed.tenant());statement.setObject(2,receipt.resultFact().id());
                    try(var row=statement.executeQuery()){assertTrue(row.next());
                        byte[] encrypted=row.getBytes(field.equals("customerName")?1:2);assertNotNull(encrypted);
                        assertFalse(new String(encrypted,java.nio.charset.StandardCharsets.UTF_8).contains("Independent"));
                        var digest=new TreeMap<String,Object>(values);digest.remove("sourceRecordKey");
                        digest.put("sourceRecordKeyDigest",Base64.getUrlEncoder().withoutPadding().encodeToString(row.getBytes(3)));
                        digest.put("cityCode",null);digest.put("email",null);
                        assertArrayEquals(CanonicalJson.digest(CanonicalJson.encode(digest)),row.getBytes(4));
                    }
                }
                return null;
            });}
        }
    }
    @Test void independent_name_columns_are_immutable_and_query_cannot_write_them() throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL,true);var receipt=run(capture("immutable",true));
        for(String field:List.of("customer_name_ciphertext","contact_name_ciphertext")) {
            for(var capability:List.of(Capability.COMMAND,Capability.QUERY))try(var c=database.apiConnection()){
                assertThrows(java.sql.SQLException.class,()->inTransaction(c,capability,x->{
                    try(var statement=x.prepareStatement("update lead.lead set "+field+"=decode('01','hex'),revision=revision+1 where tenant_id=? and lead_id=?")){
                        statement.setObject(1,seed.tenant());statement.setObject(2,receipt.resultFact().id());statement.executeUpdate();return null;
                    }
                }));
            }
        }
    }
    @Test void invalid_names_and_denied_capture_write_no_business_facts() throws Exception {
        setup(R1SourcePolicyRegistry.AssignmentMode.MANUAL,true);
        for(String field:List.of("customerName","contactName"))for(Object value:Arrays.asList(null," ","x".repeat(201),"x\u0000y",17)){
            var e=capture("invalid",true);var v=new TreeMap<String,Object>((Map<String,Object>)e.payload());v.put(field,value);rejectedBefore(payload(e,v),"VALIDATION_FAILED");
        }
        var v=captureValues("denied",true);v.put("customerName","Customer");v.put("contactName","Contact");
        var foreign=actor("HUMAN","OTHER_PERMISSION");
        rejectedBefore(new CommandEnvelope(CommandEnvelope.Type.CAPTURE_LEAD,UUID.randomUUID(),UUID.randomUUID(),foreign,v),"NOT_AUTHORIZED");
    }
}
