package com.grader.infrastructure;

import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;

/**
 * Extracts ZIP archives safely, rejecting entries that could exploit path
 * traversal, absolute paths, symlinks, or non-standard Unix file types.
 *
 * <p>Validation rules applied to every ZIP entry:
 * <ol>
 *   <li>Entry name must not start with '/' or '\' (absolute path).</li>
 *   <li>Entry name must not contain '..' (path traversal).</li>
 *   <li>Unix file type (from external attributes) must be a regular file or
 *       directory; symlinks and other types (block device, etc.) are rejected.</li>
 *   <li>Resolved canonical path must remain inside the target directory
 *       (defense-in-depth against edge-case escapes).</li>
 * </ol>
 *
 * <p>Note: the '..' check intentionally rejects any name that contains the
 * substring '..', including filenames like 'file..name'. This is an acceptable
 * trade-off for the security-sensitive grading context.
 */
@Component
public class SafeZipExtractor {

    private static final Logger log = LoggerFactory.getLogger(SafeZipExtractor.class);

    /**
     * Extracts {@code zipFile} into {@code targetDir}.
     *
     * @param zipFile   path to the ZIP archive
     * @param targetDir destination directory (must already exist)
     * @throws ZipSecurityException if any entry violates the safety rules
     * @throws IOException          on I/O errors
     */
    public void extract(Path zipFile, Path targetDir) throws IOException {
        log.info("Extracting ZIP '{}' into '{}'", zipFile.getFileName(), targetDir);
        Path canonicalTarget = targetDir.toRealPath();

        try (ZipFile zf = ZipFile.builder().setPath(zipFile).get()) {
            Enumeration<ZipArchiveEntry> entries = zf.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                validateEntry(entry, canonicalTarget);
                extractEntry(zf, entry, canonicalTarget);
            }
        }

        log.info("ZIP extraction complete — target='{}'", targetDir);
    }

    // -----------------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------------

    private void validateEntry(ZipArchiveEntry entry, Path canonicalTarget)
            throws ZipSecurityException {
        String name = entry.getName();

        // Rule 1 — reject absolute paths
        if (name.startsWith("/") || name.startsWith("\\")) {
            throw new ZipSecurityException(
                "Rejected absolute path entry: '" + name + "'");
        }

        // Rule 2 — reject path traversal (any occurrence of '..')
        if (name.contains("..")) {
            throw new ZipSecurityException(
                "Rejected path traversal entry (contains '..'): '" + name + "'");
        }

        // Rule 3 — validate Unix file type from external attributes
        int unixMode = entry.getUnixMode();
        if (unixMode != 0) {
            int fileType = unixMode & UnixStat.FILE_TYPE_FLAG;
            if (fileType == UnixStat.LINK_FLAG) {
                throw new ZipSecurityException(
                    "Rejected symlink entry: '" + name + "'");
            }
            if (fileType != UnixStat.FILE_FLAG && fileType != UnixStat.DIR_FLAG) {
                throw new ZipSecurityException(
                    "Rejected entry with non-standard Unix file type 0"
                    + Integer.toOctalString(fileType) + ": '" + name + "'");
            }
        }

        // Rule 4 — canonical path must not escape the target directory
        Path resolved = canonicalTarget.resolve(name).normalize();
        if (!resolved.startsWith(canonicalTarget)) {
            throw new ZipSecurityException(
                "Rejected entry that escapes the target directory: '" + name + "'");
        }
    }

    // -----------------------------------------------------------------------
    // Extraction
    // -----------------------------------------------------------------------

    private void extractEntry(ZipFile zf, ZipArchiveEntry entry, Path targetDir)
            throws IOException {
        Path dest = targetDir.resolve(entry.getName()).normalize();

        if (entry.isDirectory()) {
            Files.createDirectories(dest);
            return;
        }

        // Ensure parent directories exist (ZIP may omit explicit directory entries)
        Files.createDirectories(dest.getParent());

        try (InputStream in = zf.getInputStream(entry)) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        log.debug("Extracted '{}'", dest.getFileName());
    }

    // -----------------------------------------------------------------------
    // Exception type
    // -----------------------------------------------------------------------

    /** Thrown when a ZIP entry violates one of the safe-extraction rules. */
    public static class ZipSecurityException extends IOException {
        public ZipSecurityException(String message) {
            super(message);
        }
    }
}
