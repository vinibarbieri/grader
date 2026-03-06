package com.grader.controller;

import com.grader.controller.dto.ErrorResponse;
import com.grader.service.JobNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(JobNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleJobNotFound(JobNotFoundException ex) {
        return new ErrorResponse(
                "JOB_NOT_FOUND",
                ex.getMessage(),
                null,
                ex.getJobId(),
                Instant.now().toString()
        );
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleMissingPart(MissingServletRequestPartException ex) {
        return new ErrorResponse(
                "MISSING_FILE",
                ex.getMessage(),
                null,
                null,
                Instant.now().toString()
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return new ErrorResponse(
                "INVALID_REQUEST",
                ex.getMessage(),
                null,
                null,
                Instant.now().toString()
        );
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleGeneric(Exception ex) {
        log.error("Unhandled error", ex);
        return new ErrorResponse(
                "INTERNAL_ERROR",
                "An unexpected error occurred",
                ex.getMessage(),
                null,
                Instant.now().toString()
        );
    }
}
