package io.github.windyzhu3.ontologylaw.evidence;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/** Internal server port; opaque versions must never be exposed as public object URLs. */
public interface MaterialObjectStore {
    long MAX_BYTES = 20L * 1024 * 1024;
    StoredObject store(InputStream source) throws IOException;
    byte[] read(UUID objectVersion, String expectedSha256) throws IOException;
    static MaterialObjectStore localPrivate(java.nio.file.Path root, MalwareScanner scanner) throws IOException {
        return new io.github.windyzhu3.ontologylaw.evidence.internal.storage.LocalPrivateMaterialObjectStore(root, scanner);
    }
    record StoredObject(UUID objectVersion, String sha256, long byteSize, String mediaType,
                        boolean inlinePreviewSafe, String scannerEngine) {}
}
