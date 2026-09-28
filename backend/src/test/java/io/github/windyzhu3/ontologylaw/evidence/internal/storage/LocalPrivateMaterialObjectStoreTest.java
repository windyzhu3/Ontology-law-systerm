package io.github.windyzhu3.ontologylaw.evidence.internal.storage;

import io.github.windyzhu3.ontologylaw.evidence.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

class LocalPrivateMaterialObjectStoreTest {
    @TempDir Path root;
    private final MalwareScanner clean = in -> new MalwareScanner.ScanResult(MalwareScanner.Verdict.CLEAN,"ClamAV test/1");
    @Test void preservesSeparateVersionsAndVerifiesExactBytes() throws Exception {
        var store = new LocalPrivateMaterialObjectStore(root, clean);
        byte[] png = png();
        var first = store.store(new ByteArrayInputStream(png));
        var second = store.store(new ByteArrayInputStream(png));
        assertNotEquals(first.objectVersion(), second.objectVersion());
        assertEquals("image/png", first.mediaType());
        assertFalse(first.inlinePreviewSafe());
        assertEquals("ClamAV test/1", first.scannerEngine());
        assertArrayEquals(png, store.read(first.objectVersion(), first.sha256()));
        assertEquals(MaterialRejectedException.Reason.INTEGRITY_FAILURE,
            assertThrows(MaterialRejectedException.class, () -> store.read(first.objectVersion(), "0".repeat(64))).reason());
    }
    @Test void rejectsTruncatedSignatureOnlyFileWithoutRetainingObject() throws Exception {
        var store = new LocalPrivateMaterialObjectStore(root, clean);
        assertEquals(MaterialRejectedException.Reason.INVALID_MEDIA,
            assertThrows(MaterialRejectedException.class, () -> store.store(new ByteArrayInputStream(new byte[]{(byte)137,80,78,71,13,10,26,10}))).reason());
        try(var entries=Files.list(root)) { assertEquals(0,entries.count()); }
    }
    @Test void unavailableOrInfectedScannerCannotAcceptMaterial() throws Exception {
        for(var verdict : new MalwareScanner.Verdict[]{MalwareScanner.Verdict.UNAVAILABLE, MalwareScanner.Verdict.INFECTED}) {
            var store = new LocalPrivateMaterialObjectStore(root, in -> new MalwareScanner.ScanResult(verdict,"test"));
            assertThrows(MaterialRejectedException.class, () -> store.store(new ByteArrayInputStream(png())));
        }
        try(var entries=Files.list(root)) { assertEquals(0,entries.count()); }
    }
    @Test void boundsInputEvenWithoutDeclaredLength() throws Exception {
        var store = new LocalPrivateMaterialObjectStore(root, clean);
        InputStream forever = new InputStream() { public int read() { return 1; }
            public int read(byte[] b,int off,int len) { java.util.Arrays.fill(b,off,off+len,(byte)1); return len; }};
        assertEquals(MaterialRejectedException.Reason.TOO_LARGE,
            assertThrows(MaterialRejectedException.class, () -> store.store(forever)).reason());
    }
    @Test void parsesPdfButAlwaysUsesAttachmentFallback() throws Exception {
        var store = new LocalPrivateMaterialObjectStore(root, clean);
        var out = new ByteArrayOutputStream();
        try(var document = new PDDocument()) { document.addPage(new PDPage()); document.save(out); }
        var stored = store.store(new ByteArrayInputStream(out.toByteArray()));
        assertEquals("application/pdf", stored.mediaType());
        assertFalse(stored.inlinePreviewSafe());
        assertArrayEquals(out.toByteArray(), store.read(stored.objectVersion(), stored.sha256()));
        assertEquals(MaterialRejectedException.Reason.INVALID_MEDIA, assertThrows(MaterialRejectedException.class,
            () -> store.store(new ByteArrayInputStream("%PDF-1.7\nnot a PDF\n%%EOF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))).reason());
    }
    @Test void decodesJpegAndRejectsTruncation() throws Exception {
        var store = new LocalPrivateMaterialObjectStore(root, clean);
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB), "jpeg", out);
        byte[] jpeg = out.toByteArray();
        assertEquals("image/jpeg",store.store(new ByteArrayInputStream(jpeg)).mediaType());
        assertThrows(MaterialRejectedException.class,
            () -> store.store(new ByteArrayInputStream(java.util.Arrays.copyOf(jpeg,jpeg.length-2))));
    }
    @Test void rejectsPdfThatNeedsCrossReferenceRepair() throws Exception {
        var store=new LocalPrivateMaterialObjectStore(root,clean);
        var out=new ByteArrayOutputStream();
        try(var document=new PDDocument()) { document.addPage(new PDPage()); document.save(out); }
        String malformed=new String(out.toByteArray(),java.nio.charset.StandardCharsets.ISO_8859_1)
            .replaceFirst("startxref\\s+\\d+","startxref\n0");
        assertThrows(MaterialRejectedException.class, () -> store.store(new ByteArrayInputStream(
            malformed.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1))));
    }
    @Test void changedFileFailsExactVersionRead() throws Exception {
        var store = new LocalPrivateMaterialObjectStore(root, clean);
        var object = store.store(new ByteArrayInputStream(png()));
        // The filesystem owner can tamper; a frozen database digest must still detect it.
        Files.write(root.resolve(object.objectVersion().toString()),new byte[]{9,8,7});
        assertEquals(MaterialRejectedException.Reason.INTEGRITY_FAILURE,assertThrows(MaterialRejectedException.class,
            () -> store.read(object.objectVersion(),object.sha256())).reason());
    }
    @Test void interruptedUploadLeavesNoPartialObject() throws Exception {
        var store = new LocalPrivateMaterialObjectStore(root, clean);
        assertThrows(IOException.class, () -> store.store(new InputStream() {
            public int read() throws IOException { throw new IOException("connection lost"); }
        }));
        try(var entries=Files.list(root)) { assertEquals(0,entries.count()); }
    }
    @Test void refusesFileAndRootSymlinksWhenSupported() throws Exception {
        Path target = root.resolve("target");
        Files.createDirectory(target);
        Path link = root.resolve("link");
        try { Files.createSymbolicLink(link,target); }
        catch (FileSystemException | UnsupportedOperationException unsupported) {
            org.junit.jupiter.api.Assumptions.abort("Symlink creation unavailable on this platform");
        }
        assertThrows(IOException.class, () -> new LocalPrivateMaterialObjectStore(link,clean));
        var store = new LocalPrivateMaterialObjectStore(target, clean);
        var object = store.store(new ByteArrayInputStream(png()));
        Path objectPath = target.resolve(object.objectVersion().toString());
        Files.delete(objectPath);
        Files.createSymbolicLink(objectPath,root.resolve("elsewhere"));
        assertThrows(IOException.class, () -> store.read(object.objectVersion(),object.sha256()));
    }
    static byte[] png() throws IOException {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }
}
