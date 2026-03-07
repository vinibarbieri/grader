package com.grader.service;

import com.grader.domain.CompileStatus;

/**
 * Result of a compilation attempt.
 *
 * @param status  {@link CompileStatus#OK} on success, {@link CompileStatus#COMPILE_ERROR} on failure
 * @param details compiler output (error messages) or "-" on success
 */
public record CompileOutcome(CompileStatus status, String details) {}
