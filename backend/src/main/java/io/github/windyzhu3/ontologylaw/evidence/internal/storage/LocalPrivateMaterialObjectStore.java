package io.github.windyzhu3.ontologylaw.evidence.internal.storage;

import io.github.windyzhu3.ontologylaw.evidence.*;
import java.io.*;
import java.nio.file.Path;
import java.util.UUID;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;
import javax.imageio.*;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdfparser.PDFParser;
import static io.github.windyzhu3.ontologylaw.evidence.MaterialRejectedException.Reason.*;

public final class LocalPrivateMaterialObjectStore implements MaterialObjectStore {
    private final Path root;
    private final MalwareScanner scanner;

    public LocalPrivateMaterialObjectStore(Path root, MalwareScanner scanner) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.scanner = Objects.requireNonNull(scanner);
        rejectLinks(this.root);
        Files.createDirectories(this.root);
        rejectLinks(this.root);
        restrict(this.root, true);
    }

    @Override public StoredObject store(InputStream source) throws IOException {
        byte[] bytes = bounded(source);
        var scan = scanner.scan(new ByteArrayInputStream(bytes));
        if (scan == null || scan.verdict() == null || scan.verdict() == MalwareScanner.Verdict.UNAVAILABLE
                || scan.engineVersion() == null || scan.engineVersion().isBlank())
            throw new MaterialRejectedException(SCAN_UNAVAILABLE);
        if (scan.verdict() != MalwareScanner.Verdict.CLEAN) throw new MaterialRejectedException(INFECTED);
        String mediaType = mediaType(bytes);
        UUID version = UUID.randomUUID();
        Path destination = root.resolve(version.toString());
        rejectLinks(root);
        boolean created = false;
        try {
            // CREATE_NEW is atomic and never replaces an existing version, including symbolic links.
            try (var out = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                created = true;
                restrict(destination, false);
                out.write(bytes);
            }
            return new StoredObject(version, sha256(bytes), bytes.length, mediaType, false, scan.engineVersion());
        } catch (IOException | RuntimeException failure) {
            if (created) Files.deleteIfExists(destination);
            throw failure;
        }
    }

    @Override public byte[] read(UUID version, String expectedSha256) throws IOException {
        Objects.requireNonNull(version);
        if (expectedSha256 == null || !expectedSha256.matches("[0-9a-f]{64}"))
            throw new MaterialRejectedException(INTEGRITY_FAILURE);
        rejectLinks(root);
        Path object = root.resolve(version.toString());
        if (!Files.isRegularFile(object, LinkOption.NOFOLLOW_LINKS)) throw new MaterialRejectedException(INTEGRITY_FAILURE);
        byte[] bytes;
        try (var input = Files.newInputStream(object, LinkOption.NOFOLLOW_LINKS)) { bytes = bounded(input); }
        if (!MessageDigest.isEqual(sha256(bytes).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                expectedSha256.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
            throw new MaterialRejectedException(INTEGRITY_FAILURE);
        return bytes;
    }

    private static byte[] bounded(InputStream source) throws IOException {
        var out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        for (int count; (count = source.read(buffer)) != -1;) {
            total += count;
            if (total > MAX_BYTES) throw new MaterialRejectedException(TOO_LARGE);
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    private static String mediaType(byte[] bytes) throws IOException {
        try {
            if (bytes.length > 8 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-') {
                String tail = new String(bytes, Math.max(0,bytes.length-1024), Math.min(bytes.length,1024), java.nio.charset.StandardCharsets.ISO_8859_1);
                if (!tail.stripTrailing().endsWith("%%EOF")) throw new MaterialRejectedException(INVALID_MEDIA);
                try (var input = new RandomAccessReadBuffer(bytes)) {
                    var parser = new PDFParser(input);
                    try (var document = parser.parse(false)) {
                        if (document.isEncrypted() || document.getNumberOfPages() < 1) throw new MaterialRejectedException(INVALID_MEDIA);
                        document.getDocumentCatalog().getPages().forEach(page -> page.getMediaBox());
                    }
                }
                // Parsing is not sanitization: PDF remains attachment-only, including active-content PDFs.
                return "application/pdf";
            }
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw new MaterialRejectedException(INVALID_MEDIA);
                var reader = readers.next();
                try {
                    String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                    if (!format.equals("png") && !format.equals("jpeg")) throw new MaterialRejectedException(INVALID_MEDIA);
                    if (format.equals("jpeg") && (bytes.length < 4 || bytes[bytes.length-2] != (byte)0xff || bytes[bytes.length-1] != (byte)0xd9))
                        throw new MaterialRejectedException(INVALID_MEDIA);
                    if (format.equals("png") && (bytes.length < 20 || !Arrays.equals(Arrays.copyOfRange(bytes, bytes.length-12, bytes.length),
                            new byte[]{0,0,0,0,73,69,78,68,(byte)174,66,96,(byte)130}))) throw new MaterialRejectedException(INVALID_MEDIA);
                    reader.setInput(input, true, true);
                    long pixels = (long)reader.getWidth(0) * reader.getHeight(0);
                    if (pixels < 1 || pixels > 25_000_000) throw new MaterialRejectedException(INVALID_MEDIA);
                    reader.addIIOReadWarningListener((r,w) -> { throw new IllegalArgumentException("Malformed image"); });
                    if (reader.read(0) == null) throw new MaterialRejectedException(INVALID_MEDIA);
                    return format.equals("png") ? "image/png" : "image/jpeg";
                } finally { reader.dispose(); }
            }
        } catch (MaterialRejectedException failure) { throw failure; }
        catch (IOException | RuntimeException failure) { throw new MaterialRejectedException(INVALID_MEDIA); }
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void rejectLinks(Path path) throws IOException {
        for (Path current = path; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current) || (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther()))
                throw new IOException("Private storage cannot use linked paths");
        }
    }

    private static void restrict(Path path, boolean directory) throws IOException {
        var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) { posix.setPermissions(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------")); return; }
        var acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) throw new IOException("Private storage requires filesystem access controls");
        acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
            .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
    }
}
