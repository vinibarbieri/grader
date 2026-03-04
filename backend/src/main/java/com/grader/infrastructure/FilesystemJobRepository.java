package com.grader.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.grader.domain.CaseStatus;
import com.grader.domain.Job;
import com.grader.domain.JobState;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Persists each Job as a {@code status.json} file inside
 * {@code <workspaceRoot>/<jobId>/status.json}.
 */
public class FilesystemJobRepository implements JobRepository {

    private final Path workspaceRoot;
    private final ObjectMapper mapper;

    public FilesystemJobRepository(String workspaceRoot) {
        this.workspaceRoot = Path.of(workspaceRoot);
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public void save(Job job) {
        Path dir = jobDir(job.getJobId());
        try {
            Files.createDirectories(dir);
            JobSnapshot snapshot = JobSnapshot.from(job);
            mapper.writeValue(statusFile(job.getJobId()).toFile(), snapshot);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save job " + job.getJobId(), e);
        }
    }

    @Override
    public Optional<Job> findById(String jobId) {
        Path file = statusFile(jobId);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            JobSnapshot snapshot = mapper.readValue(file.toFile(), JobSnapshot.class);
            return Optional.of(snapshot.toJob());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read job " + jobId, e);
        }
    }

    @Override
    public boolean exists(String jobId) {
        return Files.exists(statusFile(jobId));
    }

    @Override
    public Path getJobDirectory(String jobId) {
        Path dir = jobDir(jobId);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create job directory for " + jobId, e);
        }
        return dir;
    }

    // --- Private helpers ---

    private Path jobDir(String jobId) {
        return workspaceRoot.resolve(jobId);
    }

    private Path statusFile(String jobId) {
        return jobDir(jobId).resolve("status.json");
    }

    // --- Internal DTO for JSON persistence ---

    static class JobSnapshot {
        public String jobId;
        public String state;
        public Instant createdAt;
        public Instant startedAt;
        public Instant endedAt;
        public int totalStudents;
        public int processedStudents;
        public String currentStudent;
        public int casesExecuted;
        public int okCount;
        public int waCount;
        public int timeoutCount;
        public int runtimeErrorCount;
        public int compileErrorCount;
        public int oleCount;

        static JobSnapshot from(Job job) {
            JobSnapshot s = new JobSnapshot();
            s.jobId = job.getJobId();
            s.state = job.getState().name();
            s.createdAt = job.getCreatedAt();
            s.startedAt = job.getStartedAt();
            s.endedAt = job.getEndedAt();
            s.totalStudents = job.getTotalStudents();
            s.processedStudents = job.getProcessedStudents();
            s.currentStudent = job.getCurrentStudent();
            s.casesExecuted = job.getCasesExecuted();
            s.okCount = job.getOkCount();
            s.waCount = job.getWaCount();
            s.timeoutCount = job.getTimeoutCount();
            s.runtimeErrorCount = job.getRuntimeErrorCount();
            s.compileErrorCount = job.getCompileErrorCount();
            s.oleCount = job.getOleCount();
            return s;
        }

        Job toJob() {
            Job job = new Job(jobId, JobState.valueOf(state), createdAt, startedAt, endedAt,
                    totalStudents, processedStudents, currentStudent,
                    casesExecuted, okCount, waCount, timeoutCount,
                    runtimeErrorCount, compileErrorCount, oleCount);
            return job;
        }
    }
}
