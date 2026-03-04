package com.grader.domain.model;

import com.grader.domain.CaseStatus;

public record CaseResult(
        String caseName,
        CaseStatus status,
        String details,
        String expectedOutput,
        String programOutput,
        long durationMs
) {}
