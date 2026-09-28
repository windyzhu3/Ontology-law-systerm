package io.github.windyzhu3.ontologylaw.api.internal.storage;

import io.github.windyzhu3.ontologylaw.evidence.MalwareScanner;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;
import io.github.windyzhu3.ontologylaw.evidence.internal.storage.LocalPrivateMaterialObjectStore;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** Opt-in acceptance against a genuine daemon; the standard suite has no external scanner dependency. */
@EnabledIfSystemProperty(named="t06.clamd.live",matches="true")
class ClamdLiveProbeTest {
    @TempDir Path root;
    @Test void realImagePassesDaemonAndExactPrivateVersionRead() throws Exception {
        var scanner=new ClamdMalwareScanner("127.0.0.1",Integer.getInteger("t06.clamd.port",19447),15000);
        var store=new LocalPrivateMaterialObjectStore(root,scanner);
        var bytes=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4,4,BufferedImage.TYPE_INT_RGB),"png",bytes);
        var object=store.store(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals("image/png",object.mediaType());
        assertTrue(object.scannerEngine().startsWith("ClamAV "));
        assertArrayEquals(bytes.toByteArray(),store.read(object.objectVersion(),object.sha256()));
    }
    @Test void genuineDaemonAcceptsCleanBytesAndDetectsHarmlessEicarTestPattern() {
        int port=Integer.getInteger("t06.clamd.port",19447);
        var scanner=new ClamdMalwareScanner("127.0.0.1",port,15000);
        var clean=scanner.scan(new ByteArrayInputStream("T06 synthetic clean material".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(MalwareScanner.Verdict.CLEAN,clean.verdict());
        assertNotNull(clean.engineVersion());
        assertTrue(clean.engineVersion().startsWith("ClamAV "));
        // Standard non-executable antivirus test signature, assembled here to avoid an infected fixture file.
        String pattern="X5O!P%@AP[4" + "\\PZX54(P^)7CC)7}$" + "EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";
        var flagged=scanner.scan(new ByteArrayInputStream(pattern.getBytes(StandardCharsets.US_ASCII)));
        assertEquals(MalwareScanner.Verdict.INFECTED,flagged.verdict());
        assertEquals(clean.engineVersion(),flagged.engineVersion());
        System.out.println("T06 live scanner: clean=CLEAN; EICAR=INFECTED; engine="+clean.engineVersion());
    }
}
