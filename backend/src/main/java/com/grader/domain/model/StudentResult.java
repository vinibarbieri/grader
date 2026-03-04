package com.grader.domain.model;

import java.util.List;

public record StudentResult(
        String studentId,
        String studentName,
        String submissionDir,
        int compileErrorsCount,
        int runtimeErrorsCount,
        int timeoutsCount,
        int waCount,
        int okCount,
        double problem1Score,
        double problem2Score,
        double totalScore,
        boolean failedAnyQuestion,
        List<FileResult> files
) {}
