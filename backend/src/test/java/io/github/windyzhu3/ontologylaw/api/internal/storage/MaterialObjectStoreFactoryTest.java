package io.github.windyzhu3.ontologylaw.api.internal.storage;

import io.github.windyzhu3.ontologylaw.evidence.MaterialRejectedException;
import java.io.ByteArrayInputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MaterialObjectStoreFactoryTest {
    @Test void missingConfigurationCannotAcceptAnUpload() {
        var store=MaterialObjectStoreFactory.configured(Map.of());
        assertEquals(MaterialRejectedException.Reason.SCAN_UNAVAILABLE,assertThrows(MaterialRejectedException.class,
            () -> store.store(new ByteArrayInputStream(new byte[]{1}))).reason());
    }
    @Test void invalidConfigurationCannotCreateAPassingScanner() {
        var store=MaterialObjectStoreFactory.configured(Map.of("OLS_MATERIAL_STORE_PATH","unused","OLS_CLAMD_PORT","invalid"));
        assertThrows(MaterialRejectedException.class,() -> store.store(new ByteArrayInputStream(new byte[]{1})));
    }
}
