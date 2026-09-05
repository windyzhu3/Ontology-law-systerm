package io.github.windyzhu3.ontologylaw.testing;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PostgresImageLockTest {
    @TempDir Path temporary;

    @Test void rejects_unlocked_tags_wrong_major_missing_or_duplicate_postgres() throws Exception {
        Path lock = temporary.resolve("lock.json");
        for (String json : new String[]{
                "{\"images\":[{\"image\":\"postgres\",\"tag\":\"18\"}]}",
                "{\"images\":[{\"image\":\"postgres\",\"tag\":\"latest\",\"digest\":\"sha256:" + "a".repeat(64) + "\"}]}",
                "{\"images\":[]}",
                "{\"images\":[" + entry() + "," + entry() + "]}"}) {
            Files.writeString(lock, json);
            assertThrows(IllegalStateException.class, () -> PostgresIntegrationTest.lockedPostgresImage(lock));
        }
    }

    @Test void resolves_digest_reference_without_mutable_tag() throws Exception {
        Path lock = temporary.resolve("lock.json");
        Files.writeString(lock, "{\"images\":[" + entry() + "]}");
        assertEquals("postgres@sha256:" + "a".repeat(64), PostgresIntegrationTest.lockedPostgresImage(lock));
    }

    private static String entry() {
        return "{\"image\":\"postgres\",\"tag\":\"18\",\"digest\":\"sha256:" + "a".repeat(64) + "\"}";
    }
}
