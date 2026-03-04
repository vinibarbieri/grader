package com.grader.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Aggregate root representing a grading job.
 * Not thread-safe by itself — callers must synchronize access when mutating state.
 */
public class Job {

    private final String jobId;
    private JobState state;
    private final Instant createdAt;
    private Instant startedAt;
    private Instant endedAt;

    // Progress counters
    private int totalStudents;
    private int processedStudents;
    private String currentStudent;

    // Case counters
    private int casesExecuted;
    private int okCount;
    private int waCount;
    private int timeoutCount;
    private int runtimeErrorCount;
    private int compileErrorCount;
    private int oleCount;

    public Job(String jobId) {
        this.jobId = jobId;
        this.state = JobState.QUEUED;
        this.createdAt = Instant.now();
    }

    /** Reconstruction constructor — used when rehydrating from persistent storage. */
    public Job(String jobId, JobState state, Instant createdAt, Instant startedAt, Instant endedAt,
               int totalStudents, int processedStudents, String currentStudent,
               int casesExecuted, int okCount, int waCount, int timeoutCount,
               int runtimeErrorCount, int compileErrorCount, int oleCount) {
        this.jobId = jobId;
        this.state = state;
        this.createdAt = createdAt;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.totalStudents = totalStudents;
        this.processedStudents = processedStudents;
        this.currentStudent = currentStudent;
        this.casesExecuted = casesExecuted;
        this.okCount = okCount;
        this.waCount = waCount;
        this.timeoutCount = timeoutCount;
        this.runtimeErrorCount = runtimeErrorCount;
        this.compileErrorCount = compileErrorCount;
        this.oleCount = oleCount;
    }

    public static Job create() {
        String id = "job_" + Instant.now().getEpochSecond() + "_" + UUID.randomUUID().toString().substring(0, 8);
        return new Job(id);
    }

    // --- State machine ---

    public void transitionTo(JobState target) {
        this.state = this.state.transitionTo(target);
        if (target == JobState.RUNNING) {
            this.startedAt = Instant.now();
        }
        if (target.isTerminal()) {
            this.endedAt = Instant.now();
        }
    }

    /**
     * Idempotent cancel: if already terminal or cancelling, does nothing.
     * Returns the resulting state.
     */
    public JobState requestCancel() {
        if (state.isTerminal() || state == JobState.CANCELLING) {
            return state;
        }
        if (state.isCancellable()) {
            // QUEUED jobs can jump directly to CANCELLED, RUNNING go to CANCELLING first
            if (state == JobState.QUEUED) {
                transitionTo(JobState.CANCELLED);
            } else {
                transitionTo(JobState.CANCELLING);
            }
        }
        return state;
    }

    // --- Getters ---

    public String getJobId() {
        return jobId;
    }

    public JobState getState() {
        return state;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public int getTotalStudents() {
        return totalStudents;
    }

    public int getProcessedStudents() {
        return processedStudents;
    }

    public String getCurrentStudent() {
        return currentStudent;
    }

    public int getCasesExecuted() {
        return casesExecuted;
    }

    public int getOkCount() {
        return okCount;
    }

    public int getWaCount() {
        return waCount;
    }

    public int getTimeoutCount() {
        return timeoutCount;
    }

    public int getRuntimeErrorCount() {
        return runtimeErrorCount;
    }

    public int getCompileErrorCount() {
        return compileErrorCount;
    }

    public int getOleCount() {
        return oleCount;
    }

    // --- Setters / mutators ---

    public void setTotalStudents(int totalStudents) {
        this.totalStudents = totalStudents;
    }

    public void setProcessedStudents(int processedStudents) {
        this.processedStudents = processedStudents;
    }

    public void setCurrentStudent(String currentStudent) {
        this.currentStudent = currentStudent;
    }

    public void incrementProcessedStudents() {
        this.processedStudents++;
    }

    public void recordCaseResult(CaseStatus status) {
        casesExecuted++;
        switch (status) {
            case OK -> okCount++;
            case WA -> waCount++;
            case TIMEOUT -> timeoutCount++;
            case RUNTIME_ERROR -> runtimeErrorCount++;
            case COMPILE_ERROR -> compileErrorCount++;
            case OUTPUT_LIMIT_EXCEEDED -> oleCount++;
            default -> { /* SKIP, INTERNAL_ERROR — counted only in casesExecuted */ }
        }
    }
}
