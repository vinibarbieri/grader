package com.grader.service;

import com.grader.domain.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Captures and logs job-level performance metrics and maintains global lifecycle counters.
 *
 * <h2>Per-job metrics logged at completion</h2>
 * <ul>
 *   <li>{@code job_total_ms} — wall-clock duration from RUNNING to terminal state</li>
 *   <li>{@code compile_total_ms} — sum of all compilation durations</li>
 *   <li>{@code run_total_ms} — sum of all case execution durations (includes compare)</li>
 *   <li>{@code compare_total_ms} — always 0 (comparison is in-memory and included in run time)</li>
 *   <li>{@code cases_executed}, {@code ok_count}, {@code wa_count}, {@code timeout_count},
 *       {@code runtime_error_count}, {@code compile_error_count}, {@code ole_count} — from Job counters</li>
 *   <li>{@code killed_processes_count} — number of PGID kills issued by ProcessGroupLauncher</li>
 *   <li>{@code tmpfs_peak_bytes} — workspace directory size at job completion</li>
 * </ul>
 *
 * <h2>Global counters</h2>
 * <ul>
 *   <li>{@code cancelled_jobs_count} — incremented each time a job finishes as CANCELLED</li>
 *   <li>{@code cleanup_failures_count} — incremented each time process cleanup verification fails</li>
 * </ul>
 */
public class MetricsService {

    private static final Logger log = LoggerFactory.getLogger(MetricsService.class);

    private final AtomicLong cancelledJobs = new AtomicLong();
    private final AtomicLong cleanupFailures = new AtomicLong();

    /**
     * Logs a structured metrics line for the completed job and updates global counters.
     *
     * @param jobId             job identifier
     * @param job               job aggregate (must be in a terminal or cancelling state)
     * @param compileTotalMs    total time spent compiling all source files
     * @param runTotalMs        total time spent executing all test cases
     * @param killedProcesses   number of PGID kill sequences issued
     * @param cleanupFailures   number of cases where post-kill cleanup verification failed
     * @param tmpfsPeakBytes    workspace directory size in bytes at the moment of logging
     */
    public void recordJobCompletion(String jobId, Job job,
                                    long compileTotalMs, long runTotalMs,
                                    long killedProcesses, long cleanupFailures,
                                    long tmpfsPeakBytes) {
        long jobTotalMs = computeJobTotalMs(job);

        log.info("METRICS jobId={} state={} " +
                        "job_total_ms={} compile_total_ms={} run_total_ms={} compare_total_ms=0 " +
                        "cases_executed={} ok_count={} wa_count={} timeouts_count={} " +
                        "runtime_errors_count={} compile_error_count={} ole_count={} " +
                        "killed_processes_count={} cleanup_failures_count={} " +
                        "tmpfs_peak_bytes={} " +
                        "cancelled_jobs_count={} cancelled_jobs_total={} cleanup_failures_total={}",
                jobId, job.getState().name().toLowerCase(),
                jobTotalMs, compileTotalMs, runTotalMs,
                job.getCasesExecuted(), job.getOkCount(), job.getWaCount(), job.getTimeoutCount(),
                job.getRuntimeErrorCount(), job.getCompileErrorCount(), job.getOleCount(),
                killedProcesses, cleanupFailures,
                tmpfsPeakBytes,
                this.cancelledJobs.get(), this.cancelledJobs.get(), this.cleanupFailures.get());
    }

    /** Increments the global cancelled-jobs counter. Call once per cancelled job. */
    public void incrementCancelledJobs() {
        cancelledJobs.incrementAndGet();
    }

    /** Increments the global cancelled-jobs counter by {@code delta}. */
    public void incrementCancelledJobs(long delta) {
        if (delta > 0) {
            cancelledJobs.addAndGet(delta);
        }
    }

    /** Increments the global cleanup-failures counter. Call once per failed cleanup. */
    public void incrementCleanupFailures() {
        cleanupFailures.incrementAndGet();
    }

    /** Increments the global cleanup-failures counter by {@code delta}. */
    public void incrementCleanupFailures(long delta) {
        if (delta > 0) {
            cleanupFailures.addAndGet(delta);
        }
    }

    public long getCancelledJobsCount() {
        return cancelledJobs.get();
    }

    public long getCleanupFailuresCount() {
        return cleanupFailures.get();
    }

    /** Returns a point-in-time snapshot of global counters. */
    public Snapshot getSnapshot() {
        return new Snapshot(cancelledJobs.get(), cleanupFailures.get());
    }

    public record Snapshot(long cancelledJobs, long cleanupFailures) {}

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private long computeJobTotalMs(Job job) {
        if (job.getStartedAt() == null || job.getEndedAt() == null) {
            return -1L;
        }
        return Duration.between(job.getStartedAt(), job.getEndedAt()).toMillis();
    }
}
