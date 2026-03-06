package com.grader.controller.dto;

import com.grader.domain.Job;

import java.time.Instant;

public record JobStatusResponse(
        String jobId,
        String state,
        Instant startedAt,
        Instant endedAt,
        Progress progress,
        Counters counters
) {

    public record Progress(
            int totalStudents,
            int processedStudents,
            String currentStudent
    ) {}

    public record Counters(
            int casesExecuted,
            int okCount,
            int waCount,
            int timeoutCount,
            int runtimeErrorCount,
            int compileErrorCount,
            int oleCount
    ) {}

    public static JobStatusResponse from(Job job) {
        Progress progress = new Progress(
                job.getTotalStudents(),
                job.getProcessedStudents(),
                job.getCurrentStudent()
        );
        Counters counters = new Counters(
                job.getCasesExecuted(),
                job.getOkCount(),
                job.getWaCount(),
                job.getTimeoutCount(),
                job.getRuntimeErrorCount(),
                job.getCompileErrorCount(),
                job.getOleCount()
        );
        return new JobStatusResponse(
                job.getJobId(),
                job.getState().name().toLowerCase(),
                job.getStartedAt(),
                job.getEndedAt(),
                progress,
                counters
        );
    }
}
