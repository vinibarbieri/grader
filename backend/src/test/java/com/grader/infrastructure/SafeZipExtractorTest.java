package com.grader.infrastructure;

import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SafeZipExtractorTest {

    @TempDir
    Path tempDir;

    // -----------------------------------------------------------------------
    // Happy-path tests
    // -----------------------------------------------------------------------

    @Test
    void validZip_extractsFilesUnderTargetDir() throws Exception {
        Path jobDir = tempDir.resolve("job-valid");
        Files.createDirectories(jobDir);

        byte[] zip = buildZip(
            regularFile("submissions/alice/problem1.c", "int main(){return 0;}"),
            regularFile("tests/problem1/case01.in",     "1 2")
        );
        Path zipFile = writeZip("valid.zip", zip);

        new SafeZipExtractor().extract(zipFile, jobDir);

        assertTrue(Files.exists(jobDir.resolve("submissions/alice/problem1.c")));
        assertEquals("int main(){return 0;}",
            Files.readString(jobDir.resolve("submissions/alice/problem1.c")));
        assertTrue(Files.exists(jobDir.resolve("tests/problem1/case01.in")));
    }

    @Test
    void validZip_directoryEntry_isCreated() throws Exception {
        Path jobDir = tempDir.resolve("job-dir-entry");
        Files.createDirectories(jobDir);

        byte[] zip = buildZip(directory("submissions/"));
        Path zipFile = writeZip("dir.zip", zip);

        new SafeZipExtractor().extract(zipFile, jobDir);

        assertTrue(Files.isDirectory(jobDir.resolve("submissions")));
    }

    @Test
    void emptyZip_extractsWithoutError() throws Exception {
        Path jobDir = tempDir.resolve("job-empty");
        Files.createDirectories(jobDir);

        byte[] zip = buildZip();
        Path zipFile = writeZip("empty.zip", zip);

        assertDoesNotThrow(() -> new SafeZipExtractor().extract(zipFile, jobDir));
    }

    // -----------------------------------------------------------------------
    // Security-rejection tests
    // -----------------------------------------------------------------------

    @Test
    void absolutePath_throwsZipSecurityException() throws Exception {
        Path jobDir = tempDir.resolve("job-abs");
        Files.createDirectories(jobDir);

        byte[] zip = buildZip(rawEntry("/etc/passwd", "evil"));
        Path zipFile = writeZip("absolute.zip", zip);

        SafeZipExtractor.ZipSecurityException ex = assertThrows(
            SafeZipExtractor.ZipSecurityException.class,
            () -> new SafeZipExtractor().extract(zipFile, jobDir)
        );
        assertTrue(ex.getMessage().toLowerCase().contains("absolute"),
            "Expected 'absolute' in message, got: " + ex.getMessage());
    }

    @Test
    void pathTraversalWithDotDot_throwsZipSecurityException() throws Exception {
        Path jobDir = tempDir.resolve("job-traversal");
        Files.createDirectories(jobDir);

        byte[] zip = buildZip(rawEntry("../../etc/passwd", "evil"));
        Path zipFile = writeZip("traversal.zip", zip);

        SafeZipExtractor.ZipSecurityException ex = assertThrows(
            SafeZipExtractor.ZipSecurityException.class,
            () -> new SafeZipExtractor().extract(zipFile, jobDir)
        );
        assertTrue(ex.getMessage().contains(".."),
            "Expected '..' in message, got: " + ex.getMessage());
    }

    @Test
    void pathTraversalEmbedded_throwsZipSecurityException() throws Exception {
        Path jobDir = tempDir.resolve("job-embedded-traversal");
        Files.createDirectories(jobDir);

        byte[] zip = buildZip(rawEntry("submissions/../../etc/passwd", "evil"));
        Path zipFile = writeZip("embedded-traversal.zip", zip);

        assertThrows(
            SafeZipExtractor.ZipSecurityException.class,
            () -> new SafeZipExtractor().extract(zipFile, jobDir)
        );
    }

    @Test
    void symlinkEntry_throwsZipSecurityException() throws Exception {
        Path jobDir = tempDir.resolve("job-symlink");
        Files.createDirectories(jobDir);

        byte[] zip = buildZip(symlink("link.c", "/etc/passwd"));
        Path zipFile = writeZip("symlink.zip", zip);

        SafeZipExtractor.ZipSecurityException ex = assertThrows(
            SafeZipExtractor.ZipSecurityException.class,
            () -> new SafeZipExtractor().extract(zipFile, jobDir)
        );
        assertTrue(ex.getMessage().toLowerCase().contains("symlink"),
            "Expected 'symlink' in message, got: " + ex.getMessage());
    }

    @Test
    void nonStandardUnixFileType_throwsZipSecurityException() throws Exception {
        // Entries with non-regular-file, non-directory Unix types are rejected.
        // S_IFBLK = 0x6000 (block device) as an example — not S_IFREG or S_IFDIR.
        Path jobDir = tempDir.resolve("job-nonstandard");
        Files.createDirectories(jobDir);

        byte[] zip = buildZip(unixTypeEntry("device.c", "data", 0x6000));
        Path zipFile = writeZip("device.zip", zip);

        assertThrows(
            SafeZipExtractor.ZipSecurityException.class,
            () -> new SafeZipExtractor().extract(zipFile, jobDir)
        );
    }

    // -----------------------------------------------------------------------
    // ZIP entry builders
    // -----------------------------------------------------------------------

    private record ZipSpec(String name, String content, int unixMode) {}

    /** Regular file with standard Unix mode 0644. */
    private ZipSpec regularFile(String name, String content) {
        return new ZipSpec(name, content, UnixStat.FILE_FLAG | 0644);
    }

    /** Directory entry (name should end with '/'). */
    private ZipSpec directory(String name) {
        return new ZipSpec(name, null, UnixStat.DIR_FLAG | 0755);
    }

    /** Symbolic link entry. */
    private ZipSpec symlink(String name, String target) {
        return new ZipSpec(name, target, UnixStat.LINK_FLAG | 0777);
    }

    /** Entry with a specific non-standard Unix file type (e.g. block device). */
    private ZipSpec unixTypeEntry(String name, String content, int unixTypeFlag) {
        return new ZipSpec(name, content, unixTypeFlag | 0644);
    }

    /** Raw entry with no Unix mode — used for path-injection cases where the name itself is the exploit. */
    private ZipSpec rawEntry(String name, String content) {
        return new ZipSpec(name, content, 0);
    }

    private byte[] buildZip(ZipSpec... specs) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(baos)) {
            for (ZipSpec spec : specs) {
                ZipArchiveEntry entry = new ZipArchiveEntry(spec.name());
                if (spec.unixMode() != 0) {
                    entry.setUnixMode(spec.unixMode());
                }
                zos.putArchiveEntry(entry);
                if (spec.content() != null) {
                    zos.write(spec.content().getBytes());
                }
                zos.closeArchiveEntry();
            }
        }
        return baos.toByteArray();
    }

    private Path writeZip(String filename, byte[] bytes) throws IOException {
        Path p = tempDir.resolve(filename);
        Files.write(p, bytes);
        return p;
    }
}
