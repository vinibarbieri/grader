package com.grader.service;

import com.grader.infrastructure.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Reads job artifacts (CSV and JSON reports) from the filesystem workspace and
 * computes workspace disk usage.
 */
public class ArtifactService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactService.class);

    private final JobRepository jobRepository;

    public ArtifactService(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    /**
     * Returns the raw bytes of the requested artifact.
     *
     * @param jobId  target job identifier
     * @param format {@code "csv"} or {@code "json"}
     * @return artifact bytes
     * @throws ArtifactNotFoundException if the artifact file does not exist yet
     */
    public byte[] getArtifactBytes(String jobId, String format) {
        String fileName = "csv".equals(format) ? "results.csv" : "results.json";
        Path artifact = jobRepository.getJobDirectory(jobId).resolve(fileName);

        if (!Files.exists(artifact)) {
            throw new ArtifactNotFoundException(
                    "Artifact not found for job " + jobId + ": " + fileName);
        }

        try {
            return Files.readAllBytes(artifact);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read artifact for job " + jobId, e);
        }
    }

    /**
     * Walks the entire job workspace directory and sums the sizes of all regular files.
     * Used to capture {@code tmpfs_peak_bytes} at job completion.
     *
     * @param jobId target job identifier
     * @return total bytes occupied by all files under the job directory,
     *         or {@code -1} if the directory cannot be accessed
     */
    public long computeWorkspaceSizeBytes(String jobId) {
        Path jobDir = jobRepository.getJobDirectory(jobId);
        if (!Files.exists(jobDir)) {
            return -1L;
        }
        try (Stream<Path> stream = Files.walk(jobDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            log.warn("jobId={} could not stat file {}: {}", jobId, p, e.getMessage());
                            return 0L;
                        }
                    })
                    .sum();
        } catch (IOException e) {
            log.warn("jobId={} could not compute workspace size: {}", jobId, e.getMessage());
            return -1L;
        }
    }
}
