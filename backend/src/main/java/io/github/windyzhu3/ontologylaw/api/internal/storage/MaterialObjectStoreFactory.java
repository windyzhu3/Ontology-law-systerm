package io.github.windyzhu3.ontologylaw.api.internal.storage;

import io.github.windyzhu3.ontologylaw.evidence.MaterialObjectStore;
import java.util.Map;
import java.util.UUID;
import java.io.*;
import java.nio.file.Path;
import io.github.windyzhu3.ontologylaw.evidence.MaterialRejectedException;

public final class MaterialObjectStoreFactory {
    private MaterialObjectStoreFactory() {}
    public static MaterialObjectStore configured() { return configured(System.getenv()); }
    static MaterialObjectStore configured(Map<String,String> configuration) {
        String path=configuration.get("OLS_MATERIAL_STORE_PATH");
        if(path == null || path.isBlank()) return unavailable();
        try {
            var scanner=new ClamdMalwareScanner(configuration.getOrDefault("OLS_CLAMD_HOST","127.0.0.1"),
                Integer.parseInt(configuration.getOrDefault("OLS_CLAMD_PORT","3310")),
                Integer.parseInt(configuration.getOrDefault("OLS_CLAMD_TIMEOUT_MS","15000")));
            return MaterialObjectStore.localPrivate(Path.of(path),scanner);
        } catch(IOException | RuntimeException invalidConfiguration) { return unavailable(); }
    }
    private static MaterialObjectStore unavailable() {
        return new MaterialObjectStore() {
            public StoredObject store(InputStream source) throws IOException {
                throw new MaterialRejectedException(MaterialRejectedException.Reason.SCAN_UNAVAILABLE);
            }
            public byte[] read(UUID version,String digest) throws IOException {
                throw new IOException("Private material storage unavailable");
            }
        };
    }
}
