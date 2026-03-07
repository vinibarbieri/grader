package com.grader.service;

import com.grader.domain.Job;
import com.grader.domain.JobState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class MetricsServiceTest {

    @Test
    void recordJobCompletion_doesNotThrow_forDoneJob() {
        MetricsService service = new MetricsService();
        Job job = Job.create();
        job.transitionTo(JobState.RUNNING);
        job.transitionTo(JobState.DONE);

        assertThatCode(() ->
                service.recordJobCompletion("job-001", job, 150L, 800L, 0L, 3L, 2048L)
        ).doesNotThrowAnyException();
    }

    @Test
    void recordJobCompletion_doesNotThrow_whenTimestampsAreNull() {
        MetricsService service = new MetricsService();
        Job job = Job.create(); // startedAt / endedAt are null until RUNNING/terminal

        assertThatCode(() ->
                service.recordJobCompletion("job-002", job, 0L, 0L, 0L, 0L, 0L)
        ).doesNotThrowAnyException();
    }

    @Test
    void incrementCancelledJobs_incrementsCounter() {
        MetricsService service = new MetricsService();
        assertThat(service.getCancelledJobsCount()).isEqualTo(0);

        service.incrementCancelledJobs();
        service.incrementCancelledJobs();

        assertThat(service.getCancelledJobsCount()).isEqualTo(2);
    }

    @Test
    void incrementCleanupFailures_incrementsCounter() {
        MetricsService service = new MetricsService();
        assertThat(service.getCleanupFailuresCount()).isEqualTo(0);

        service.incrementCleanupFailures();

        assertThat(service.getCleanupFailuresCount()).isEqualTo(1);
    }

    @Test
    void incrementCleanupFailures_withDelta_addsToCounter() {
        MetricsService service = new MetricsService();
        assertThat(service.getCleanupFailuresCount()).isEqualTo(0);

        service.incrementCleanupFailures(3);
        service.incrementCleanupFailures(0);

        assertThat(service.getCleanupFailuresCount()).isEqualTo(3);
    }

    @Test
    void getSnapshot_returnsCurrentCounts() {
        MetricsService service = new MetricsService();
        service.incrementCancelledJobs();
        service.incrementCleanupFailures();
        service.incrementCleanupFailures();

        MetricsService.Snapshot snap = service.getSnapshot();

        assertThat(snap.cancelledJobs()).isEqualTo(1);
        assertThat(snap.cleanupFailures()).isEqualTo(2);
    }

    @Test
    void countersAreIndependent() {
        MetricsService service = new MetricsService();
        service.incrementCancelledJobs();

        assertThat(service.getCancelledJobsCount()).isEqualTo(1);
        assertThat(service.getCleanupFailuresCount()).isEqualTo(0);
    }
}
