package com.grader.service;

import com.grader.infrastructure.FilesystemJobRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArtifactServiceTest {

    private Path tempWorkspace;
    private FilesystemJobRepository repo;
    private ArtifactService service;

    @BeforeEach
    void setUp() throws IOException {
        tempWorkspace = Files.createTempDirectory("grader-artifact-test-");
        repo = new FilesystemJobRepository(tempWorkspace.toString());
        service = new ArtifactService(repo);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (Files.exists(tempWorkspace)) {
            try (var walk = Files.walk(tempWorkspace)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.delete(p); } catch (IOException ignored) {}
                });
            }
        }
    }

    // -------------------------------------------------------------------------
    // getArtifactBytes
    // -------------------------------------------------------------------------

    @Test
    void getArtifactBytes_csv_returnsCorrectBytes() throws IOException {
        String jobId = "job-csv-001";
        Path jobDir = tempWorkspace.resolve(jobId);
        Files.createDirectories(jobDir);
        byte[] expected = "student,score\nalice,20\n".getBytes();
        Files.write(jobDir.resolve("results.csv"), expected);

        byte[] result = service.getArtifactBytes(jobId, "csv");

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void getArtifactBytes_json_returnsCorrectBytes() throws IOException {
        String jobId = "job-json-001";
        Path jobDir = tempWorkspace.resolve(jobId);
        Files.createDirectories(jobDir);
        byte[] expected = "[{\"studentId\":\"001\"}]".getBytes();
        Files.write(jobDir.resolve("results.json"), expected);

        byte[] result = service.getArtifactBytes(jobId, "json");

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void getArtifactBytes_missingCsvFile_throwsArtifactNotFoundException() throws IOException {
        String jobId = "job-missing-csv";
        Path jobDir = tempWorkspace.resolve(jobId);
        Files.createDirectories(jobDir);
        // no results.csv written

        assertThatThrownBy(() -> service.getArtifactBytes(jobId, "csv"))
                .isInstanceOf(ArtifactNotFoundException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void getArtifactBytes_missingJsonFile_throwsArtifactNotFoundException() throws IOException {
        String jobId = "job-missing-json";
        Path jobDir = tempWorkspace.resolve(jobId);
        Files.createDirectories(jobDir);

        assertThatThrownBy(() -> service.getArtifactBytes(jobId, "json"))
                .isInstanceOf(ArtifactNotFoundException.class)
                .hasMessageContaining("not found");
    }

    // -------------------------------------------------------------------------
    // computeWorkspaceSizeBytes
    // -------------------------------------------------------------------------

    @Test
    void computeWorkspaceSizeBytes_returnsCorrectSize() throws IOException {
        String jobId = "job-size-001";
        Path jobDir = tempWorkspace.resolve(jobId);
        Files.createDirectories(jobDir);
        // Write two files: 10 bytes and 20 bytes
        Files.write(jobDir.resolve("a.txt"), new byte[10]);
        Files.write(jobDir.resolve("b.txt"), new byte[20]);

        long size = service.computeWorkspaceSizeBytes(jobId);

        assertThat(size).isEqualTo(30L);
    }

    @Test
    void computeWorkspaceSizeBytes_emptyDir_returnsZero() throws IOException {
        String jobId = "job-empty-001";
        Path jobDir = tempWorkspace.resolve(jobId);
        Files.createDirectories(jobDir);

        long size = service.computeWorkspaceSizeBytes(jobId);

        assertThat(size).isEqualTo(0L);
    }

    @Test
    void computeWorkspaceSizeBytes_emptyJobDir_returnsZero() throws IOException {
        // FilesystemJobRepository.getJobDirectory() always creates the directory.
        // A job with no uploaded files therefore returns 0, not -1.
        String jobId = "nonexistent-job";
        Path jobDir = tempWorkspace.resolve(jobId);
        Files.createDirectories(jobDir); // mirrors what getJobDirectory does

        long size = service.computeWorkspaceSizeBytes(jobId);

        assertThat(size).isEqualTo(0L);
    }

    @Test
    void computeWorkspaceSizeBytes_nestedFiles_includesAllFiles() throws IOException {
        String jobId = "job-nested-001";
        Path jobDir = tempWorkspace.resolve(jobId);
        Path subDir = jobDir.resolve("submissions").resolve("student1");
        Files.createDirectories(subDir);
        Files.write(subDir.resolve("problem1.c"), new byte[100]);
        Files.write(jobDir.resolve("results.csv"), new byte[50]);

        long size = service.computeWorkspaceSizeBytes(jobId);

        assertThat(size).isEqualTo(150L);
    }
}
