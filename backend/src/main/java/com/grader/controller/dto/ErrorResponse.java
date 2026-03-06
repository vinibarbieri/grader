package com.grader.controller.dto;

public record ErrorResponse(
        String code,
        String message,
        String details,
        String jobId,
        String timestamp
) {}
