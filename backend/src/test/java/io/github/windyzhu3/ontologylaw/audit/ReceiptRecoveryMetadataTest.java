package io.github.windyzhu3.ontologylaw.audit;

import io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ReceiptRecoveryMetadataTest {
    private static final String TENANT="11111111-1111-1111-1111-111111111111";
    private static final String VALID="{\"profile\":\"R1_COMMAND_RECEIPT_RECOVERY_V1\",\"scope\":{\"profile\":\"R1_CAPTURE_SCOPE_V1\",\"tenantId\":\""+TENANT+"\",\"sourceAccountCode\":\"SOURCE\",\"sourceRecordKeyDigest\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\"},\"binding\":{\"kind\":\"CAPTURE\"}}";
    @ParameterizedTest
    @ValueSource(strings={"{\"a\":null,\"a\":1}","{\"a\":1,\"a\":2}","{\"a\":1,}","[1,]","01","1.0","1e0","9007199254740992","-9007199254740992","true false","{\"a\":}","\"\\u+001\"","\"\\u-001\"","\"\\uD800\"","\"\\q\""})
    void malformed_json_never_becomes_recovery_data(String raw) {
        assertThrows(IllegalArgumentException.class,()->ReceiptAuditJson.parse(raw));
    }
    @Test void valid_capture_preserves_only_typed_restricted_selectors() {
        var metadata=new ReceiptRecoveryMetadata("CAPTURE_LEAD",ReceiptAuditJson.parse(VALID));
        assertEquals(UUID.fromString(TENANT),metadata.tenantId());assertEquals("SOURCE",metadata.sourceAccountCode());
        assertEquals("ReceiptRecoveryMetadata[restricted]",metadata.toString());
        assertNull(metadata.taskId());assertNull(metadata.lead());
        byte[] first=metadata.scopeDigest();first[0]^=1;assertFalse(Arrays.equals(first,metadata.scopeDigest()));
    }
    @Test void unknown_missing_duplicate_wrong_kind_and_scalar_metadata_are_rejected() {
        var bad=new ArrayList<String>();
        bad.add(VALID.replace("\"kind\":\"CAPTURE\"","\"kind\":\"CAPTURE\",\"extra\":null"));
        bad.add(VALID.replace("\"kind\":\"CAPTURE\"","\"kind\":\"CAPTURE\",\"kind\":\"CAPTURE\""));
        bad.add(VALID.replace("\"kind\":\"CAPTURE\"","\"kind\":\"TASK\""));
        bad.add(VALID.replace("\"profile\":\"R1_COMMAND_RECEIPT_RECOVERY_V1\",",""));
        bad.add(VALID.replace("R1_COMMAND_RECEIPT_RECOVERY_V1","UNSUPPORTED"));
        bad.add(VALID.replace("\"sourceAccountCode\":\"SOURCE\"","\"sourceAccountCode\":1"));
        bad.add(VALID.replace("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="));
        bad.add(VALID.replace(TENANT,"11111111111111111111111111111111"));
        for(String raw:bad)assertThrows(IllegalArgumentException.class,()->new ReceiptRecoveryMetadata("CAPTURE_LEAD",ReceiptAuditJson.parse(raw)));
        assertThrows(IllegalArgumentException.class,()->new ReceiptRecoveryMetadata("REOPEN_DUE_CONTACT_TASKS",ReceiptAuditJson.parse(VALID)));
    }
    @Test void canonical_audit_json_preserves_unicode_and_safe_integer_without_coercion() {
        assertEquals("{\"a\":9007199254740991,\"z\":\"中文\\n😀\"}",
                ReceiptAuditJson.encode(ReceiptAuditJson.parse("{\"z\":\"中文\\n\\uD83D\\uDE00\",\"a\":9007199254740991}")));
    }
}
